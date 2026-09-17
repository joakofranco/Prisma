# 🎬 Demo guiada — PRISMA en Kubernetes (MVP, minikube)

Guion paso a paso para grabar un video mostrando PRISMA corriendo en Kubernetes y demostrando
las tres cosas que un orquestador da "gratis" frente a `docker compose`: **auto-recuperación**,
**rolling update sin downtime** y **escalado horizontal**. Ver el análisis completo en
[`docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md`](mcu-5.0/Kubernetes-Analisis-Viabilidad.md)
(secciones 3.1 P1-P3): esta demo es la Fase 1 de ese documento, hecha filmable.

Para el PPT que acompaña la grabación (slides + notas del orador), ver
[`docs/Demo-Kubernetes-MVP-Slides.md`](Demo-Kubernetes-MVP-Slides.md).

Usa el overlay `infra/kubernetes/overlays/demo/`, una variante **sólo para esto** del overlay
`dev` normal: saca Ollama y MinIO (los componentes más pesados y sin uso en el guion) y el
Ingress (se accede directo al Service `frontend` por `kubectl port-forward` — más simple para
grabar que sumar ingress-nginx + cert-manager). Ver el comentario al principio de
[`kustomization.yaml`](../infra/kubernetes/overlays/demo/kustomization.yaml) para el detalle
completo de qué cambia y por qué.

**No confundir con un ambiente de despliegue real** (eso es `overlays/{dev,staging,prod}/, ver
[`infra/kubernetes/README.md`](../infra/kubernetes/README.md) y
[`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)). Esto es una
demo desechable: se arma, se graba, se borra.

---

## 0. Antes de grabar (una sola vez, sin cámara)

