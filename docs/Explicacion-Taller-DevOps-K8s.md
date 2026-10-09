# 📘 Taller DevOps: qué se hizo, cómo y por qué (explicado para todos)

Este documento explica, en lenguaje sencillo, **todo lo que se implementó en la rama
`demo-taller-devops`**: qué herramientas se usaron, cómo funcionan por dentro y por qué se
eligió cada una. También incluye una segunda parte con **alternativas usando solo Docker**
(sin Kubernetes), para quien quiera mostrar ideas parecidas sin instalar un cluster.

No se asume conocimiento previo de Kubernetes o Docker: cada concepto se explica la primera
vez que aparece.

---

## 1. El problema de fondo: ¿por qué no alcanza con "prender los contenedores"?

Hoy PRISMA corre con **Docker Compose**: un archivo (`docker-compose.yml`) que describe qué
contenedores hay que levantar (frontend, backend, base de datos, etc.) y con qué configuración.
Es simple y funciona muy bien para desarrollo. El problema aparece en producción, cuando el
sistema tiene que estar disponible todo el tiempo:

- Si un contenedor se cae (por un error, falta de memoria, etc.), **nadie lo vuelve a levantar
  solo**. Hay que notarlo y hacerlo a mano.
- Si hay que actualizar una versión nueva del backend, `docker compose up` **apaga el
  contenedor viejo y recién después** levanta el nuevo: hay una ventana de tiempo donde el
  servicio no responde (downtime).
- Si de golpe hay más usuarios de lo normal, no hay forma automática de "clonar" el backend
  para repartir la carga entre varias copias.

**Kubernetes (K8s)** es un *orquestador de contenedores*: un software que, en vez de que una
persona ejecute `docker run` a mano, mira todo el tiempo "¿cuántas copias de cada cosa deberían
estar corriendo, y según qué reglas?" y corrige solo cualquier diferencia entre lo que **debería
haber** (lo que vos declaraste) y lo que **realmente hay** en ese momento. Eso es lo que se
armó y se demostró en esta rama: una puesta en escena completa de PRISMA corriendo en
Kubernetes, pensada para grabar un video mostrando esas tres ventajas en vivo.

---

## 2. Qué se implementó, archivo por archivo

### 2.1. Los manifiestos de Kubernetes (`infra/kubernetes/`)

Un **manifiesto** es simplemente un archivo YAML que describe "quiero que exista esto, con
estas características" — Kubernetes lee ese archivo y se encarga de hacerlo realidad. No se
ejecutan comandos imperativos ("creá un contenedor"), se **declara un resultado deseado**.

La carpeta está organizada así:

```
infra/kubernetes/
├── base/            ← la definición "genérica" de cada pieza de PRISMA (frontend, backend-core,
│                       backend-ai, postgres, redis, keycloak, minio, ollama, chroma, ingress...)
└── overlays/
    ├── dev/         ← ambiente real de desarrollo
    ├── staging/     ← ambiente real de staging
    ├── prod/        ← ambiente real de producción
    └── demo/        ← ★ NUEVO: una variante liviana, sólo para grabar la demo
```

**¿Por qué "base" + "overlays" y no un archivo gigante por ambiente?** Esto se llama
**Kustomize** (viene integrado en `kubectl`, no hay que instalar nada aparte). La idea es no
repetir la definición de "cómo es el frontend" tres veces (una por ambiente) corriendo el riesgo
de que se desincronicen. En cambio:

- `base/` tiene la definición común (una sola vez).
- cada `overlay/` aplica **parches** sobre esa base: "en prod, usá 3 réplicas en vez de 1";
  "en demo, sacá Ollama y Chroma porque no se usan en el guion".

El overlay `demo/` que se agregó en esta rama hace, concretamente:

- `delete-resources.yaml`: saca del despliegue a Ollama (el modelo de IA, pesa ~4.7 GB y no
  hace falta para la demo), MinIO (almacenamiento de archivos) y Chroma (la base vectorial del
  buscador RAG), y también el Ingress (la "puerta de entrada" HTTP con dominio propio). Esto
  deja exactamente el mismo alcance que ya existía para Docker Compose en el perfil `mvp`
  (`docker-compose.mvp.yml`): frontend, backend-core, backend-ai, Keycloak (+ su base), Postgres
  y Redis.
