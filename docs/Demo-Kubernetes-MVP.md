# 🎬 Demo guiada — PRISMA en Kubernetes (MVP, minikube)

Guion paso a paso para grabar un video mostrando PRISMA corriendo en Kubernetes y demostrando
las tres cosas que un orquestador da "gratis" frente a `docker compose`: **auto-recuperación**,
**rolling update sin downtime** y **escalado horizontal**. Ver el análisis completo en
[`docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md`](mcu-5.0/Kubernetes-Analisis-Viabilidad.md)
(secciones 3.1 P1-P3): esta demo es la Fase 1 de ese documento, hecha filmable.

Para el PPT que acompaña la grabación: el contenido slide por slide (bullets + notas del
orador) está en [`docs/Demo-Kubernetes-MVP-Slides.md`](Demo-Kubernetes-MVP-Slides.md), y un
prompt listo para generar el diseño con una IA de presentaciones (Gamma, Tome, Canva, etc.) está
en [`docs/Demo-Kubernetes-MVP-Prompt.md`](Demo-Kubernetes-MVP-Prompt.md).

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
Opcional: [Lens/OpenLens](https://k8slens.dev) si preferís esa UI de escritorio en vez del
Kubernetes Dashboard integrado para la sección 4 (ninguno de los dos es obligatorio).

**Ensayo obligatorio.** Corré el flujo completo (secciones 1 a 7) al menos una vez sin grabar.
Dos puntos concretos que este overlay ya mitiga pero vale la pena confirmar en tu máquina:

- **Keycloak.** `base/keycloak.yaml` originalmente prueba salud contra un path
  (`/health/ready` en el puerto de management) que el equipo **nunca confirmó** contra la
  imagen 26.7 (ver `infra/kubernetes/README.md`, "Keycloak — caveats operativos"). El overlay
  `demo` ya lo reemplaza por un probe TCP simple sobre el puerto HTTP (ver `patches.yaml`) para
  no dejar la grabación a merced de eso — pero confirmá en el ensayo que el pod `keycloak`
  efectivamente llega a `Running 1/1` sin reintentos eternos.
- **Tiempos de arranque.** Postgres/Keycloak tardan más en frío la primera vez (imágenes
  bajándose). El ensayo también sirve para tener las imágenes de terceros ya cacheadas en el
  nodo de minikube antes de grabar, así el `make k8s-demo-up` en cámara es rápido.
- **Chroma.** El overlay `demo` NO despliega Chroma (a diferencia de `dev`/`staging`/`prod`) —
  su rollout llegó a colgarse en un minikube chico (`0 of 1 updated replicas are available`
  sin salir nunca de ahí). Sacarlo también alinea el MVP de K8s con el mismo alcance que
  `docker-compose.mvp.yml` ya define para Compose. Ver la sección 3 para el detalle de qué
  implica para el RAG.

Dejá el cluster **corriendo** (`minikube stop`, no `minikube delete`) entre el ensayo y la
grabación si podés — te ahorra los 2-3 minutos de arranque en frío del nodo.

---

## 1. Arrancar el cluster *(en cámara: ~1 min)*

```bash
minikube start --driver=docker --container-runtime=docker --cpus=4 --memory=6144  # crea/arranca el nodo de K8s como contenedor Docker, con 4 CPU y 6 GiB asignados
minikube status                                                                    # chequea que el cluster (host, kubelet, apiserver) esté Running
kubectl get nodes                                                                  # confirma que el nodo minikube quedó Ready y qué versión de K8s corre
```

> *Guion sugerido:* "Esto es un cluster de Kubernetes de un solo nodo corriendo en un
> contenedor Docker en mi máquina — minikube. En un ambiente real serían 2 o 3 nodos, pero para
> mostrar cómo funciona la tecnología, uno alcanza."

> **Importante:** fijar `--container-runtime=docker` explícito. Sin ese flag, algunas versiones
> de minikube arrancan el nodo con **containerd** como runtime por dentro, y el paso 2
> (`make k8s-demo-images`, que depende de `minikube docker-env`) queda colgado sin avanzar — ver
> "El `docker build` de `k8s-demo-images` se queda colgado" en el Troubleshooting.

(Opcional, para que `kubectl top` y el HPA muestren métricas en vivo más adelante):

```bash
minikube addons enable metrics-server  # instala el Metrics Server (para que `kubectl top` y el HPA lean uso real de CPU/memoria)
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
kubectl get pods -n prisma-demo -o wide             # lista los pods del namespace con su IP y en qué nodo quedaron
kubectl get deployments,statefulsets -n prisma-demo # réplicas deseadas vs. listas de cada Deployment/StatefulSet
kubectl get svc -n prisma-demo                      # Services (IP estable + balanceo) que exponen cada componente dentro del cluster
kubectl get hpa -n prisma-demo                      # HorizontalPodAutoscaler configurado, con sus límites min/max de réplicas
```

Puntos para señalar en cámara (mapea 1 a 1 con la tabla de arquitectura del análisis de
viabilidad, sección 2.1):

- `frontend`, `backend-core`, `backend-ai`: **2 réplicas cada uno** — son stateless, toda la
  persistencia vive en Postgres/Redis/Chroma, no en el proceso.
- `postgres`, `keycloak-db`: **StatefulSet, 1 réplica** — bases de datos con estado real, cada
  una con su propio volumen persistente (`kubectl get pvc -n prisma-demo`).
- `backend-core-hpa`: ya existe un HorizontalPodAutoscaler apuntando a `backend-core`
  (2→4 réplicas acá, 2→8 en un cluster real) — se ve en la sección 8.

**Qué NO está en esta demo, y por qué** (bueno decirlo explícito en el video, conecta con el
análisis de viabilidad): sin Ollama/LLM (el modelo de ~4.7 GB no se descarga), sin MinIO (la
subida de evidencia no funciona), sin Chroma (el servicio aparte del índice RAG en K8s — ver
`infra/kubernetes/base/chroma.yaml`), sin Ingress/TLS (se entra directo al Service `frontend`).
El RAG queda completamente degradado sin Ollama ni Chroma (backend-ai levanta igual — ver
`apps/backend-ai/app/rag/chroma_client.py`, el cliente HTTP a Chroma es perezoso y `/health` no
depende de él), pero eso no afecta el login, las evaluaciones ni el resto del guion de la demo.
Esta es exactamente la frontera de `docker-compose.mvp.yml` (perfil `mvp`), que tampoco incluye
Chroma como servicio aparte — el overlay `demo` de K8s ahora tiene el mismo alcance que ese MVP.

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
# minikube dashboard   -- abre el Kubernetes Dashboard en el navegador (levanta un proxy local a la UI del cluster)
```

Abre una pestaña del navegador sola. Una vez ahí:

1. Arriba a la izquierda, cambiar el namespace de `default` a **`prisma-demo`**.
2. Ir a **Workloads → Deployments** (o **Pods**) para ver las cards de `frontend`,
   `backend-core`, `backend-ai`, `keycloak` con su cantidad de réplicas listas.

> *Guion sugerido:* "Además de la terminal, vamos a tener abierto el Kubernetes Dashboard —
> la interfaz gráfica oficial del proyecto — para ver gráficamente cada cambio que hagamos:
> pods reiniciándose, el rollout avanzando, las réplicas subiendo. Selecciono el namespace de
> la demo, `prisma-demo`, arriba a la izquierda."

Dejar esta pestaña abierta (o en un segundo monitor/ventana) durante las secciones 6, 7 y 8 —
cada demo en vivo tiene abajo una nota de qué mirar ahí en simultáneo con la terminal.

### Alternativa/complemento: Lens

[Lens](https://k8slens.dev) (o su fork libre **OpenLens**, sin cuenta ni telemetría) es un
"IDE" de escritorio para Kubernetes — más completo que el Dashboard: árbol de recursos por
namespace, gráficos de CPU/memoria por pod en el tiempo (con `metrics-server`, sección 1),
terminal embebida dentro de un pod y seguimiento de logs, todo en una sola ventana. Si el equipo
ya lo usa o prefiere una app de escritorio en vez de una pestaña del navegador para la demo, va
acá en vez de (o además de) la sección anterior.

1. Instalar **Lens Desktop** desde [k8slens.dev](https://k8slens.dev/download) (pide una cuenta
   gratuita para algunas funciones) o **OpenLens** (mismo core, sin cuenta) — buscar el release
   más reciente en su repositorio de GitHub, o vía Chocolatey en Windows:
   ```bash
   choco install openlens
   ```
2. Abrir Lens. Detecta solo los contextos de `~/.kube/config` — el cluster `minikube` debería
   aparecer en la pantalla de inicio ("Catalog"); click para conectar.
3. Arriba, cambiar el namespace de `All namespaces` a **`prisma-demo`** (mismo filtro que en el
   Dashboard).
4. **Workloads → Deployments/Pods** para el mismo tipo de vista que el Dashboard, con el
   agregado del gráfico de CPU/memoria por pod si `metrics-server` está habilitado.

> *Guion sugerido (si se usa en vez del Dashboard):* "Para esta parte vamos a usar Lens, una
> herramienta de escritorio para administrar clusters de Kubernetes — mismo tipo de vista que un
> Dashboard web, pero con más detalle: métricas por pod, logs y hasta una terminal dentro de
> cada contenedor, todo desde acá."

Las mismas notas de "En el Dashboard" en las secciones 6, 7 y 8 aplican igual en Lens — es la
misma información (pods, réplicas, estado del rollout), sólo cambia la ventana donde se mira.

> El equipo también documentó una integración con **Portainer** para administrar el cluster
> (ver [`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)) — es una
> herramienta más completa a nivel equipo, pero requiere desplegar un agente y conectar redes
> Docker a mano; de más para esta demo puntual, donde el Dashboard o Lens ya alcanzan.

