# Kubernetes — PRISMA

Manifiestos Kustomize (`base/` + `overlays/{dev,staging,prod}/`) para desplegar la stack completa
en un cluster real. Ver también [`docs/CI-CD-Infra.md`](../../docs/CI-CD-Infra.md) (cómo dispara
cada ambiente `deploy.yml`) y [`docs/Certificados-TLS.md`](../../docs/Certificados-TLS.md)
(hostname dinámico de Keycloak, mismo mecanismo que acá).

Hay un cuarto overlay, `overlays/demo/`, que **no** es un ambiente de despliegue real: es un MVP
recortado (sin Ollama/MinIO/Ingress) pensado para grabar un video demostrando la tecnología en
minikube. Ver [`docs/Demo-Kubernetes-MVP.md`](../../docs/Demo-Kubernetes-MVP.md) para el guion
completo, [`docs/Demo-Kubernetes-MVP-Slides.md`](../../docs/Demo-Kubernetes-MVP-Slides.md) para
el script del PPT que lo acompaña, y
[`docs/Demo-Kubernetes-MVP-Prompt.md`](../../docs/Demo-Kubernetes-MVP-Prompt.md) para el prompt
que genera ese PPT con una IA de presentaciones.

## Primer deploy en un namespace nuevo

`kubectl apply -k .` **no** crea el Secret con las credenciales reales ni el import de Keycloak
por sí solo — son dos pasos manuales, una sola vez por namespace, antes del primer apply:

```bash
# 1) Namespace (o dejar que namespace.yaml de la kustomization lo cree con el primer apply)
kubectl create namespace prisma-staging

# 2) Secret real -- prisma-secrets NO está en resources: de ningún kustomization.yaml (a
#    propósito, ver "Por qué el Secret no vive en Kustomize" abajo). Usar secret.example.yaml
#    como referencia de las keys, con valores generados (ej. `openssl rand -base64 32`).
kubectl create secret generic prisma-secrets -n prisma-staging \
  --from-literal=SPRING_DATASOURCE_USERNAME=prisma \
  --from-literal=SPRING_DATASOURCE_PASSWORD=... \
  --from-literal=SPRING_DATA_REDIS_PASSWORD=... \
  --from-literal=JWT_SECRET=... \
  --from-literal=MINIO_ACCESS_KEY=... \
  --from-literal=MINIO_SECRET_KEY=... \
  --from-literal=KEYCLOAK_DB_PASSWORD=... \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD=... \
  --from-literal=KEYCLOAK_ADMIN_CLIENT_SECRET=... \
  --from-literal=AI_INTERNAL_API_KEY=...

# 3) Generar las copias que Kustomize/el build de backend-ai necesitan (realm de Keycloak,
#    knowledge base del RAG) -- ver "Assets generados" abajo.
make sync-k8s-assets

# 4) Recién ahora
kubectl apply -k infra/kubernetes/overlays/staging
```

Sin el paso 2, los Pods de `backend-core`/`backend-ai`/`postgres`/`keycloak-db`/`minio` quedan en
`CreateContainerConfigError` (esperado: `kubectl apply -k .` no puede crear un Secret que no está
en su árbol). Sin el paso 3, `kubectl apply -k .` falla al intentar generar el ConfigMap del
realm de Keycloak.

## Por qué el Secret no vive en Kustomize

`base/configmap.yaml` sólo tiene el `ConfigMap` (valores no sensibles, iguales en todo ambiente) y
un PVC. Las credenciales reales se crean a mano (paso 2 de arriba) y **nunca** están en
`resources:` de ningún `kustomization.yaml` — así, `kubectl apply -k .` (lo que corre `deploy.yml`
en cada push) nunca las toca. Si estuvieran en Kustomize, cualquier corrección manual
(`kubectl edit secret`) se perdería en el siguiente deploy, porque Kustomize reemplaza el objeto
completo, no hace merge selectivo.

`secret.example.yaml` es la plantilla (análoga a `.env.example` en la raíz del repo): documenta
las keys esperadas, nunca se aplica directo con valores reales.