- `patches.yaml`: baja cuánta CPU/memoria le pide cada pod (para que entre en una notebook
  común) y fuerza `imagePullPolicy: Never` — es decir, "no intentes bajar la imagen de internet,
  usá la que ya está construida localmente" (más detalle en la sección 2.3).
- `configmap-patch.yaml`: ajusta URLs (Keycloak, CORS) al puerto que usa la demo (`8888`).

### 2.2. El Makefile: automatizar los comandos largos

Se agregaron ~15 "recetas" nuevas al `Makefile` (atajos de comandos), todas con el prefijo
`k8s-demo-*`. Un `Makefile` es simplemente una lista de comandos con nombre: en vez de acordarse
un comando largo de `kubectl`, se escribe `make k8s-demo-up` y el Makefile ejecuta la secuencia
completa. Esto es puramente conveniencia — nada de lo que hace no se podría escribir a mano,
pero reduce errores de tipeo y hace la demo repetible.

Las recetas más importantes:

| Comando | Qué hace, en criollo |
|---|---|
| `make k8s-demo-images` | Construye las 3 imágenes Docker propias (frontend, backend-core, backend-ai) **dentro** del motor de Docker que usa minikube (ver 2.3) |
| `make k8s-demo-secrets` | Crea el "espacio" (`namespace`) de la demo y las contraseñas, generadas al azar, que van a usar los servicios |
| `make k8s-demo-up` | Aplica todos los manifiestos (`kubectl apply -k`) y espera a que todo esté listo |
| `make k8s-demo-status` | Muestra qué hay corriendo |
| `make k8s-demo-dashboard` | Abre una interfaz visual en el navegador |
| `make k8s-demo-open` | Abre un túnel para poder entrar a la app desde `localhost:8888` |
| `make k8s-demo-down` | Borra todo lo de la demo (pero deja el cluster prendido) |
| `make k8s-demo-destroy` | Apaga y borra el cluster entero |

### 2.3. minikube: un Kubernetes de bolsillo

**Kubernetes real** (el que se usa en producción) necesita varias máquinas (nodos) coordinadas.
Para desarrollo y demos, eso es demasiado: **minikube** es una herramienta que crea un cluster
de Kubernetes **de un solo nodo**, corriendo él mismo adentro de un contenedor Docker en tu
propia máquina. Es decir: usás Docker para simular una máquina, y adentro de esa "máquina" se
instala Kubernetes completo. Sirve para aprender y probar exactamente las mismas herramientas
(`kubectl`, manifiestos, etc.) que se usarían en un cluster real de varios nodos, sin necesitar
infraestructura real.

Un detalle importante que se resolvió en esta rama: minikube tiene su **propio** daemon Docker,
separado del Docker de tu PC. Si construís la imagen con tu Docker normal, minikube no la va a
encontrar. La solución (`eval $(minikube docker-env)`) hace que, por un momento, los comandos
`docker build` apunten al Docker *de adentro* de minikube en vez del tuyo. Por eso las 3
imágenes de PRISMA se "ven" dentro del cluster sin tener que subirlas a ningún repositorio
externo (GHCR, Docker Hub, etc.) — atajo válido solo para demos locales, no para producción.

### 2.4. kubectl: el control remoto de Kubernetes

`kubectl` es el programa de línea de comandos para hablarle al cluster. Todo lo que se hizo en
la demo pasa por él, por ejemplo:

- `kubectl apply -k <carpeta>`: "tomá estos manifiestos y hacé que el cluster quede así".
- `kubectl get pods`: "mostrame qué hay corriendo". Un **pod** es la unidad mínima que
  Kubernetes programa — en este proyecto, cada pod corre un contenedor (el mismo tipo de
  contenedor que ya se usaba con Docker Compose).
- `kubectl delete pod <nombre>`: borra un pod a mano — se usa en la demo para *simular* una
  caída y mostrar que Kubernetes lo repone solo.
