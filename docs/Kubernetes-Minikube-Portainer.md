# ☸️ Kubernetes local — Minikube + Portainer (gestión del cluster)

Guía para levantar un cluster Kubernetes local con **Minikube** y gestionarlo desde la instancia **Portainer** de PRISMA. El objetivo es contar con un entorno K8s local para desplegar y probar la app antes de ir a entornos cloud/DEV.

> Alcance: la instancia `prisma-portainer` (contenedor Docker) continúa gestionando Docker **y además** un endpoint Kubernetes apuntando al cluster minikube.

---

## 📦 1. Instalación de Minikube

En Windows se instala con `winget` (o `choco install minikube`):

```bash
winget install -e --id Kubernetes.minikube --accept-source-agreements --accept-package-agreements --disable-interactivity
```

Verificar:

```bash
minikube version
# minikube version: v1.38.1
```

> Nota: tras instalar por primera vez, refrescar el `PATH` (nueva sesión de terminal) antes de usar `minikube`.

---

## 🚀 2. Iniciar el cluster

Para este repo se usa el **driver `docker`** (aprovecha Docker Desktop ya existente; no requiere Hyper-V/WSL2 específico).

```bash
minikube start --driver=docker --cpus=4 --memory=8192
```

Comprobar el estado:

```bash
minikube status
kubectl get nodes
# NAME       STATUS   ROLES           AGE   VERSION
# minikube   Ready    control-plane   12s   v1.35.1
```

El nodo minikube corre dentro de un contenedor Docker sobre una red dedicada `minikube` (IP típica `192.168.49.2`):

```bash
minikube ip
# 192.168.49.2
```

---

## 🧱 3. Desplegar PRISMA con redundancia (≥2 réplicas)

Con el cluster arriba, el deploy de la app en sí usa los manifiestos Kustomize de
[`infra/kubernetes/`](../infra/kubernetes/README.md) (ver ese README para el detalle de Secret +
`make sync-k8s-assets` que hacen falta antes del primer `apply`). En minikube se usa el overlay
`dev`:

```bash
make sync-k8s-assets
kubectl create secret generic prisma-secrets -n prisma-dev --from-literal=... # ver infra/kubernetes/README.md
kubectl apply -k infra/kubernetes/overlays/dev
kubectl get deployments -n prisma-dev
```

### Qué tiene redundancia y qué no, y por qué

| Componente | Réplicas | Motivo |
|---|---|---|
| `frontend` | **2** | Stateless (nginx sirviendo estáticos) -- sin ningún costo extra de coordinación. |
| `backend-core` | **2** (dev/staging) / **4** (prod) | Stateless: toda la persistencia vive en Postgres/Redis/MinIO, no en el proceso. |
| `backend-ai` | **2** | Stateless *desde este cambio*: el índice RAG se movió a un servicio `chroma` aparte (ver abajo) -- antes tenía el índice ChromaDB embebido en un `PersistentClient` local sobre un PVC `ReadWriteOnce`, lo que en la práctica impedía correr más de una réplica (dos pods no pueden montar el mismo RWO de forma confiable, y dos procesos escribiendo el mismo SQLite local podían corromper el índice). |
| `chroma` | 1 (a propósito) | Es el componente con estado real ahora: el índice vectorial en sí. Escalarlo necesitaría el modo *distribuido* de Chroma (particionado + réplicas por shard), fuera de alcance acá. `backend-ai` le pega por HTTP (`CHROMA_SERVER_HOST`), así que sus propias réplicas no tienen estado propio. |
| `postgres`, `keycloak-db` | 1 (a propósito) | Bases de datos relacionales de un único nodo. Redundancia real requeriría replicación streaming + failover (p. ej. Patroni) o un servicio gestionado -- no "poner `replicas: 2`" sobre el mismo PVC, que ni siquiera podría programarse (RWO) y, si pudiera, tendría dos Postgres divergiendo sobre el mismo directorio de datos. |
| `redis` | 1 (a propósito) | Cache; redundancia real sería Sentinel o Cluster mode (varios nodos coordinándose), no múltiples réplicas independientes sin estado compartido. |
| `minio` | 1 (a propósito) | Object storage de un solo nodo. El modo distribuido real de MinIO necesita 4+ nodos con discos propios, no 2 réplicas sobre el mismo PVC. |
| `ollama` | 1 (a propósito) | Sirve el modelo LLM/embeddings vía HTTP; ya es el cuello de botella de recursos del cluster (RAM/CPU para inferencia) antes de pensarlo como candidato a escalar. |
| `keycloak` | 1 (a propósito) | Keycloak sí soporta clustering real (JGroups + `KC_CACHE_STACK=kubernetes`), pero no está configurado en este manifiesto -- llevarlo a 2 réplicas sin eso puede dejar sesiones/tokens inconsistentes entre pods. Documentado como trabajo futuro, no activado a ciegas. |