**Software necesario:** Docker Desktop corriendo, `minikube`, `kubectl`, `openssl` (ya viene
con Git Bash en Windows). Ver instalación de minikube en
[`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md#-1-instalación-de-minikube).

**Ensayo obligatorio.** Corré el flujo completo (secciones 1 a 7) al menos una vez sin grabar.
Dos puntos concretos que este overlay ya mitiga pero vale la pena confirmar en tu máquina:

- **Keycloak.** `base/keycloak.yaml` originalmente prueba salud contra un path
  (`/health/ready` en el puerto de management) que el equipo **nunca confirmó** contra la
  imagen 26.7 (ver `infra/kubernetes/README.md`, "Keycloak — caveats operativos"). El overlay
  `demo` ya lo reemplaza por un probe TCP simple sobre el puerto HTTP (ver `patches.yaml`) para
  no dejar la grabación a merced de eso — pero confirmá en el ensayo que el pod `keycloak`
  efectivamente llega a `Running 1/1` sin reintentos eternos.
- **Tiempos de arranque.** Postgres/Keycloak/Chroma tardan más en frío la primera vez (imágenes
  bajándose). El ensayo también sirve para tener las imágenes de terceros ya cacheadas en el
  nodo de minikube antes de grabar, así el `make k8s-demo-up` en cámara es rápido.

Dejá el cluster **corriendo** (`minikube stop`, no `minikube delete`) entre el ensayo y la
grabación si podés — te ahorra los 2-3 minutos de arranque en frío del nodo.

---

## 1. Arrancar el cluster *(en cámara: ~1 min)*

```bash
minikube start --driver=docker --cpus=4 --memory=6144
minikube status
kubectl get nodes
```

> *Guion sugerido:* "Esto es un cluster de Kubernetes de un solo nodo corriendo en un
> contenedor Docker en mi máquina — minikube. En un ambiente real serían 2 o 3 nodos, pero para
> mostrar cómo funciona la tecnología, uno alcanza."

(Opcional, para que `kubectl top` y el HPA muestren métricas en vivo más adelante):

```bash
minikube addons enable metrics-server
```

---

## 2. Construir las imágenes y desplegar *(en cámara: ~2-3 min, más el tiempo de arranque de los pods)*

Las 3 imágenes propias (`frontend`, `backend-core`, `backend-ai`) se construyen **dentro del
daemon docker de minikube** — no se publican a GHCR para esta demo, por eso el overlay usa
`imagePullPolicy: Never` (ver `infra/kubernetes/overlays/demo/patches.yaml`).

```bash
make k8s-demo-images   # build de las 3 imágenes dentro de minikube
make k8s-demo-up       # namespace + Secret real (passwords random) + kubectl apply -k + espera a Ready
```

> *Guion sugerido, mientras corre:* "`kubectl apply -k` toma los manifiestos declarativos del
> repo — están versionados en `infra/kubernetes/` — y le dice al cluster el estado deseado.
> Kubernetes se encarga de programar cada pod, esperar a que pase sus health checks y
> mantenerlo así todo el tiempo que el namespace exista; eso es lo que vamos a ver ahora."

`make k8s-demo-up` no termina hasta que todos los rollouts están `Ready` (usa
`kubectl rollout status`), así que es un buen punto para cortar y retomar en la edición si el
arranque tarda.

---

## 3. Recorrido de lo desplegado *(en cámara: ~2 min)*

```bash
kubectl get pods -n prisma-demo -o wide
kubectl get deployments,statefulsets -n prisma-demo
kubectl get svc -n prisma-demo
kubectl get hpa -n prisma-demo
```

Puntos para señalar en cámara (mapea 1 a 1 con la tabla de arquitectura del análisis de
viabilidad, sección 2.1):

- `frontend`, `backend-core`, `backend-ai`: **2 réplicas cada uno** — son stateless, toda la
  persistencia vive en Postgres/Redis/Chroma, no en el proceso.
- `postgres`, `keycloak-db`: **StatefulSet, 1 réplica** — bases de datos con estado real, cada
  una con su propio volumen persistente (`kubectl get pvc -n prisma-demo`).
- `chroma`: Deployment de 1 réplica — el índice vectorial del RAG, separado de `backend-ai`
  justamente para que este último pueda tener 2+ réplicas (ver comentario en
  `infra/kubernetes/base/chroma.yaml`).
- `backend-core-hpa`: ya existe un HorizontalPodAutoscaler apuntando a `backend-core`
  (2→4 réplicas acá, 2→8 en un cluster real) — se ve en la sección 8.

**Qué NO está en esta demo, y por qué** (bueno decirlo explícito en el video, conecta con el
análisis de viabilidad): sin Ollama/LLM (el modelo de ~4.7 GB no se descarga; el RAG degrada sin
romper), sin MinIO (la subida de evidencia no funciona), sin Ingress/TLS (se entra directo al
Service `frontend`). Ninguno de los tres hace falta para mostrar cómo Kubernetes orquesta la
app — son la misma frontera que ya traza `docker-compose.mvp.yml` (perfil `mvp`) para una demo
liviana en Compose.

---

## 4. Abrir el Kubernetes Dashboard *(en cámara: ~1 min)*

Complemento visual para el resto de la demo: el **Kubernetes Dashboard** es la UI web oficial
del proyecto, viene empaquetada con minikube (no hay que instalar nada aparte) y muestra
pods/deployments/services/HPA con actualizaciones en vivo — mucho más claro en video que sólo
`kubectl get pods -w` en una terminal.

En una terminal aparte (igual que el `port-forward` de la sección 5, queda ocupando esa
terminal mientras dure la demo):

```bash
make k8s-demo-dashboard
# minikube dashboard
```

Abre una pestaña del navegador sola. Una vez ahí:

1. Arriba a la izquierda, cambiar el namespace de `default` a **`prisma-demo`**.
2. Ir a **Workloads → Deployments** (o **Pods**) para ver las cards de `frontend`,
   `backend-core`, `backend-ai`, `keycloak`, `chroma` con su cantidad de réplicas listas.

> *Guion sugerido:* "Además de la terminal, vamos a tener abierto el Kubernetes Dashboard —
> la interfaz gráfica oficial del proyecto — para ver gráficamente cada cambio que hagamos:
> pods reiniciándose, el rollout avanzando, las réplicas subiendo. Selecciono el namespace de
> la demo, `prisma-demo`, arriba a la izquierda."

Dejar esta pestaña abierta (o en un segundo monitor/ventana) durante las secciones 6, 7 y 8 —
cada demo en vivo tiene abajo una nota de qué mirar ahí en simultáneo con la terminal.

> El equipo también documentó una integración con **Portainer** para administrar el cluster
> (ver [`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)) — es una
> herramienta más completa a nivel equipo, pero requiere desplegar un agente y conectar redes
> Docker a mano; de más para esta demo puntual, donde el Dashboard integrado alcanza.

---

## 5. Abrir la app y loguearse *(en cámara: ~1 min)*

En una terminal aparte (queda corriendo durante todo el resto de la demo):