- `kubectl rollout restart deployment/<nombre>`: dispara una actualización controlada ("rolling
  update", sección 3).
- `kubectl scale deployment/<nombre> --replicas=4`: pide más copias de un servicio.
- `kubectl port-forward`: abre un túnel temporal entre tu máquina y un servicio de adentro del
  cluster, para poder abrirlo en el navegador sin configurar nada de redes.

### 2.5. Kubernetes Dashboard y Lens: ver todo esto, pero con dibujitos

Todo lo anterior se puede hacer por terminal, pero es mucho más claro mostrarlo en video con una
interfaz visual:

- **Kubernetes Dashboard**: viene incluido con minikube (`make k8s-demo-dashboard` lo abre en el
  navegador). Muestra tarjetas con cada pieza desplegada y se actualiza en vivo — perfecto para
  ver, por ejemplo, un pod pasando de "Terminating" a "ContainerCreating" a "Running" en tiempo
  real mientras se graba.
- **Lens / OpenLens**: una alternativa de aplicación de escritorio (no hace falta instalar nada
  dentro del cluster), más completa — además de las tarjetas, muestra gráficos de uso de
  CPU/memoria por pod, y permite abrir una terminal *dentro* de un contenedor directamente desde
  la interfaz.

Ninguna de las dos cambia lo que hace Kubernetes: son solo ventanas para **mirar** el mismo
estado que `kubectl get pods` ya muestra por texto.

---

## 3. Las tres demostraciones concretas (y por qué importan)

Todo el trabajo de las secciones 1 y 2 existe para poder mostrar, en vivo, estas tres cosas que
Kubernetes da "gratis" y que Docker Compose no:

### 3.1. Auto-recuperación (self-healing)

**Qué se hace:** se borra un pod a mano (`kubectl delete pod ...`), simulando que se cayó por un
error.
**Qué pasa:** Kubernetes nota que "debería haber 2 copias de backend-core" y en ese momento hay
solo 1 — y arranca una nueva automáticamente, sin que nadie lo haga a mano.
**Por qué importa:** mientras tanto, un script que le pega al servicio una vez por segundo
(`curl-loop`) nunca deja de recibir respuesta `200 OK`, porque la otra copia seguía arriba todo
el tiempo. Con Docker Compose, si ese único contenedor se cae, el servicio queda caído hasta que
alguien lo note y lo levante a mano.

### 3.2. Actualización sin caída de servicio (rolling update)

**Qué se hace:** se dispara `kubectl rollout restart deployment/backend-core` (simula "desplegar
una versión nueva").
**Qué pasa:** Kubernetes levanta **primero** el pod nuevo y espera a que esté sano, y recién
*después* apaga uno viejo — nunca al revés. Esto está configurado con `maxSurge: 1,
maxUnavailable: 0` en el manifiesto.
**Por qué importa:** durante todo el proceso, el servicio sigue respondiendo. Con
`docker compose up -d`, el contenedor viejo se apaga y el nuevo se prende después — hay una
ventana real, aunque corta, de caída.

### 3.3. Escalado horizontal (scaling)

**Qué se hace:** `kubectl scale deployment/backend-core --replicas=4` (pasar de 2 a 4 copias).
**Qué pasa:** aparecen 2 pods nuevos del mismo servicio, y el tráfico se reparte automáticamente
entre las 4 copias.
**Por qué importa:** esto es exactamente lo que hace solo, sin intervención humana, un
**HorizontalPodAutoscaler (HPA)** — un componente que ya está configurado en el proyecto y que
mira el uso de CPU/memoria en tiempo real: si sube mucho (por ejemplo, varias organizaciones
subiendo evidencia el mismo día), agrega copias solo, y cuando baja la carga, las quita.

---

## 4. Alternativas usando SOLO Docker (sin Kubernetes)

Instalar y aprender Kubernetes tiene una curva de entrada real. Si el objetivo es **mostrar las
mismas ideas** (auto-recuperación, actualización sin caída, escalado) sin meterse con
minikube/kubectl, hay dos caminos con Docker solo. Ambos usan herramientas que ya están
instaladas junto con Docker Desktop — no hay que agregar nada nuevo.

### 4.1. Opción A — Docker Compose "mejorado" (la más simple, ya la tenemos)

Docker Compose (lo que PRISMA ya usa) tiene algunas funciones que **imitan, de forma limitada**,
lo que hace Kubernetes. No son automáticas como el HPA, pero sirven para una demo didáctica.

**Herramienta:** `docker compose`, el mismo que ya usa el proyecto. No hay que instalar nada.

**1) Auto-recuperación parcial con `restart: unless-stopped`**

En el `docker-compose.yml` cada servicio ya tiene (o se le puede agregar) una política de
reinicio:

```yaml
services:
  backend-core:
    restart: unless-stopped   # si el contenedor se cae, Docker lo vuelve a prender solo
```

**Cómo demostrarlo:** con la stack levantada (`make up` o `make mvp-up`), matar el contenedor a
mano y ver que vuelve:

```bash
docker kill prisma-backend-core-1      # simula una caída
docker ps | grep backend-core          # a los pocos segundos, Docker ya lo volvió a arrancar
```

**La diferencia clave con Kubernetes:** acá hay **una sola copia**. Mientras ese contenedor está
abajo reiniciándose (unos segundos), el servicio **sí** está caído — no hay una segunda copia
absorbiendo el tráfico como en la demo de Kubernetes. Es un buen punto para explicar en vivo:
"esto recupera solo, pero con un corte real; en Kubernetes con 2+ réplicas, ese corte no existe".

**2) "Rolling update" manual con `--scale` y dos versiones**