---

## 5. Abrir la app y loguearse *(en cámara: ~1 min)*

En una terminal aparte (queda corriendo durante todo el resto de la demo):

```bash
make k8s-demo-open
# kubectl port-forward svc/frontend 8888:80 -n prisma-demo   -- abre un túnel local: localhost:8888 -> puerto 80 del Service frontend dentro del cluster
```

Abrir **http://localhost:8888** en el navegador (8888 y no 8080 a propósito — ver
Troubleshooting, "página en blanco / 404 al abrir localhost"). Login:

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
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w  # -w = "watch": queda mirando y refresca sola cada vez que cambia un pod de backend-core
```

**Terminal B** — un pod de prueba que le pega al Service (no a un pod puntual) cada segundo,
para probar que el Service en sí nunca deja de responder:

```bash
# levanta un pod descartable (--restart=Never) que hace curl al Service backend-core (no a un pod puntual)
# una vez por segundo e imprime sólo el código HTTP devuelto
kubectl run curl-loop -n prisma-demo --image=curlimages/curl:8.10.1 --restart=Never -- \
  sh -c 'while true; do curl -s -o /dev/null -w "%{http_code}\n" http://backend-core:8080/actuator/health/readiness; sleep 1; done'
kubectl logs -f curl-loop -n prisma-demo  # -f = "follow": muestra en vivo lo que va imprimiendo ese loop
```

Debería imprimir `200` una vez por segundo, sin cortes.

**Terminal C** — acá es donde se "rompe" algo en cámara:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core  # lista los pods de backend-core para copiar el nombre de uno
# copiar el nombre de UNO de los dos pods
kubectl delete pod <nombre-del-pod> -n prisma-demo  # lo borra a mano, simulando una caída; el Deployment repone la réplica faltante solo
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
kubectl rollout restart deployment/backend-core -n prisma-demo  # dispara un rolling update (recrea los pods de a uno, respetando maxSurge/maxUnavailable)
kubectl rollout status  deployment/backend-core -n prisma-demo  # se queda esperando y muestra el progreso hasta que el rollout termina
```