```bash
make k8s-demo-open
# kubectl port-forward svc/frontend 8080:80 -n prisma-demo
```

Abrir **http://localhost:8080** en el navegador. Login:

| Usuario | Password |
|---|---|
| `admin@prisma.local` | `Admin1234!` |

> La contraseña es `temporary: true` en el realm de Keycloak — al primer login pide cambiarla.
> Para no interrumpir la grabación con esa pantalla, podés cambiarla en el ensayo (sección 0) y
> usar la nueva en la grabación real.

Con esto ya se ve la app real funcionando servida por pods de Kubernetes — buen corte para
pasar a la parte de infraestructura.

---

## 6. Demo 1 — Auto-recuperación (self-healing) *(en cámara: ~2 min)*

Idea: probar que **matar un pod a mano no tira abajo el servicio**, porque Kubernetes lo
reprograma solo y el Service sigue enrutando tráfico al pod que queda arriba.

Abrí dos terminales nuevas.

**Terminal A** — mirar los pods en vivo:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

**Terminal B** — un pod de prueba que le pega al Service (no a un pod puntual) cada segundo,
para probar que el Service en sí nunca deja de responder:

```bash
kubectl run curl-loop -n prisma-demo --image=curlimages/curl:8.10.1 --restart=Never -- \
  sh -c 'while true; do curl -s -o /dev/null -w "%{http_code}\n" http://backend-core:8080/actuator/health/readiness; sleep 1; done'
kubectl logs -f curl-loop -n prisma-demo
```

Debería imprimir `200` una vez por segundo, sin cortes.