Docker Compose no tiene un verdadero rolling update automático, pero se puede simular a mano
levantando la versión nueva **antes** de bajar la vieja:

```bash
docker compose up -d --no-deps --scale backend-core=2 backend-core   # levanta una 2da copia
# ... esperar a que la copia nueva esté sana (docker compose ps) ...
docker compose up -d --no-deps --scale backend-core=1 backend-core   # vuelve a 1, Compose apaga la vieja
```

Esto requiere que haya algo repartiendo tráfico entre las dos copias (por ejemplo, el `nginx` que
ya usa el proyecto, apuntando por nombre de servicio en vez de IP fija) — si no, el navegador
sigue hablando siempre con la misma copia y no se nota nada.

**3) Escalado manual con `docker compose scale`**

```bash
docker compose up -d --scale backend-core=4   # pasa de 1 a 4 copias del mismo servicio
docker compose ps                              # confirma las 4 copias corriendo
```

Igual que en Kubernetes, pero **sin automatismo**: nadie mira el uso de CPU para decidir subir o
bajar copias solo — hay que ejecutar el comando a mano cada vez. Es la diferencia central que
conviene remarcar al público: Compose puede "tener varias copias", pero no "decidir solo cuántas
necesita".

### 4.2. Opción B — Docker Swarm (el "Kubernetes chiquito" de Docker)

Si se quiere mostrar algo **más parecido** a Kubernetes (incluyendo auto-recuperación real con
varias réplicas y rolling updates automáticos) pero sin salir de Docker, **Docker Swarm** es la
respuesta: es el orquestador que Docker trae **integrado**, sin instalar nada aparte de Docker
Desktop/Engine. Es mucho más simple que Kubernetes (menos conceptos, menos archivos) pero ofrece
las mismas tres ideas centrales de esta demo.

**Cómo "prender" Swarm** (una sola vez, es un interruptor del propio Docker que ya tenés):

```bash
docker swarm init     # convierte tu Docker local en un cluster Swarm de 1 nodo
docker info | grep Swarm   # confirma: "Swarm: active"
```

**Desplegar PRISMA en Swarm** (reutiliza el mismo `docker-compose.yml` que ya existe, con
mínimos ajustes — Swarm entiende el formato de Compose casi igual):

```bash
docker stack deploy -c docker-compose.yml prisma     # "stack" = el equivalente de Compose en Swarm
docker stack services prisma                          # lista servicios y cuántas réplicas tiene cada uno
```

**Demo 1 — Auto-recuperación real, con varias réplicas:**

```bash
docker service scale prisma_backend-core=2    # 2 copias del backend
docker service ps prisma_backend-core         # ver en qué contenedor está cada réplica
docker rm -f <id-de-un-contenedor>            # matar una réplica a mano (simula una caída)
docker service ps prisma_backend-core         # Swarm ya la repuso sola, igual que Kubernetes
```