## Assets generados (`make sync-k8s-assets`)

Dos archivos que Kustomize/Docker necesitan pero que **no viven** dentro de `infra/kubernetes/` ni
`apps/backend-ai/` (fuente de verdad en otro lado del repo):

| Copia generada (gitignorada) | Fuente | Por qué no es el original directamente |
|---|---|---|
| `base/keycloak-realm.json` | `infra/keycloak/realm-prisma.json` | Kustomize no permite leer archivos fuera del árbol de la kustomization (restricción de diseño) |
| `apps/backend-ai/knowledge-base/` | `docs/mcu-5.0/` | Docker no permite `COPY` desde fuera del build context (`apps/backend-ai/`) |

`make sync-k8s-assets` genera ambas. Corre automáticamente en CI (`deploy.yml` antes de aplicar
manifiestos, `build-and-push.yml` antes de construir la imagen de `backend-ai`) — sólo hace falta
correrlo a mano para probar localmente contra un cluster (minikube, ver
[`docs/Kubernetes-Minikube-Portainer.md`](../../docs/Kubernetes-Minikube-Portainer.md)).

`apps/backend-ai/knowledge-base/` tiene un `.gitkeep` versionado — si nunca se corrió el target,
el directorio existe vacío y el build de la imagen no falla, sólo el RAG queda sin contexto.

## Keycloak — caveats operativos

- **`--import-realm` sólo importa si el realm no existe todavía** en ese namespace. Editar
  `infra/keycloak/realm-prisma.json` después del primer deploy **no** actualiza el Keycloak que ya
  está corriendo — hay que aplicar el cambio a mano por Admin Console/API (`https://<host
  del ambiente>/auth/admin`, ver `KEYCLOAK_ADMIN_PASSWORD` del Secret real). Típicamente hace
  falta esto la primera vez que se despliega un ambiente nuevo, para:
  - Rotar el secret del client `prisma-backend` (el realm lo importa con
    `CHANGE_ME_IN_PRODUCTION`) y copiarlo a `KEYCLOAK_ADMIN_CLIENT_SECRET` en el Secret real.
  - **No** hace falta tocar **Valid redirect URIs**/**Web origins** del client `prisma-frontend`
    por cada ambiente nuevo: el realm los trae en `"*"` a propósito, justo para no depender de este
    paso manual por host/dominio (ver `docs/SECURITY.md`, sección "Endurecimientos recientes",
    ítem 10).
- Probes de salud del pod `keycloak` apuntan al **management port (9000)**, `/health/ready` y
  `/health/live` — no al puerto 8080 bajo `/auth`. Esto es lo documentado para Keycloak 24+, pero
  no se pudo levantar el contenedor en este entorno para confirmar el path exacto contra la 26.7 —
  si el pod no pasa `readinessProbe`, es el primer lugar para mirar (`kubectl describe pod`).

## MinIO — Job `minio-init` inmutable

`spec.template` de un `Job` no se puede editar in-place. Si se cambia `minio-init-job.yaml` (por
ejemplo, para agregar un tercer bucket), el siguiente `kubectl apply -k .` falla con
`field is immutable` — hay que borrar el Job viejo primero:

```bash
kubectl delete job minio-init -n prisma-<env>
kubectl apply -k infra/kubernetes/overlays/<env>
```

## Verificar los manifiestos sin un cluster

```bash
kubectl kustomize infra/kubernetes/overlays/dev     > /dev/null
kubectl kustomize infra/kubernetes/overlays/staging > /dev/null
kubectl kustomize infra/kubernetes/overlays/prod    > /dev/null
```

Detecta errores de sintaxis/referencias (incluye el `configMapGenerator` del realm) sin necesitar
`kubectl apply` contra nada. `kubectl kustomize` viene bundlado en `kubectl` (no hace falta el
binario standalone `kustomize` para esto — sí lo sigue necesitando `deploy.yml` para
`kustomize edit set image`).
