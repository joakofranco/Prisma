# 🌍 Ambientes de despliegue — de desarrollo local a producción

Instructivo de cómo levantar PRISMA desde cero en cada uno de los cuatro ambientes del proyecto.
Pensado para alguien que nunca lo hizo: qué instalar antes, qué archivo/variable tocar en cada
paso, y cómo confirmar que quedó bien. El detalle de cada job del workflow que dispara estos
despliegues está en [`CI-CD-Infra.md`](CI-CD-Infra.md) (sección "5. `deploy.yml`" y "Parte 3 —
Deployment a Kubernetes").

| Ambiente | Cómo se levanta | Trigger | Infraestructura |
|---|---|---|---|
| **Local** | `make up` (`docker compose --profile full up -d --build`) | Manual, en tu máquina | Docker Compose, todo en un host |
| **Dev remoto** | Automático | `push`/merge a la rama `dev` | Servidor único, Docker Compose (vía SSH) |
| **Staging** | Automático | Tag `v*.*.*-rc*` (ej. `v1.2.0-rc1`) | Kubernetes (Kustomize), namespace `prisma-staging` |
| **Producción** | Automático, con aprobación | Tag `v*.*.*` (ej. `v1.2.0`) | Kubernetes (Kustomize), namespace `prisma-prod` |

Los tres ambientes remotos (dev, staging, prod) los despliega el workflow **`deploy.yml`** una vez
configurados — no hace falta correr nada a mano en el día a día. Pero **cada uno necesita una
puesta a punto manual la primera vez** (servidor/cluster + secrets de GitHub): eso es lo que cubre
cada sección de abajo.

> **Placeholders que vas a tener que rotar en cualquier ambiente que no sea tu máquina local**:
> `.env.example` (Compose) e `infra/kubernetes/base/secret.example.yaml` (K8s) traen valores
> `CHANGE_ME_*` a propósito — nunca uses esos valores tal cual en un servidor real. Generá cada
> uno con, por ejemplo, `openssl rand -base64 32`:
>
> | Variable | Para qué es |
> |---|---|
> | `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `SONAR_DB_PASSWORD` | Acceso a las bases de datos |
> | `JWT_SECRET` | Firma de las sesiones de la app — el más crítico de rotar |
> | `AI_INTERNAL_API_KEY` | Sin esto, `backend-ai` queda alcanzable sin autenticación interna |
> | `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | Admin de Keycloak (IAM completo) |
> | `MINIO_ROOT_PASSWORD` (Compose) / `MINIO_SECRET_KEY` (K8s) | Acceso a las evidencias almacenadas |
> | `GF_SECURITY_ADMIN_PASSWORD`, `PORTAINER_ADMIN_PASSWORD` | Admin de las herramientas de infraestructura |
> | `PGADMIN_DEFAULT_PASSWORD` | Admin de pgAdmin |
>
> `KEYCLOAK_ADMIN_CLIENT_SECRET` es distinto al resto de la tabla: no alcanza con cambiar el
> `.env` — Keycloak no lee variables de entorno de `infra/keycloak/realm-prisma.json`, así que el
> secret real del client `prisma-backend` queda fijo en lo que importó ese JSON (`CHANGE_ME_IN_PRODUCTION`
> por defecto) hasta que alguien lo rota A MANO desde Keycloak (Admin Console → Clients →
> `prisma-backend` → Credentials → Regenerate) o edita el JSON antes del primer `--import-realm`.
> Si sólo se cambia el `.env`, el aprovisionamiento de usuarios nuevos (Configuración → Usuarios)
> queda roto: la fila se crea en Postgres pero la llamada a la Admin API de Keycloak falla, y ese
> usuario nunca puede loguearse.

## 1) Local — Docker Compose en tu máquina

### Prerrequisitos

Ver [Requisitos previos](../README.md#requisitos-previos) del README principal — en resumen:
Docker Desktop, Git, y opcionalmente `make`. En Windows sin `make` instalado, cada comando `make
<target>` de esta guía tiene su equivalente `docker compose ...` explícito en el `Makefile` — abrilo
y copiá el comando del target que necesites.

### Paso a paso

```bash
# 1) Clonar el repo
git clone https://github.com/joakofranco/Prisma.git
cd Prisma

# 2) Copiar variables de entorno
cp .env.example .env
```

**Qué configurar en el `.env` para desarrollo local**: en general, nada es obligatorio para que
`make up` funcione — los valores `CHANGE_ME_*` de `.env.example` sirven tal cual para levantar la
stack en tu máquina (no está expuesta a nadie más). Igual, revisá:

- Si ya tenés algo corriendo en tu máquina en los puertos 5432, 6379, 8080, 8081, 8180, 9000-9003,
  3000, 9090, 5050 o 11434, cambiá el `*_PORT` correspondiente en el `.env` para evitar el
  conflicto (ver [Problemas comunes](#problemas-comunes) más abajo).
- `TZ` si tu zona horaria no es `America/Montevideo`.

```bash
# 3) Generar el certificado TLS autofirmado de nginx (una vez; sin esto el contenedor de
#    nginx no arranca -- ver docs/Certificados-TLS.md)
make certs

# 4) Levantar TODA la stack (frontend, backends, BD, IAM, monitoreo, etc.)
make up
# equivalente a: docker compose --profile full up -d --build --remove-orphans
# Primera vez: descarga/compila imágenes -- puede tardar varios minutos. Los healthchecks de
# cada servicio (`make ps`) van a mostrar "starting" hasta que Postgres/Keycloak/etc. terminen
# de inicializar -- normal, no hace falta reintentar nada.

# 5) Descargar el modelo Llama 3 (primera vez, ~4.7 GB -- puede tardar según tu conexión)
make pull-llm

# 6) Verificar que todo esté OK
make health
```

### Verificación

`make health` chequea `backend-core`/`backend-ai`/`frontend`. Para confirmar el resto, entrá a
http://localhost:8080, logueate con `admin@prisma.local` / `Admin1234!` (Keycloak va a pedir
cambiarla en el primer login — es esperado) y confirmá que el dashboard carga con el catálogo MCU
5.0 ya sembrado. La tabla completa de URLs/credenciales de cada herramienta está en
[Puesta en marcha local](../README.md#puesta-en-marcha-local-quickstart) del README principal.

### Problemas comunes

- **nginx no arranca / `cannot load certificate prisma.crt`**: te salteaste el paso 3
  (`make certs`). Corré `make certs` y `make restart-nginx`.
- **pgAdmin en loop de reinicio, log dice `does not appear to be a valid email address`**: el
  `PGADMIN_DEFAULT_EMAIL` de tu `.env` usa un TLD reservado (`.local`, `.test`, `.example`,
  `.invalid`) — pgAdmin4 lo rechaza aunque `CHECK_EMAIL_DELIVERABILITY` esté en `False`. Usá un
  dominio con TLD real aunque no resuelva (ej. `admin@prisma.uy`, que es el default actual de
  `.env.example`).
- **Puerto ya en uso (`port is already allocated`)**: dos servicios del `.env` apuntando al mismo
  puerto de host, o algo externo a Docker ya lo tiene tomado. `docker compose --profile full
  config --quiet` no detecta colisiones de puerto por sí solo — revisá a mano los `*_PORT` de tu
  `.env` contra `netstat -ano` (Windows) / `lsof -i` (Mac/Linux).
- **Login completa pero el dashboard queda cargando / todo da 401**: no es un problema de
  `redirect_uri` (Keycloak acepta cualquier host, ver más abajo) sino de `KEYCLOAK_TRUSTED_ISSUERS`
  en tu `.env` — agregá ahí el origen exacto (esquema+host+puerto) por el que estás entrando. Ver
  el mismo mecanismo explicado en [`RUNBOOK.md`](RUNBOOK.md#el-login-funciona-pero-todas-las-llamadas-a-la-api-dan-401-dashboard-cargando-infinito).

## 2) Dev remoto — servidor único, automático al mergear a `dev`

### Prerrequisitos (en el servidor)

- Un servidor Linux (VM cloud, on-prem, lo que sea) con **Docker Engine + el plugin Compose v2**
  instalados (`docker compose version` tiene que andar, no el `docker-compose` viejo standalone).
- Acceso SSH con un usuario que pueda correr `docker` (en el grupo `docker`, o vía `sudo`).
- Un dominio o IP fija para acceder (no hace falta DNS público — con la IP alcanza).

### Configuración inicial del servidor (una sola vez)

```bash
ssh <usuario>@<servidor-dev>
sudo mkdir -p /opt/prisma && sudo chown $USER /opt/prisma
git clone https://github.com/joakofranco/Prisma.git /opt/prisma
cd /opt/prisma
cp .env.example .env
```

**Qué configurar en el `.env` de este servidor** (a diferencia de local, acá SÍ importa):

1. **Generar un valor real para cada `CHANGE_ME_*`** de la tabla al principio de este documento —
   un servidor con las contraseñas de ejemplo del repo (públicas, en GitHub) es una puerta
   abierta. Generar 12 valores de un saque (uno por línea, en el mismo orden que la tabla):
   ```bash
   for i in $(seq 1 12); do openssl rand -base64 32; done
   ```
   Abrir el `.env` y pegar cada valor generado en su variable, reemplazando el placeholder
   completo:
   ```bash
   nano .env   # o vi/vim — cualquier editor de texto en el servidor sirve
   ```
   Variables a completar (mismas 12 de la tabla del principio del documento):
   `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `SONAR_DB_PASSWORD`,
   `JWT_SECRET`, `AI_INTERNAL_API_KEY`, `KEYCLOAK_ADMIN_PASSWORD`,
   `KEYCLOAK_ADMIN_CLIENT_SECRET`, `MINIO_ROOT_PASSWORD`, `GF_SECURITY_ADMIN_PASSWORD`,
   `PORTAINER_ADMIN_PASSWORD`, `PGADMIN_DEFAULT_PASSWORD`. Al terminar, confirmar que no quedó
   ninguno sin reemplazar:
   ```bash
   grep CHANGE_ME .env   # sin salida = OK; si imprime algo, falta esa variable
   ```
   `KEYCLOAK_ADMIN_CLIENT_SECRET` es el único que necesita un paso extra (ver la nota arriba de
   esta sección): además de pegarlo en `.env`, hay que rotarlo A MANO en Keycloak después del
   primer arranque (Admin Console → Clients → `prisma-backend` → Credentials → Regenerate,
   pegando ahí el mismo valor).
2. **`GHCR_ORG`**: abrir `.env` y reemplazar el placeholder `your-github-org` por el dueño real
   del repo en GHCR (tu usuario u organización de GitHub) — si lo dejás como está, `docker
   compose pull` va a intentar traer `ghcr.io/your-github-org/...`, que no existe:
   ```bash
   sed -i 's/^GHCR_ORG=.*/GHCR_ORG=<tu-usuario-u-org>/' .env
   ```
3. Si los paquetes de GHCR son privados (default de GitHub Packages salvo que los hayas hecho
   públicos a mano):
   1. En GitHub: **tu foto de perfil (arriba a la derecha) → Settings → Developer settings**
      (al final del menú lateral) **→ Personal access tokens → Tokens (classic) → Generate new
      token (classic)**.
   2. Ponerle un nombre (ej. `prisma-ghcr-pull`), marcar únicamente el scope **`read:packages`**,
      generar y copiar el token (no se vuelve a mostrar).
   3. Completar `GHCR_USER` (tu usuario de GitHub) y `GHCR_TOKEN` (el token recién generado) en
      el `.env` del servidor.
   4. Loguearse una vez con esas credenciales (Compose no las lee solo, hay que usarlas a mano):
      ```bash
      echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
      ```
4. Si vas a entrar por un dominio real (no una IP pelada), completá estas dos variables del
   `.env` con ese dominio — ejemplo para `dev.prisma.example.uy`:
   ```bash
   KEYCLOAK_TRUSTED_ISSUERS=https://dev.prisma.example.uy/auth/realms/prisma
   CORS_ALLOWED_ORIGINS=https://dev.prisma.example.uy
   ```
   (ver el mismo paso explicado en detalle en [`Certificados-TLS.md`](Certificados-TLS.md)).

```bash
# Certificado TLS (si tenés un dominio real, mejor un cert de una CA pública -- este autofirmado
# es la opción rápida, igual que en local; ver docs/Certificados-TLS.md)
scripts/generate-nginx-cert.sh --cn <tu-host> --san <tu-host>,localhost,127.0.0.1

# Primer arranque manual (antes de que el pipeline haga esto solo)
docker compose --profile full up -d --build
```

**Accedas por el host que accedas** (una IP, un dominio real, `localhost`) no hace falta tocar
nada en Keycloak: el client `prisma-frontend` trae `redirectUris`/`webOrigins` en `"*"` a propósito
(ver [`SECURITY.md`](SECURITY.md#endurecimientos-recientes-para-que-no-se-repitan), ítem 10) —
justamente para no depender de un paso manual por cada IP/dominio distinto en el que se despliegue.

### Configurar el pipeline (para que el deploy automático funcione)

1. **Generar el par de claves SSH que va a usar el pipeline** (en tu máquina local, no en el
   servidor):
   ```bash
   ssh-keygen -t ed25519 -C "prisma-deploy-dev" -f deploy_dev_key -N ""
   ```
   Esto crea dos archivos: `deploy_dev_key` (privada) y `deploy_dev_key.pub` (pública).
2. **Copiar la clave pública al servidor**, para el mismo usuario que clonó `/opt/prisma`:
   ```bash
   ssh-copy-id -i deploy_dev_key.pub <usuario>@<servidor-dev>
   # si ssh-copy-id no está disponible (ej. Windows):
   #   cat deploy_dev_key.pub | ssh <usuario>@<servidor-dev> "cat >> ~/.ssh/authorized_keys"
   ```
   Confirmar que funciona sin pedir contraseña: `ssh -i deploy_dev_key <usuario>@<servidor-dev>`.
3. **En GitHub**: entrar al repo → pestaña **Settings** (arriba) → **Environments** (menú
   lateral izquierdo) → **New environment** → escribir `dev` → **Configure environment**.
4. Dentro del environment `dev` recién creado, agregar cada fila con **Add secret** (las tres
   primeras) y **Add variable** (la última):

   | Tipo | Nombre | Valor |
   |---|---|---|
   | Secret | `DEV_HOST` | IP o dominio del servidor |
   | Secret | `DEV_USER` | El usuario SSH usado en el paso 2 |
   | Secret | `DEV_SSH_KEY` | Contenido completo de la clave **privada**: `cat deploy_dev_key` y pegar todo, incluidas las líneas `-----BEGIN...`/`-----END...` |
   | Variable | `APP_URL` | URL pública para el smoke test post-deploy, ej. `http://<tu-host>:8080` |
5. Borrar `deploy_dev_key`/`deploy_dev_key.pub` de tu máquina local si no los vas a reutilizar
   (ya quedaron guardados como secret en GitHub y como `authorized_keys` en el servidor).

### Cómo funciona después

Cada `push`/merge a `dev` dispara `deploy.yml`, que por SSH corre:

```bash
cd /opt/prisma && git pull origin dev && docker compose pull && \
  docker compose --profile full up -d --remove-orphans && docker system prune -f
```

Para reproducirlo a mano (ej. si el pipeline está caído), corré esos mismos comandos en el
servidor — no hay ningún paso oculto. También se puede disparar manualmente desde
**Actions → Deploy → Run workflow**, eligiendo `environment: dev`.

## 3) Staging — Kubernetes, por tag release-candidate

### Prerrequisitos (cluster)

- Un cluster Kubernetes real (cloud o [minikube local](Kubernetes-Minikube-Portainer.md) para
  probar el flujo antes de ir a uno de verdad) con **ingress-nginx** y **cert-manager**
  instalados — `infra/kubernetes/base/ingress.yaml` asume `kubernetes.io/ingress.class: nginx` y
  un `ClusterIssuer` llamado **`letsencrypt`** ya creado en el cluster; sin eso el Ingress no va a
  poder emitir el certificado TLS.
- `kubectl` (con acceso al cluster) y el binario standalone `kustomize` si vas a correr los pasos
  a mano (CI ya lo trae en `ubuntu-latest`).
- Un subdominio real apuntando (DNS) a la IP del ingress del cluster, ej. `staging.prisma.example.uy`.

### Primer deploy a este namespace (una sola vez)

```bash
# 1) Namespace
kubectl create namespace prisma-staging
```

**2) Secret real.** Generar los 9 valores primero (guardarlos en variables de shell para no
tipearlos dos veces ni dejarlos en el historial de `kubectl`):
```bash
export SPRING_DATASOURCE_PASSWORD=$(openssl rand -base64 32)
export SPRING_DATA_REDIS_PASSWORD=$(openssl rand -base64 32)
export JWT_SECRET=$(openssl rand -base64 32)
export MINIO_ACCESS_KEY=$(openssl rand -hex 16)
export MINIO_SECRET_KEY=$(openssl rand -base64 32)
export KEYCLOAK_DB_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -base64 32)
export KEYCLOAK_ADMIN_CLIENT_SECRET=$(openssl rand -base64 32)
export AI_INTERNAL_API_KEY=$(openssl rand -base64 32)
```
Anotar `KEYCLOAK_ADMIN_CLIENT_SECRET` aparte (lo vas a necesitar de nuevo más abajo, para
pegarlo también dentro de Keycloak). Crear el Secret con esos valores:
```bash
kubectl create secret generic prisma-secrets -n prisma-staging \
  --from-literal=SPRING_DATASOURCE_USERNAME=prisma \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  --from-literal=SPRING_DATA_REDIS_PASSWORD="$SPRING_DATA_REDIS_PASSWORD" \
  --from-literal=JWT_SECRET="$JWT_SECRET" \
  --from-literal=MINIO_ACCESS_KEY="$MINIO_ACCESS_KEY" \
  --from-literal=MINIO_SECRET_KEY="$MINIO_SECRET_KEY" \
  --from-literal=KEYCLOAK_DB_PASSWORD="$KEYCLOAK_DB_PASSWORD" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  --from-literal=KEYCLOAK_ADMIN_CLIENT_SECRET="$KEYCLOAK_ADMIN_CLIENT_SECRET" \
  --from-literal=AI_INTERNAL_API_KEY="$AI_INTERNAL_API_KEY"
```
(referencia de las keys en [`infra/kubernetes/base/secret.example.yaml`](../infra/kubernetes/base/secret.example.yaml)).

**3) Dominio.** Si tu dominio real no es `staging.prisma.example.uy`, reemplazalo en los dos
archivos que lo tienen hardcodeado, antes de aplicar:
```bash
sed -i 's/staging\.prisma\.example\.uy/staging.<tu-dominio-real>/' \
  infra/kubernetes/overlays/staging/ingress-patch.yaml \
  infra/kubernetes/overlays/staging/configmap-patch.yaml
```
Confirmar el reemplazo: `grep -r staging.prisma.example.uy infra/kubernetes/overlays/staging/`
no debe imprimir nada.

```bash
# 4) Generar las copias que Kustomize/la imagen de backend-ai necesitan
make sync-k8s-assets

# 5) Aplicar
kubectl apply -k infra/kubernetes/overlays/staging
kubectl -n prisma-staging rollout status deployment/backend-core --timeout=5m
kubectl -n prisma-staging rollout status deployment/backend-ai   --timeout=5m
kubectl -n prisma-staging rollout status deployment/frontend     --timeout=5m
```

**Después del primer deploy**, rotar el secret del client `prisma-backend` (el realm lo importa
con el valor de ejemplo `CHANGE_ME_IN_PRODUCTION`, que no coincide con el
`KEYCLOAK_ADMIN_CLIENT_SECRET` recién generado arriba):
1. Entrar a `https://staging.<tu-dominio>/auth/admin`, loguearse con `KEYCLOAK_ADMIN` /
   `KEYCLOAK_ADMIN_PASSWORD` (los valores que pusiste en el Secret).
2. Realm **prisma** (selector arriba a la izquierda) → **Clients** → `prisma-backend` →
   pestaña **Credentials** → botón **Regenerate**.
3. Copiar el secret que Keycloak generó y actualizar el Secret de Kubernetes con ESE valor
   (tiene que coincidir exacto en los dos lados):
   ```bash
   kubectl create secret generic prisma-secrets -n prisma-staging \
     --from-literal=KEYCLOAK_ADMIN_CLIENT_SECRET='<el-valor-que-copiaste-de-keycloak>' \
     --dry-run=client -o yaml | kubectl apply -f -
   kubectl -n prisma-staging rollout restart deployment/backend-core
   ```
No hace falta tocar **Valid redirect URIs**/**Web origins** de `prisma-frontend` (ya vienen en
`"*"`, ver [`SECURITY.md`](SECURITY.md#endurecimientos-recientes-para-que-no-se-repitan), ítem
10). Detalle completo de otros caveats de Keycloak en K8s (qué probes/Jobs tienen gotchas) en
[`../infra/kubernetes/README.md`](../infra/kubernetes/README.md).

### Configurar el pipeline

1. Generar el kubeconfig a pasarle a GitHub. Lo más simple (cuenta admin completa del cluster):
   ```bash
   cat ~/.kube/config | base64 -w0
   ```
   Más seguro (recomendado): crear un `ServiceAccount` acotado al namespace `prisma-staging`,
   con permisos sólo sobre ese namespace, y armar un kubeconfig a partir de su token:
   ```bash
   kubectl create serviceaccount prisma-deployer -n prisma-staging
   kubectl create rolebinding prisma-deployer-binding -n prisma-staging \
     --clusterrole=edit --serviceaccount=prisma-staging:prisma-deployer
   kubectl create token prisma-deployer -n prisma-staging --duration=8760h > /tmp/sa-token

   # armar el kubeconfig apuntando al mismo cluster/CA que ya usás, pero con este token
   CLUSTER_NAME=$(kubectl config view --minify -o jsonpath='{.clusters[0].name}')
   CLUSTER_SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
   kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' > /tmp/ca.b64

   cat > /tmp/kubeconfig-staging.yaml <<EOF
   apiVersion: v1
   kind: Config
   clusters:
   - name: $CLUSTER_NAME
     cluster:
       server: $CLUSTER_SERVER
       certificate-authority-data: $(cat /tmp/ca.b64)
   contexts:
   - name: prisma-staging
     context: {cluster: $CLUSTER_NAME, namespace: prisma-staging, user: prisma-deployer}
   current-context: prisma-staging
   users:
   - name: prisma-deployer
     user: {token: "$(cat /tmp/sa-token)"}
   EOF

   cat /tmp/kubeconfig-staging.yaml | base64 -w0   # esto es lo que pegás en el secret KUBE_CONFIG
   rm /tmp/sa-token /tmp/ca.b64 /tmp/kubeconfig-staging.yaml
   ```
2. En GitHub: repo → **Settings → Environments → New environment** → escribir `staging` →
   **Configure environment**.
3. Agregar:

   | Tipo | Nombre | Valor |
   |---|---|---|
   | Secret | `KUBE_CONFIG` | El base64 generado en el paso 1 (pegado completo, una sola línea) |
   | Variable | `APP_URL` | `https://staging.<tu-dominio>` |

### Deploys siguientes

Empujar un tag `v*.*.*-rc*` (ej. `v1.3.0-rc1`), o disparar manualmente desde
**Actions → Deploy → Run workflow** con `environment: staging`. El pipeline sólo hace
`kustomize edit set image` + `kubectl apply -k .` + esperar el rollout — los pasos 1-4 de arriba
no se repiten (son de una sola vez por namespace).

## 4) Producción — Kubernetes, por tag semver + aprobación

### Prerrequisitos

Los mismos que staging, más 2 revisores obligatorios (configurados más abajo). Dominio real de
producción, ej. `prisma.example.uy`.

### Primer deploy al namespace `prisma-prod`

Mismos pasos que [Staging](#3-staging--kubernetes-por-tag-release-candidate) punto por punto,
reemplazando `staging` por `prod` en namespace y comandos (`kubectl create namespace prisma-prod`,
`-n prisma-prod`, etc.). Para el dominio, el `sed` del paso 3 de staging queda así (los archivos
de `prod` ya traen `prisma.example.uy` como default — sólo hace falta tocarlos si tu dominio real
es otro):
```bash
sed -i 's/prisma\.example\.uy/<tu-dominio-real>/' \
  infra/kubernetes/overlays/prod/ingress-patch.yaml \
  infra/kubernetes/overlays/prod/configmap-patch.yaml
```
Y el paso de rotar `KEYCLOAK_ADMIN_CLIENT_SECRET` después del primer deploy es igual de
obligatorio acá (mismos comandos que en staging, con `-n prisma-prod`).

### Configurar el pipeline

1. Repetir el paso 1 de staging (generar el `KUBE_CONFIG`, en lo posible con un `ServiceAccount`
   acotado a `prisma-prod`, no el mismo que staging).
2. GitHub: repo → **Settings → Environments → New environment** → escribir `prod` →
   **Configure environment**.
3. Agregar `KUBE_CONFIG` (Secret) y `APP_URL` (Variable, ej. `https://<tu-dominio-real>`) igual
   que en staging.
4. **Bloquear el deploy hasta aprobación manual** — en la misma pantalla del environment `prod`:
   sección **Deployment protection rules** → tildar **Required reviewers** → buscar y agregar
   (mínimo) 2 usuarios/equipos de GitHub → **Save protection rules**. Sin este paso el tag
   dispara el deploy directo a producción sin pausa.

### Deploys siguientes

```bash
git tag v1.3.0        # semver, SIN sufijo -rc
git push origin v1.3.0
```

El job queda **pausado esperando aprobación** antes de aplicar los manifiestos contra
`infra/kubernetes/overlays/prod` / namespace `prisma-prod`. Después de aprobar y aplicar, corre un
smoke test (`GET {APP_URL}/actuator/health`) antes de dar el deploy por exitoso.

### Alternativa sin Kubernetes (un solo servidor)

Para un despliegue más simple (sin cluster), mismos pasos que
[Dev remoto](#2-dev-remoto--servidor-único-automático-al-mergear-a-dev) pero con los overrides de
`docker-compose.prod.yml` (menos puertos expuestos, límites de recursos, `ssl=on` en Postgres,
Keycloak en modo `start` en vez de `start-dev`):

```bash
ssh <usuario>@<servidor-prod>
cd /opt/prisma
git pull origin main
# Primera vez en este servidor: mismos pasos de configuración de .env/certs/GHCR login que
# "Dev remoto" más arriba.
docker compose pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile full up -d --remove-orphans
```

## Referencias

- Detalle de cada job del pipeline que dispara estos despliegues: [`CI-CD-Infra.md`](CI-CD-Infra.md)
- Cluster Kubernetes local para probar antes de ir a un cluster real: [`Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)
- Secret/assets de Kubernetes, caveats de Keycloak/MinIO en K8s: [`../infra/kubernetes/README.md`](../infra/kubernetes/README.md)
- HTTPS, certificado autofirmado y su renovación: [`Certificados-TLS.md`](Certificados-TLS.md)
- Setup de desarrollo local por SO, con más detalle que la sección 1 de acá: [`Dev-Config.md`](Dev-Config.md)
- Incidentes reales de operación y su causa raíz: [`RUNBOOK.md`](RUNBOOK.md)
