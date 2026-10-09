# Demo Kubernetes — Referencia de Comandos

Todos los comandos del guion de la demo PRISMA en Kubernetes (minikube), en orden de ejecución.
Las explicaciones en texto plano describen cada comando pero **no se copian** al usar el botón de copiar del bloque de código.

---

## Paso 0 — Antes de grabar: verificar pre-requisitos

Verifica que Docker Desktop esté corriendo:

```bash
docker version
```

Confirma que minikube y kubectl están instalados:

```bash
minikube version && kubectl version --client
```

---

## Paso 1 — Arrancar el cluster *(~1 min en cámara)*

Crea/arranca el nodo de K8s como contenedor Docker con 4 CPU y 6 GiB asignados.
El flag `--container-runtime=docker` es obligatorio para que el build de imágenes funcione en el paso 2.

```bash
minikube start --driver=docker --container-runtime=docker --cpus=4 --memory=6144
```

Verifica que el cluster (host, kubelet, apiserver) esté Running:

```bash
minikube status
```

Confirma que el nodo quedó Ready y muestra la versión de K8s:

```bash
kubectl get nodes
```

Opcional — instala Metrics Server para que `kubectl top` y el HPA lean uso real de CPU/memoria:

```bash
minikube addons enable metrics-server
```

---

## Paso 2 — Construir imágenes y desplegar *(~2-3 min en cámara)*

Construye las 3 imágenes propias (`frontend`, `backend-core`, `backend-ai`) dentro del daemon Docker de minikube — no se publican a GHCR:

```bash
make k8s-demo-images
```

Crea el namespace, genera el Secret con passwords aleatorias, aplica todos los manifiestos y espera a que todos los pods estén Ready:

```bash
make k8s-demo-up
```

---

## Paso 3 — Recorrido de lo desplegado *(~2 min en cámara)*

Lista todos los pods del namespace con su IP interna y en qué nodo quedaron programados:

```bash
kubectl get pods -n prisma-demo -o wide
```

Muestra réplicas deseadas vs. listas de cada Deployment y StatefulSet:

```bash
kubectl get deployments,statefulsets -n prisma-demo
```

Lista los Services: IP estable y balanceo que expone cada componente dentro del cluster:

```bash
kubectl get svc -n prisma-demo
```

Muestra el HorizontalPodAutoscaler configurado y sus límites min/max de réplicas:

```bash
kubectl get hpa -n prisma-demo
```

Muestra los Persistent Volume Claims (almacenamiento de `postgres` y `keycloak-db`):

```bash
kubectl get pvc -n prisma-demo
```

---

## Paso 4 — Abrir el Kubernetes Dashboard *(~1 min — terminal aparte)*

Abre el Dashboard en el navegador levantando un proxy local a la UI del cluster.
Dejar corriendo en una terminal aparte durante toda la demo:

```bash
make k8s-demo-dashboard
```

Alternativa — instala OpenLens en Windows vía Chocolatey (app de escritorio, sin cuenta):

```bash
choco install openlens
```

---

## Paso 5 — Abrir la app y loguearse *(~1 min — terminal aparte)*

Abre un túnel local `localhost:8888 → puerto 80 del Service frontend`.
Dejar corriendo durante toda la demo. Luego abrir **http://localhost:8888** en el navegador:

```bash
make k8s-demo-open
```

Credenciales de acceso:

| Usuario | Password |
|---|---|
| `admin@prisma.local` | `Admin1234!` |

> La contraseña es `temporary: true` en Keycloak — al primer login pide cambiarla. Cambiarla en el ensayo (paso 0) para no interrumpir la grabación.

---

## Demo 1 — Auto-recuperación (self-healing) *(~2 min — 3 terminales)*

### Terminal A — watch de pods en vivo

Queda mirando los pods de `backend-core` y refresca automáticamente cada vez que cambia alguno:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

### Terminal B — loop de curl al Service

Levanta un pod descartable que hace curl al Service `backend-core` una vez por segundo
e imprime el código HTTP. Debería mostrar `200` sin cortes durante todo el experimento:

```bash
kubectl run curl-loop -n prisma-demo --image=curlimages/curl:8.10.1 --restart=Never -- \
  sh -c 'while true; do curl -s -o /dev/null -w "%{http_code}\n" http://backend-core:8080/actuator/health/readiness; sleep 1; done'
```

Muestra en vivo lo que va imprimiendo el loop (follow de logs):

```bash
kubectl logs -f curl-loop -n prisma-demo
```

### Terminal C — "romper" un pod en cámara

Lista los pods de `backend-core` para copiar el nombre de uno:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core
```

Reemplazar `<nombre-del-pod>` con el nombre copiado. Simula una caída; el Deployment repone la réplica solo:

```bash
kubectl delete pod <nombre-del-pod> -n prisma-demo
```

---

## Demo 2 — Rolling update sin downtime *(~2 min)*

Dispara un rolling update: recrea los pods de a uno respetando `maxSurge/maxUnavailable`
(primero levanta el nuevo, después baja el viejo):

```bash
kubectl rollout restart deployment/backend-core -n prisma-demo
```

Se queda esperando y muestra el progreso del rollout hasta que termina:

```bash
kubectl rollout status deployment/backend-core -n prisma-demo
```

Muestra en vivo cómo aparece el pod nuevo (Ready) antes de que se borre el viejo:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

---

## Demo 3 — Escalado horizontal *(~1-2 min)*

Muestra el HPA y sus límites (2→4 réplicas en esta demo) antes de forzar el escalado a mano:

```bash
kubectl get hpa -n prisma-demo
```

Pide al Deployment que pase de 2 a 4 réplicas — lo mismo que haría el HPA automáticamente bajo carga real de CPU:

```bash
kubectl scale deployment/backend-core -n prisma-demo --replicas=4
```

Mira en vivo cómo aparecen los 2 pods nuevos:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

Vuelve a bajar a 2 réplicas — el estado "normal" del deployment:

```bash
kubectl scale deployment/backend-core -n prisma-demo --replicas=2
```

---

## Paso 9 — Cierre y limpieza

Borra el pod de prueba del curl-loop (`--ignore-not-found` evita error si ya no existe):

```bash
kubectl delete pod curl-loop -n prisma-demo --ignore-not-found
```

Borra el namespace `prisma-demo` (pods + PVCs); el cluster minikube queda vivo:

```bash
make k8s-demo-down
```

Opcional — apaga y borra el cluster minikube entero:

```bash
make k8s-demo-destroy
```

---

## Cheatsheet — comandos `make`

| Comando | Qué hace |
|---|---|
| `make k8s-demo-images` | Construye `frontend`/`backend-core`/`backend-ai` dentro del daemon Docker de minikube |
| `make k8s-demo-secrets` | Crea el namespace `prisma-demo` + el Secret (pre-requisito de `k8s-demo-up`) |
| `make k8s-demo-up` | `kubectl apply -k overlays/demo` + espera a que todo esté Ready |
| `make k8s-demo-status` | `kubectl get pods,svc,hpa -n prisma-demo` |
| `make k8s-demo-dashboard` | Abre el Kubernetes Dashboard en el navegador |
| `make k8s-demo-open` | `kubectl port-forward svc/frontend 8888:80` — dejar corriendo, abrir el navegador |
| `make k8s-demo-down` | Borra el namespace `prisma-demo` (conserva el cluster minikube) |
| `make k8s-demo-destroy` | `minikube delete` — borra el cluster entero |