**Demo 2 — Actualización sin caída (rolling update automático):**

```bash
docker service update --image prisma-backend-core:v2 --update-parallelism 1 \
  --update-delay 10s prisma_backend-core
# Swarm reemplaza las réplicas de a una, esperando entre cada una -- mismo efecto
# que vimos con Kubernetes y maxSurge/maxUnavailable
docker service ps prisma_backend-core   # ver el reemplazo en progreso, réplica por réplica
```

**Demo 3 — Escalado horizontal (manual; Swarm no trae autoscaler propio):**

```bash
docker service scale prisma_backend-core=4   # sube de 2 a 4 réplicas
docker service scale prisma_backend-core=2   # vuelve a 2
```

**Diferencia honesta con Kubernetes:** Swarm no trae un HPA (el "escalar solo según CPU/memoria"
de la sección 3.3) de fábrica — ese paso siempre es manual, a diferencia de la demo 3 de
Kubernetes donde existe el componente que lo automatiza. Para todo lo demás (réplicas, auto
recuperación, rolling updates), Swarm se comporta de forma muy parecida a Kubernetes, con muchas
menos piezas que aprender.

### 4.3. ¿Cuál conviene mostrar?

| | Docker Compose "a mano" (4.1) | Docker Swarm (4.2) | Kubernetes/minikube (lo ya implementado) |
|---|---|---|---|
| Instalación extra | Ninguna | Ninguna (ya viene con Docker) | minikube + kubectl |
| Auto-recuperación | Solo si hay 1 copia (con corte) | Real, con varias réplicas | Real, con varias réplicas |
| Rolling update | Manual, a pulmón | Automático (1 comando) | Automático (1 comando) |
| Escalado | Manual | Manual | Automático con HPA |
| Qué tan parecido a producción real | Bajo | Medio | Alto (es lo que se usa en la industria) |

Para una primera demo rápida y sin instalar nada: **Opción A**. Para algo más convincente sin
salir de Docker: **Opción B (Swarm)**. Para mostrar exactamente cómo se vería en producción real
(y lo que ya está grabado/documentado en este repo): **Kubernetes con minikube**, tal como se
implementó en esta rama (ver [`docs/Demo-Kubernetes-MVP.md`](Demo-Kubernetes-MVP.md) para el
guion completo paso a paso).

---

## 5. Comandos explicados uno por uno

Esta sección es un glosario de **todos los comandos** mencionados arriba, explicando qué hace
cada palabra/flag. La idea es que se pueda leer cualquier comando de este documento y entender
qué significa cada parte, sin tener que adivinar.

### 5.1. Comandos de `minikube`

| Comando | Explicación parte por parte |
|---|---|
| `minikube start --driver=docker --container-runtime=docker --cpus=4 --memory=6144` | `start` crea (o reanuda) el cluster. `--driver=docker` le dice que el "nodo" de Kubernetes sea un contenedor Docker en tu máquina (en vez de, por ejemplo, una máquina virtual completa). `--container-runtime=docker` fija qué motor usa **adentro** ese nodo para correr los pods — importante dejarlo explícito, porque si minikube elige `containerd` en su lugar, el paso de construir imágenes (`k8s-demo-images`) se cuelga. `--cpus=4 --memory=6144` reserva 4 CPUs virtuales y 6144 MB (6 GiB) de RAM para ese nodo. |
| `minikube status` | Muestra si el cluster está `Running` (el nodo, el `kubelet` — el agente que administra pods en el nodo —, y el `apiserver` — la puerta de entrada que recibe todos los comandos de `kubectl`). |
| `minikube addons enable metrics-server` | Instala el **Metrics Server**, un componente opcional que mide cuánta CPU/memoria usa cada pod en tiempo real. Sin él, `kubectl top` no muestra nada y el HPA (autoescalado) no tiene datos para decidir. |
| `minikube docker-env` / `eval $(minikube docker-env --shell bash)` | `minikube docker-env` imprime variables de entorno que apuntan al daemon Docker **de adentro** de minikube (no el de tu PC). `eval $(...)` ejecuta esas líneas en la terminal actual, así el próximo `docker build` construye la imagen directamente donde minikube la puede usar, sin tener que subirla a ningún lado. |
| `minikube dashboard` | Levanta un proxy local y abre en el navegador la interfaz web oficial de Kubernetes (ver sección 2.5). |
| `minikube delete` | Apaga y **borra completamente** el cluster (el contenedor que hacía de nodo, sus discos, todo). Es destructivo: después hay que `minikube start` de cero. |