En resumen: **todo lo que puede ser stateless en este stack ya corre con 2+ réplicas**
(`frontend`, `backend-core`, `backend-ai`). Lo que sigue en 1 réplica es así por diseño -- son
componentes con estado real donde "más réplicas" sin el mecanismo de clustering/replicación
correspondiente no da redundancia, da riesgo de corrupción o inconsistencia. Ver también
[`docs/HistoriasDeUsuario.md`](HistoriasDeUsuario.md#15-infraestructura-y-despliegue)
(HU-INFRA-02).

### Verificar que las réplicas realmente están repartidas

```bash
kubectl get pods -n prisma-dev -o wide -l app.kubernetes.io/name=backend-ai
# NAME                          READY   STATUS    RESTARTS   AGE   IP            NODE
# backend-ai-xxxxxxxxxx-aaaaa   1/1     Running   0          1m    10.244.0.12   minikube
# backend-ai-xxxxxxxxxx-bbbbb   1/1     Running   0          1m    10.244.0.13   minikube

kubectl delete pod -n prisma-dev -l app.kubernetes.io/name=backend-ai --field-selector status.phase=Running --dry-run=client
# (en un ensayo real: borrar UNO de los dos pods y confirmar que /ai/health sigue respondiendo
# durante el restart, vía el Service -- eso es lo que la redundancia compra)
```

> minikube es de un solo nodo: los pods de una misma réplica pueden terminar todos en el mismo
> nodo físico (no hay otro adonde ir). Eso alcanza para probar que el Deployment/Service
> balancea entre pods y que un restart de uno no tumba el servicio, pero **no** valida tolerancia
> a la caída de un nodo entero -- eso sólo se prueba en un cluster multi-nodo real (staging/prod).

---

## 🔗 4. Conectar Portainer a Kubernetes (Portainer Agent)

Portainer gestiona clusters Kubernetes mediante un **agente** desplegado dentro del cluster. Los pasos siguientes despliegan ese agente y lo exponen vía `NodePort` para que la instancia de Portainer (contenedor Docker) pueda alcanzarlo.

### 4.1 Desplegar el agente

El agente se despliega en el namespace `portainer` con permisos `cluster-admin`, un `Deployment` y dos `Service` (NodePort + headless para HA).

Esquema mínimo del manifiesto (`portainer-agent.yaml`):

```yaml
# 1) ServiceAccount + ClusterRoleBinding (cluster-admin)
# 2) Deployment portainer/agent:<versión>
# 3) Service NodePort 30777 -> 9001  (acceso externo del agente)
# 4) Service headless s-portainer-agent-headless (modo HA del agente)
```

Puntos clave del manifiesto:

- **Imagen** del agente debe **coincidir** con la versión de Portainer server (aquí `2.45.0`):

  ```yaml
  image: portainer/agent:2.45.0
  ```

- El pod necesita la IP del pod vía Downward API y el runtime completo de Kubernetes (bind `0.0.0.0`):

  ```yaml
  env:
    - name: KUBERNETES_POD_IP
      valueFrom:
        fieldRef:
          fieldPath: status.podIP
  ports:
    - containerPort: 9001
  ```

- El agente usa **TLS por defecto** (`use_tls=true`); no hay que exponerlo por HTTP simple.

Aplicar:

```bash
kubectl create namespace portainer
kubectl apply -f portainer-agent.yaml
kubectl get pods -n portainer -o wide
# portainer-agent-xxxxx   1/1   Running
```

> Si el agente falla con `unable to retrieve a list of IP associated to the host`, falta el **Service headless** `s-portainer-agent-headless` (el agente necesita enumerar la IP de cada pod en modo HA).

### 4.2 Permitir que el contenedor Portainer alcance el agente

La stack corre en la red Docker `prisma-net`, mientras que minikube usa su propia red `minikube`. Para que `prisma-portainer` pueda alcanzar el NodePort del agente, se conecta a la red de minikube:

```bash
docker network connect minikube prisma-portainer
```

Verificar que la red queda vinculada:

```bash
docker inspect prisma-portainer --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
# minikube prisma-net
```

Probar conectividad (desde un contenedor en la red `minikube`):

```bash
docker run --rm --network minikube alpine:3.19 \
  wget -q --no-check-certificate --spider -T 8 https://192.168.49.2:30777
```

> Un `HTTP 403 Forbidden` es esperado: confirma que el agente responde por TLS (rechaza peticiones sin token).

---

## 🖥️ 5. Registrar el endpoint en Portainer (paso manual)

1. Abrí **http://localhost:9000** y logueate.
2. **Environments → Add environment**.
3. Tipo **Kubernetes** → conector **Agent**.
4. **URL del endpoint:** `https://192.168.49.2:30777`
5. **TLS:** marcar `Skip verification` (certificado self-signed del agente).
6. Nombrá el entorno (ej. `minikube`) → **Connect**.

A partir de ahí Portainer muestra el cluster (nodos, namespaces, deployments, etc.) y permite gestionarlo desde la misma UI que Docker.

---

## 🧹 6. Detener / eliminar

Detener el cluster (conserva el estado):

```bash
minikube stop
```

Eliminar el cluster por completo:

```bash
minikube delete
```

Quitar el agente K8s (si se dejó de usar):

```bash
kubectl delete -f portainer-agent.yaml
kubectl delete namespace portainer
kubectl delete service/s-portainer-agent-headless -n portainer
```

---

## ⚠️ Notas y limitaciones

- El agente usa **TLS** con certificado autofirmado → en Portainer hay que activar `Skip TLS verification`.
- La IP `192.168.49.x` pertenece a la red Docker `minikube`; **no** es alcanzable desde `prisma-net` ni desde `localhost` del host por defecto (por eso se conecta el contenedor a la red `minikube`). `ping` hacia esa IP falla pero **TCP sí funciona** desde contenedores de esa red.
- La versión del `portainer/agent:MAYOR.MENOR.PATCH` debe alinearse con la del servidor Portainer.
- El contenedor Portainer usa una imagen distroless (sin shell), por lo que probar conectividad desde dentro requiere un contenedor auxiliar (p. ej. `alpine`).