> *Guion sugerido:* "Esto simula desplegar una versión nueva del backend en horario laboral,
> sin cortar a nadie que esté usando el sistema. Miren la Terminal B: sigue en `200` todo el
> rollout. Con `docker compose up -d`, ese mismo cambio recrea el contenedor y hay una ventana
> real de caída."

Para ver el detalle del reemplazo pod por pod en tiempo real:

```bash
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w  # -w: muestra en vivo cómo aparece el pod nuevo (Ready) antes de que se borre el viejo
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
kubectl get hpa -n prisma-demo                                            # muestra el HPA y sus límites (2→4 réplicas en esta demo) antes de forzar el escalado a mano
kubectl scale deployment/backend-core -n prisma-demo --replicas=4         # pide al Deployment que pase de 2 a 4 réplicas (lo mismo que haría el HPA solo)
kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w # -w: mirar en vivo cómo aparecen los 2 pods nuevos
```

> *Guion sugerido:* "Esto mismo lo hace Kubernetes solo cuando el HPA que se ve arriba detecta
> CPU o memoria por encima del umbral — acá lo disparo a mano para que se vea en el video, pero
> en un pico de uso real (varias organizaciones subiendo evidencia el mismo día, por ejemplo)
> pasaría sin que nadie lo toque."

Volver a 2 réplicas para cerrar prolijo:

```bash
kubectl scale deployment/backend-core -n prisma-demo --replicas=2  # vuelve a bajar a 2 réplicas, el estado "normal" del deployment
```

**En el Dashboard:** la card de `backend-core` en **Workloads → Deployments** cambia el
contador de réplicas de 2 a 4 y de vuelta a 2 en vivo — buen plano final para esta sección.

---

## 9. Cierre y limpieza

Cerrar el `curl-loop`, el `port-forward` y la pestaña del Dashboard (o desconectar el cluster
en Lens si se usó esa alternativa) — Ctrl+C en las terminales que quedaron abiertas, y:

```bash
kubectl delete pod curl-loop -n prisma-demo --ignore-not-found  # borra el pod de prueba del curl-loop; --ignore-not-found evita error si ya no existe
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
| `make k8s-demo-open` | `kubectl port-forward svc/frontend 8888:80` (dejar corriendo, abrir el navegador) |
| `make k8s-demo-down` | Borra el namespace `prisma-demo` (conserva el cluster minikube) |
| `make k8s-demo-destroy` | `minikube delete` — borra el cluster entero |

Lens/OpenLens no tiene un `make` propio: es una app de escritorio aparte (sección 4) que detecta
el cluster `minikube` solo, vía `~/.kube/config`.

## Troubleshooting

- **La pantalla de login de Keycloak aparece sin ningún estilo (HTML plano, sin CSS/JS)** y la
  consola muestra varios `GET .../auth/resources/.../*.css` o `*.js` con `404 Not Found`. Bug de
  `apps/frontend/nginx.conf`, ya corregido, independiente de K8s/Docker: en nginx, un `location`
  con regex (acá, el de cache de assets estáticos por extensión — `~* \.css$`, `\.js$`, etc.) le
  **gana** a un `location` de prefijo simple (`/auth/`, `/api/`, `/ai/`) sin importar el orden en
  el archivo. Cualquier archivo de Keycloak bajo `/auth/resources/...` que terminara en `.css` o
  `.js` (todo su tema visual) nunca llegaba a proxearse a Keycloak: nginx intentaba servirlo como
  archivo estático propio, no existía en el filesystem del frontend, y devolvía 404. El fix
  agrega el modificador `^~` a los tres `location` de proxy (`^~ /api/`, `^~ /ai/`, `^~ /auth/`),
  que fuerza a nginx a preferirlos sobre cualquier location regex. Si tu imagen de frontend es de
  antes de este fix, reconstruila:
  ```bash
  make k8s-demo-images
  kubectl rollout restart deployment/frontend -n prisma-demo
  ```
- **Página en blanco al abrir la app; en la consola del navegador aparece algo como
  `Not allowed to load local resource: file:///C:/Program%20Files/Git/auth/realms/...` y
  `Timeout when waiting for 3rd party check iframe message`.** Bug clásico de **Git Bash/MSYS2
  en Windows**, no de Kubernetes: cuando `make k8s-demo-images` corre bajo Git Bash (que este
  `Makefile` fuerza como `SHELL` en Windows, ver el comentario al principio del archivo), MSYS2
  reescribe automáticamente cualquier argumento que "parezca" un path Unix absoluto (empieza con
  `/`) a un path de Windows **antes** de que `docker build` lo vea. Los `--build-arg
  VITE_KEYCLOAK_URL=/auth` (y `VITE_API_BASE_URL=/api`, `VITE_AI_API_BASE_URL=/ai/api/v1`) del
  target `k8s-demo-images` terminaban horneados en el bundle de JS como
  `C:/Program Files/Git/auth` en vez de `/auth` -- Keycloak intentaba redirigir el navegador a
  un `file://` local, que el navegador bloquea, y el login se queda esperando para siempre.
  Ya está arreglado en el `Makefile` (exporta `MSYS_NO_PATHCONV=1` antes de los `docker build`,
  que le dice a MSYS2 que NO reescriba paths para ese comando) -- si estás en un checkout viejo
  o ya construiste las imágenes antes de este fix, reconstruilas y reiniciá el frontend:
  ```bash
  make k8s-demo-images
  kubectl rollout restart deployment/frontend -n prisma-demo
  ```
  Para confirmar si una imagen ya construida tiene el bug, sin necesidad de abrir el navegador:
  ```bash
  docker run --rm prisma-frontend:demo sh -c 'grep -l "Program Files" /usr/share/nginx/html/assets/index-*.js' 2>&1
  # cualquier salida acá (no vacío) confirma el bug -- hay que reconstruir la imagen
  ```
- **`backend-core` en `CrashLoopBackOff` con `FATAL: password authentication failed for user
  "prisma"` (o `"keycloak"`) DESPUÉS de que ya había funcionado.** `make k8s-demo-secrets` ya es
  idempotente (no toca el Secret si `prisma-secrets` ya existe en el namespace — ver el
  comentario en el `Makefile`), pero si de un checkout viejo, o borrando el Secret a mano,
  `k8s-demo-up` termina generando passwords nuevas sobre un `postgres-0`/`keycloak-db-0` que ya
  se inicializó con las anteriores, el resultado es exactamente este error: Postgres tiene en
  disco la password vieja, el pod nuevo arranca con la password nueva del Secret actual, no
  matchean. Dos formas de arreglarlo, sin perder los datos:
  ```bash
  # 1) alinear Postgres/keycloak-db a la password ACTUAL del Secret (recomendado, no pierde datos)
  PASS=$(kubectl get secret prisma-secrets -n prisma-demo -o jsonpath='{.data.SPRING_DATASOURCE_PASSWORD}' | base64 -d)
  kubectl exec -n prisma-demo postgres-0 -- env PGPASSWORD="$PASS" psql -U prisma -d prisma -c "ALTER USER prisma WITH PASSWORD '$PASS';"
  KPASS=$(kubectl get secret prisma-secrets -n prisma-demo -o jsonpath='{.data.KEYCLOAK_DB_PASSWORD}' | base64 -d)
  kubectl exec -n prisma-demo keycloak-db-0 -- env PGPASSWORD="$KPASS" psql -U keycloak -d keycloak -c "ALTER USER keycloak WITH PASSWORD '$KPASS';"
  kubectl rollout restart deployment/backend-core -n prisma-demo

  # 2) o, más simple si no importa perder lo que había: borrar todo y arrancar de cero
  kubectl delete namespace prisma-demo
  make k8s-demo-up
  ```
- **`http://localhost:8888` (o `:8080` en una versión vieja de este overlay) da "404 page not
  found" o una página en blanco, aunque `make k8s-demo-open` no muestra ningún error.** El
  `kubectl port-forward` sólo escucha en `127.0.0.1` (IPv4) — si algún otro proceso de la
  máquina (otro `kubectl port-forward`, Docker Desktop/WSL2 reenviando el puerto de OTRO
  proyecto, el propio `docker compose` de PRISMA con `FRONTEND_PORT` en el mismo valor, etc.)
  está escuchando ese mismo puerto en `::1`/`[::]` (IPv6), el navegador puede resolver
  "localhost" a la dirección IPv6 primero y terminar hablando con ESE otro proceso en vez de con
  el port-forward. Diagnóstico rápido — comparar las dos direcciones a mano:
  ```bash
  curl -s -o /dev/null -w "IPv4: %{http_code}\n" http://127.0.0.1:8888/
  curl -s -o /dev/null -w "IPv6: %{http_code}\n" http://[::1]:8888/
  ```
  Si dan códigos distintos, ahí está confirmado el choque. Fixes, de más simple a más definitivo:
  1. Entrar por **`http://127.0.0.1:8888`** en vez de `localhost` (fuerza IPv4, sin ambigüedad).
  2. Ver qué proceso está usando el puerto y cerrarlo si es prescindible — en Windows:
     `Get-NetTCPConnection -LocalPort 8888` (PowerShell) te da el `OwningProcess`, después
     `Get-Process -Id <pid>` para identificarlo.
  3. Cambiar el puerto del `port-forward` por otro libre en tu máquina, editando
     `k8s-demo-open` en el `Makefile` (y `KEYCLOAK_ISSUER_URL`/`KEYCLOAK_TRUSTED_ISSUERS`/
     `CORS_ALLOWED_ORIGINS` en `infra/kubernetes/overlays/demo/configmap-patch.yaml` para que
     coincidan) y reaplicando (`make k8s-demo-up`).
- **El `docker build` de `k8s-demo-images` se queda colgado** (en la terminal sólo se ve el
  warning `Using the docker-env command with the containerd runtime is a highly experimental
  feature...` y no avanza más, y `minikube status` también se cuelga sin responder). Causa: el
  nodo de minikube quedó con **containerd** como runtime interno en vez de docker (pasa si se
  corrió `minikube start` sin `--container-runtime=docker`, ver nota en la sección 1) — `crictl
  ps` dentro del nodo queda deadlocked y `minikube docker-env`/`minikube status` nunca vuelven.
  Docker Desktop en sí sigue sano (`docker version` responde normal); el problema es sólo del
  nodo. Fix: matar el `make`/`minikube` colgado y recrear el cluster con el runtime correcto:
  ```bash
  minikube delete
  minikube start --driver=docker --container-runtime=docker --cpus=4 --memory=6144
  make k8s-demo-images
  make k8s-demo-up   # el namespace prisma-demo anterior se perdió con el delete, hay que reaplicar
  ```
- **`backend-core` en `CrashLoopBackOff`, logs con `FlywayMigrateException` /
  `schema "prisma" does not exist`.** Deuda técnica real que este overlay ya corrige (no hace
  falta nada de tu lado si tu checkout es reciente): `infra/kubernetes/base/postgres.yaml`
  ahora monta `base/postgres-init.sql` (generado por `make sync-k8s-assets` desde
  `scripts/db/init.sql`, el mismo script que docker-compose bind-monta) en
  `/docker-entrypoint-initdb.d/00-init.sql`, que crea los schemas `prisma`/`audit` + extensiones
  **antes** de que Flyway migre. Ese script sólo corre en el **primer** arranque de un volumen
  vacío — si ya tenías un `postgres-0` corriendo de antes del fix, borrar el namespace y
  reaplicar (`kubectl delete namespace prisma-demo && make k8s-demo-up`) es más simple que
  editar el pod a mano. Si preferís no perder los datos, se puede correr el script a mano contra
  el pod vivo:
  ```bash
  kubectl exec -n prisma-demo postgres-0 -- psql -U prisma -d prisma -c \
    "CREATE SCHEMA IF NOT EXISTS prisma; CREATE SCHEMA IF NOT EXISTS audit; \
     CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"; CREATE EXTENSION IF NOT EXISTS \"pgcrypto\"; \
     CREATE EXTENSION IF NOT EXISTS \"pg_trgm\"; \
     GRANT ALL PRIVILEGES ON SCHEMA prisma TO prisma; GRANT ALL PRIVILEGES ON SCHEMA audit TO prisma;"
  kubectl delete pod -n prisma-demo -l app.kubernetes.io/name=backend-core   # reintenta Flyway
  ```
- **`kubectl rollout status deployment/chroma` se queda esperando y `make k8s-demo-up` termina
  en `Error 1` con `0 of 1 updated replicas are available`.** Estás en una copia del overlay
  `demo` de antes de que se sacara Chroma del MVP — actualizá `infra/kubernetes/overlays/demo/`
  a la versión actual (ya no incluye Chroma, ver sección 0 y 3) y volvé a correr
  `make k8s-demo-up`. Si el namespace `prisma-demo` quedó a mitad de camino:
  `kubectl delete namespace prisma-demo` y recién ahí `make k8s-demo-up` de nuevo.
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