### 5.2. Comandos de `kubectl`

`kubectl` sigue casi siempre el patrón: `kubectl <verbo> <tipo-de-recurso> <nombre> [flags]`.

| Comando | Explicación parte por parte |
|---|---|
| `kubectl get nodes` | `get` = "mostrame". `nodes` = las máquinas (o, en minikube, la única máquina simulada) que forman el cluster. Confirma que el nodo está `Ready`. |
| `kubectl apply -k infra/kubernetes/overlays/demo` | `apply` = "hacé que el cluster coincida con esto" (crea lo que falta, actualiza lo que cambió, no toca lo que ya está igual). `-k <carpeta>` le dice que esa carpeta es un proyecto de **Kustomize** (busca ahí un archivo `kustomization.yaml`) y no un YAML suelto. |
| `kubectl rollout status statefulset/postgres -n prisma-demo --timeout=180s` | `rollout status` se queda esperando y mostrando el progreso hasta que ese recurso termina de desplegarse (o falla). `statefulset/postgres` = el tipo de recurso (`statefulset`, para cosas con estado como una base de datos) y su nombre (`postgres`). `-n prisma-demo` = "dentro del namespace (espacio de nombres, una forma de agrupar recursos) llamado `prisma-demo`". `--timeout=180s` = se rinde y devuelve error si pasan 180 segundos sin terminar. |
| `kubectl get pods,svc,hpa -n prisma-demo -o wide` | Pide varios tipos de recursos a la vez separados por coma: `pods` (contenedores corriendo), `svc` (`Service`, la "dirección fija" para hablarle a un grupo de pods), `hpa` (`HorizontalPodAutoscaler`, el autoescalador). `-o wide` = formato de salida con columnas extra (IP, nodo, etc.) en vez del resumen mínimo. |
| `kubectl get pods -n prisma-demo -l app.kubernetes.io/name=backend-core -w` | `-l <etiqueta>=<valor>` filtra: solo los pods que tengan esa etiqueta (así no se mezclan los pods de otros servicios). `-w` = "watch": no imprime una vez y termina, se queda mirando y agrega una línea nueva cada vez que algo cambia — útil para ver en vivo un pod cayendo y otro apareciendo. |
| `kubectl run curl-loop -n prisma-demo --image=curlimages/curl:8.10.1 --restart=Never -- sh -c '...'` | `run` crea un pod "a mano" (sin pasar por un manifiesto YAML), útil para pruebas rápidas y descartables. `--image=...` = qué imagen de contenedor usar (acá, una imagen chiquita que solo trae `curl`). `--restart=Never` = si termina o falla, que no lo reinicie (es un pod de un solo uso, no un servicio permanente). Todo lo que sigue después de `--` es el comando que corre *adentro* de ese pod. |
| `kubectl logs -f curl-loop -n prisma-demo` | Muestra la salida (stdout) de un pod. `-f` = "follow": sigue mostrando líneas nuevas en vivo, en vez de mostrar solo lo que ya pasó y cortar. |
| `kubectl delete pod <nombre> -n prisma-demo` | Borra ese pod puntual. Si ese pod pertenecía a un `Deployment` (como `backend-core`), Kubernetes nota que falta una réplica y crea una nueva solo — **eso** es lo que se está demostrando. |
| `kubectl delete pod curl-loop -n prisma-demo --ignore-not-found` | Igual que el anterior, pero `--ignore-not-found` evita que el comando muestre un error si el pod ya no existía (por ejemplo, si ya se había borrado antes). |
| `kubectl rollout restart deployment/backend-core -n prisma-demo` | Le pide al `Deployment` (la "receta" que dice cuántas copias de un servicio debe haber y cómo) que reemplace todos sus pods por copias nuevas, **respetando** las reglas de actualización que ese Deployment ya tiene configuradas (en este caso, primero sube el nuevo, después baja el viejo — nunca al revés). |
| `kubectl get deployments,statefulsets -n prisma-demo` | Muestra, para cada `Deployment`/`StatefulSet`, cuántas réplicas **deberían** existir (`DESIRED`) contra cuántas están realmente listas (`READY`) — la comparación que Kubernetes usa todo el tiempo para decidir si tiene que actuar. |
| `kubectl scale deployment/backend-core -n prisma-demo --replicas=4` | Cambia a mano cuántas copias (réplicas) quiere ese Deployment — de las 2 que había, pide 4. Kubernetes crea las 2 que faltan. |
| `kubectl get hpa -n prisma-demo` | Muestra el/los `HorizontalPodAutoscaler` configurados: a qué Deployment vigilan, qué umbral de CPU/memoria usan como disparador, y los límites mínimo/máximo de réplicas que tienen permitido usar. |
| `kubectl port-forward svc/frontend 8888:80 -n prisma-demo` | Abre un túnel temporal: todo lo que llegue a `localhost:8888` en tu máquina se reenvía al puerto `80` del `Service` llamado `frontend`, **dentro** del cluster. Mientras el comando sigue corriendo (ocupa la terminal), el túnel sigue abierto. |
| `kubectl delete namespace prisma-demo --ignore-not-found` | Borra **todo** lo que vive dentro de ese namespace (pods, servicios, volúmenes de datos, etc. — equivalente a "borrar la carpeta completa de la demo"), sin tocar el resto del cluster ni otros namespaces. |
| `kubectl describe pod ... ` / `kubectl logs ...` (sección de Troubleshooting) | `describe` muestra el detalle completo de un recurso, incluyendo los últimos eventos (por qué no arrancó, qué error de imagen tuvo, etc.) — el primer comando a correr cuando algo no queda `Running`. `logs` muestra lo que ese contenedor imprimió por su salida estándar. |