**Terminal C** — acá es donde se "rompe" algo en cámara:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core
# copiar el nombre de UNO de los dos pods
kubectl delete pod <nombre-del-pod> -n prisma-demo
```

> *Guion sugerido:* "Voy a borrar uno de los dos pods de backend-core a mano, como si se hubiera
> caído por un error o un OOM. Miren la Terminal A: Kubernetes lo detecta y arranca uno nuevo
> solo. Y la Terminal B, que le está pegando al servicio una vez por segundo, ni se entera —
> sigue respondiendo `200` todo el tiempo porque el otro pod nunca dejó de estar arriba."

> minikube es de un solo nodo — esto prueba que el Deployment/Service reparte tráfico entre
> pods y que la caída de UNO no tumba el servicio, no tolerancia a la caída de un nodo entero
> (eso sólo se prueba en un cluster multi-nodo real, ver
> `docs/Kubernetes-Minikube-Portainer.md`).

**En el Dashboard** (sección 4): con el namespace `prisma-demo` seleccionado, ir a
**Workloads → Pods** — se ve el pod borrado pasar a `Terminating` y uno nuevo aparecer en
`ContainerCreating` → `Running` en tiempo real, sin tocar nada ahí.

---

## 7. Demo 2 — Rolling update sin downtime *(en cámara: ~2 min)*

`backend-core.yaml` ya trae `maxSurge: 1, maxUnavailable: 0` — durante un rollout, Kubernetes
**primero** levanta el pod nuevo y lo espera Ready antes de bajar uno viejo, nunca al revés.
Con la Terminal B de la sección anterior todavía corriendo (o relanzá `curl-loop` si la
cerraste):

```bash
kubectl rollout restart deployment/backend-core -n prisma-demo
kubectl rollout status  deployment/backend-core -n prisma-demo
```

> *Guion sugerido:* "Esto simula desplegar una versión nueva del backend en horario laboral,
> sin cortar a nadie que esté usando el sistema. Miren la Terminal B: sigue en `200` todo el
> rollout. Con `docker compose up -d`, ese mismo cambio recrea el contenedor y hay una ventana
> real de caída."

Para ver el detalle del reemplazo pod por pod en tiempo real:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

**En el Dashboard:** el Deployment `backend-core` en **Workloads → Deployments** muestra el
rollout en curso (réplicas "up to date" avanzando de a una) sin que el contador total de Pods
Ready baje nunca de 2.

---

## 8. Demo 3 — Escalado horizontal *(en cámara: ~1-2 min)*

Ya existe un `HorizontalPodAutoscaler` (`backend-core-hpa`, 2→4 en esta demo) que haría esto
solo bajo carga real de CPU/memoria — generar esa carga de forma confiable en cámara es
complicado, así que la demo hace el mismo gesto a mano (es exactamente lo que el HPA automatiza):

```bash
kubectl get hpa -n prisma-demo
kubectl scale deployment/backend-core -n prisma-demo --replicas=4
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w
```

> *Guion sugerido:* "Esto mismo lo hace Kubernetes solo cuando el HPA que se ve arriba detecta
> CPU o memoria por encima del umbral — acá lo disparo a mano para que se vea en el video, pero
> en un pico de uso real (varias organizaciones subiendo evidencia el mismo día, por ejemplo)
> pasaría sin que nadie lo toque."

Volver a 2 réplicas para cerrar prolijo:

```bash
kubectl scale deployment/backend-core -n prisma-demo --replicas=2
```

**En el Dashboard:** la card de `backend-core` en **Workloads → Deployments** cambia el
contador de réplicas de 2 a 4 y de vuelta a 2 en vivo — buen plano final para esta sección.

---

## 9. Cierre y limpieza

Cerrar el `curl-loop`, el `port-forward` y la pestaña del Dashboard (Ctrl+C en las terminales
que quedaron abiertas), y:

```bash
kubectl delete pod curl-loop -n prisma-demo --ignore-not-found
```

Para dejar la máquina como estaba:

```bash
make k8s-demo-down       # borra el namespace prisma-demo (pods + PVCs); el cluster minikube queda
make k8s-demo-destroy    # opcional: apaga y borra el cluster minikube entero
```

---

## Cheatsheet — todos los comandos `make` de esta demo

| Comando | Qué hace |
|---|---|
| `make k8s-demo-images` | Construye `frontend`/`backend-core`/`backend-ai` dentro del daemon docker de minikube |
| `make k8s-demo-secrets` | Crea el namespace `prisma-demo` + el Secret real (se corre solo, es prerequisito de `k8s-demo-up`) |
| `make k8s-demo-up` | `kubectl apply -k overlays/demo` + espera a que todo esté `Ready` |
| `make k8s-demo-status` | `kubectl get pods,svc,hpa -n prisma-demo` |
| `make k8s-demo-dashboard` | `minikube dashboard` — abre el Kubernetes Dashboard en el navegador |
| `make k8s-demo-open` | `kubectl port-forward svc/frontend 8080:80` (dejar corriendo, abrir el navegador) |
| `make k8s-demo-down` | Borra el namespace `prisma-demo` (conserva el cluster minikube) |
| `make k8s-demo-destroy` | `minikube delete` — borra el cluster entero |

## Troubleshooting

- **`ImagePullBackOff` en `backend-core`/`backend-ai`/`frontend`.** `make k8s-demo-images` corrió
  contra el daemon docker equivocado (el del host, no el de minikube) — repetilo asegurando que
  el `eval $(minikube docker-env ...)` se ejecutó en el mismo shell que el `docker build`
  (`make k8s-demo-images` ya lo hace en un solo comando; si lo corriste a mano en pasos
  separados, cada línea de terminal es un shell nuevo y el `eval` no persiste).
- **`keycloak` nunca llega a `Running 1/1`.** Ver la nota de la sección 0 sobre el probe TCP.
  Si igual falla, `kubectl describe pod -n prisma-demo -l app.kubernetes.io/name=keycloak` y
  `kubectl logs -n prisma-demo -l app.kubernetes.io/name=keycloak` para ver si el problema es
  otro (memoria insuficiente en el nodo es la causa más común).
- **Login falla con "invalid client credentials" o similar.** `KEYCLOAK_ADMIN_CLIENT_SECRET`
  del Secret tiene que ser exactamente `CHANGE_ME_IN_PRODUCTION` — es el valor con el que el
  realm importado crea el client `prisma-backend` (ver `infra/keycloak/realm-prisma.json`).
  `make k8s-demo-secrets` ya lo fija así; si armaste el Secret a mano, revisá ese valor.
- **El Dashboard no muestra gráficos de CPU/memoria por pod.** Hace falta el addon
  `metrics-server` (ver sección 1, es opcional para todo lo demás pero el Dashboard lo usa para
  esos gráficos): `minikube addons enable metrics-server`.
- **`kubectl apply -k overlays/demo` falla por el `configMapGenerator` del realm.** Falta
  `make sync-k8s-assets` (o corrió `make k8s-demo-images`/`k8s-demo-up`, que ya lo incluyen como
  prerequisito) — genera `infra/kubernetes/base/keycloak-realm.json`, gitignorado, a partir de
  `infra/keycloak/realm-prisma.json`.