### 5.3. Comandos de `docker` (dentro de la demo de Kubernetes)

| Comando | Explicación parte por parte |
|---|---|
| `docker build -f infra/docker/backend-core.Dockerfile --target runtime -t prisma-backend-core:demo apps/backend-core` | `-f <archivo>` indica qué `Dockerfile` (receta de cómo armar la imagen) usar, en vez del default. `--target runtime` = ese Dockerfile define varias "etapas" (por ejemplo, una para compilar y otra más liviana solo para correr) y esta construye únicamente la etapa llamada `runtime`. `-t prisma-backend-core:demo` = el nombre (`tag`) que le va a quedar a la imagen construida: `prisma-backend-core`, versión `demo`. El último argumento (`apps/backend-core`) es el **contexto de build**: la carpeta cuyos archivos puede usar el Dockerfile. |
| `--build-arg VITE_API_BASE_URL=/api` (y similares) | Pasa una variable al momento de construir la imagen del frontend — en este caso, le dice a la aplicación con qué URL hablarle al backend una vez compilada. |

### 5.4. Comandos de `make`

Todos siguen el patrón `make <nombre-de-receta>`. `make` simplemente busca ese nombre en el
`Makefile` y ejecuta, en orden, los comandos de shell que esa receta tiene escritos — ya
explicados individualmente en la sección 2.2. Algunas recetas dependen de otras (por ejemplo,
`k8s-demo-up` corre primero `sync-k8s-assets` y `k8s-demo-secrets` antes de su propio comando) —
eso está declarado en el Makefile como `k8s-demo-up: sync-k8s-assets k8s-demo-secrets`, y `make`
se encarga de respetar ese orden solo.

### 5.5. Comandos de la Opción A — Docker Compose a mano (sección 4.1)

| Comando | Explicación parte por parte |
|---|---|
| `docker kill prisma-backend-core-1` | Manda una señal de apagado forzado a ese contenedor puntual (simula una caída repentina, como un crash). El nombre completo incluye el proyecto (`prisma`), el servicio (`backend-core`) y el número de copia (`1`). |
| `docker ps \| grep backend-core` | `docker ps` lista los contenedores corriendo; `grep backend-core` filtra solo las líneas que mencionan ese nombre (el `\|` conecta ambos comandos: la salida del primero es la entrada del segundo). |
| `docker compose up -d --no-deps --scale backend-core=2 backend-core` | `-d` = en segundo plano ("detached", no ocupa la terminal). `--no-deps` = no reinicies también los servicios de los que depende este (para no tocar la base de datos, por ejemplo). `--scale backend-core=2` = quiero que terminen existiendo 2 copias de ese servicio. El último `backend-core` repite el nombre del servicio a aplicar. |
| `docker compose up -d --scale backend-core=4` | Igual que arriba pero sin `--no-deps`: pide 4 copias del servicio `backend-core` (levantando también sus dependencias si no estaban corriendo). |
| `docker compose ps` | Lista los contenedores que pertenecen a este proyecto de Compose, con su estado. |

### 5.6. Comandos de la Opción B — Docker Swarm (sección 4.2)

| Comando | Explicación parte por parte |
|---|---|
| `docker swarm init` | Convierte tu Docker local (un solo motor) en el primer nodo de un cluster Swarm. A partir de este comando, tu Docker entiende comandos nuevos (`docker service`, `docker stack`). |
| `docker info \| grep Swarm` | Muestra información general de Docker; se filtra la línea que dice si el modo Swarm está `active` o `inactive`. |
| `docker stack deploy -c docker-compose.yml prisma` | `stack deploy` = el equivalente de `docker compose up`, pero para Swarm. `-c docker-compose.yml` indica qué archivo de Compose usar como receta. `prisma` es el nombre que se le da a este stack (agrupa todos los servicios bajo ese nombre, ej. `prisma_backend-core`). |
| `docker stack services prisma` | Lista los servicios que pertenecen al stack `prisma`, con cuántas réplicas tiene cada uno. |
| `docker service scale prisma_backend-core=2` | Pide que el servicio `prisma_backend-core` (dentro del stack `prisma`) tenga 2 réplicas corriendo. |
| `docker service ps prisma_backend-core` | Muestra cada réplica de ese servicio: en qué contenedor vive, en qué nodo, y su estado (incluye el historial de réplicas reemplazadas, por ejemplo durante un rolling update). |
| `docker rm -f <id-de-un-contenedor>` | Borra ese contenedor puntual a la fuerza (`-f`), aunque esté corriendo — simula una caída repentina de una réplica. |
| `docker service update --image prisma-backend-core:v2 --update-parallelism 1 --update-delay 10s prisma_backend-core` | `update` cambia la configuración de un servicio ya desplegado. `--image prisma-backend-core:v2` = la nueva versión de imagen a usar. `--update-parallelism 1` = reemplazar las réplicas de una en una (no todas juntas). `--update-delay 10s` = esperar 10 segundos entre cada reemplazo, para darle tiempo a que la réplica nueva esté sana antes de tocar la siguiente. |

---

## 6. Resumen de archivos nuevos/modificados en esta rama

- `infra/kubernetes/overlays/demo/` — overlay nuevo con la variante liviana para la demo
  (`kustomization.yaml`, `delete-resources.yaml`, `patches.yaml`, `configmap-patch.yaml`).
- `infra/kubernetes/base/postgres.yaml` — ajuste para inicializar los esquemas de la base antes
  de que corran las migraciones (Flyway).
- `Makefile` — ~15 comandos nuevos `k8s-demo-*` para automatizar todo el flujo de la demo.
- `docs/Demo-Kubernetes-MVP.md` — guion completo, paso a paso, para grabar el video (incluye
  troubleshooting real de todos los problemas que aparecieron al armar esto).
- `docs/Demo-Kubernetes-MVP-Slides.md` y `docs/Demo-Kubernetes-MVP-Prompt.md` — contenido para
  las diapositivas que acompañan la grabación.
- `docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md` — el análisis previo que justifica por qué
  vale la pena este trabajo (de dónde sale todo lo de la sección 1 de este documento).
- `apps/frontend/nginx.conf` — corrección de un bug real de proxy (detallado en el
  Troubleshooting de `Demo-Kubernetes-MVP.md`) que rompía el login de Keycloak.
