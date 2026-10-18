# 🌍 Ambientes de despliegue — de desarrollo local a producción

Instructivo de cómo levantar PRISMA desde cero en cada uno de los cuatro ambientes del proyecto.
Pensado para alguien que nunca lo hizo: qué instalar antes, qué archivo/variable tocar en cada
paso, y cómo confirmar que quedó bien. El detalle de cada job del workflow que dispara estos
despliegues está en [`CI-CD-Infra.md`](CI-CD-Infra.md) (sección "5. `deploy.yml`" y "Parte 3 —
Deployment por ambiente").

| Ambiente | Cómo se levanta | Trigger | Infraestructura |
|---|---|---|---|
| **Local** | `make up` (`docker compose --profile full up -d --build`) | Manual, en tu máquina | Docker Compose, todo en un host |
| **Dev remoto** | Automático | `push`/merge a la rama `dev` | Servidor único, Docker Compose (vía SSH) |
| **Staging** | Automático | Tag `v*.*.*-rc*` (ej. `v1.2.0-rc1`) | Servidor de staging, Docker Compose (vía SSH) |
| **Producción** | Automático, con aprobación | Tag `v*.*.*` (ej. `v1.2.0`) | Servidor de producción, Docker Compose (vía SSH) |

Los tres ambientes remotos (dev, staging, prod) los despliega el workflow **`deploy.yml`** una vez
configurados — no hace falta correr nada a mano en el día a día. Pero **cada uno necesita una
puesta a punto manual la primera vez** (servidor + secrets de GitHub): eso es lo que cubre
cada sección de abajo.

> **Placeholders que vas a tener que rotar en cualquier ambiente que no sea tu máquina local**:
> `.env.example` trae valores
> `CHANGE_ME_*` a propósito — nunca uses esos valores tal cual en un servidor real. Generá cada
> uno con, por ejemplo, `openssl rand -base64 32`:
>
> | Variable | Para qué es |
> |---|---|
> | `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `SONAR_DB_PASSWORD` | Acceso a las bases de datos |
> | `JWT_SECRET` | Firma de las sesiones de la app — el más crítico de rotar |
> | `AI_INTERNAL_API_KEY` | Sin esto, `backend-ai` queda alcanzable sin autenticación interna |
> | `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ADMIN_CLIENT_SECRET` | Admin de Keycloak (IAM completo) |
> | `MINIO_ROOT_PASSWORD` | Acceso a las evidencias almacenadas |
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

**MFA obligatoria para `PRISMA_ADMIN` y `ORG_RESPONSIBLE`**: cuando Keycloak ya esté arriba
(`make ps` en `healthy`), activar el flujo de login con segundo factor. Hay que repetirlo en
**cada ambiente nuevo** (dev, staging, producción), porque `--import-realm` solo importa
`realm-prisma.json` la primera vez que se crea el realm:

```bash
make mfa-enable     # idempotente; detalle en docs/SECURITY.md → "MFA (TOTP) obligatoria por rol"
make mfa-status     # confirmar el flujo y quién todavía tiene que registrar OTP
```

El primer login de cada usuario con esos roles (incluido `admin@prisma.local`) le va a pedir
registrar una app autenticadora (Google/Microsoft Authenticator, FreeOTP...).

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

## 3) Staging — Docker Compose por SSH, por tag release-candidate

Igual que [Dev remoto](#2-dev-remoto--servidor-único-automático-al-mergear-a-dev), pero en un
servidor propio de staging y disparado por un tag release-candidate.

### Prerrequisitos y configuración inicial del servidor (una sola vez)

Repetir la sección "Prerrequisitos" y "Configuración inicial del servidor" de Dev remoto en el
servidor de staging: Docker + Compose, repo clonado en `/opt/prisma`, `.env` con todos los
`CHANGE_ME_*` reemplazados, `GHCR_ORG`/login a GHCR y certificado TLS. Un dominio real apuntando
(DNS) al servidor, ej. `staging.prisma.example.uy`, y las variables `KEYCLOAK_TRUSTED_ISSUERS` y
`CORS_ALLOWED_ORIGINS` del `.env` con ese dominio.

### Configurar el pipeline

1. Generar el par de claves SSH (`ssh-keygen -t ed25519 -C "prisma-deploy-staging" -f deploy_staging_key -N ""`)
   y copiar la clave pública al servidor, igual que en Dev remoto.
2. GitHub: repo → **Settings → Environments → New environment** → `staging`.
3. Dentro del environment agregar:

   | Tipo | Nombre | Valor |
   |---|---|---|
   | Secret | `STAGING_HOST` | IP o dominio del servidor de staging |
   | Secret | `STAGING_USER` | Usuario SSH |
   | Secret | `STAGING_SSH_KEY` | Clave **privada** completa |
   | Variable | `APP_URL` | URL pública para el smoke test, ej. `https://staging.prisma.example.uy` |

### Deploys siguientes

```bash
git tag v1.3.0-rc1
git push origin v1.3.0-rc1
```

`deploy.yml` conecta por SSH, hace `git fetch --tags` y `git checkout` del tag, y corre
`docker compose -f docker-compose.yml -f docker-compose.prod.yml pull` y `up -d --remove-orphans`
con `TAG` igual al tag publicado. Después corre el smoke test (`GET {APP_URL}/actuator/health`).

## 4) Producción — Docker Compose por SSH, por tag semver + aprobación

### Prerrequisitos

Los mismos que staging, en un servidor de producción propio, más 2 revisores obligatorios
(configurados más abajo). Dominio real de producción, ej. `prisma.example.uy`.

### Configurar el pipeline

1. Generar un par de claves SSH **distinto** al de staging (`deploy_prod_key`) y copiar la
   pública al servidor de producción.
2. GitHub: repo → **Settings → Environments → New environment** → `prod`.
3. Agregar los secrets `PROD_HOST`, `PROD_USER` y `PROD_SSH_KEY` y la variable `APP_URL` (ej.
   `https://<tu-dominio-real>`), igual que en staging.
4. **Bloquear el deploy hasta aprobación manual** — en la misma pantalla del environment `prod`:
   sección **Deployment protection rules** → tildar **Required reviewers** → buscar y agregar
   (mínimo) 2 usuarios/equipos de GitHub → **Save protection rules**. Sin este paso el tag
   dispara el deploy directo a producción sin pausa.

### Deploys siguientes

```bash
git tag v1.3.0        # semver, SIN sufijo -rc
git push origin v1.3.0
```

El job queda **pausado esperando aprobación**. Después de aprobar, `deploy.yml` hace el mismo
proceso que en staging contra el servidor de producción, usando los overrides de
`docker-compose.prod.yml` (menos puertos expuestos, límites de recursos, `ssl=on` en Postgres,
Keycloak en modo `start` en vez de `start-dev`), y corre el smoke test antes de dar el deploy por
exitoso.

Para reproducirlo a mano en el servidor:

```bash
cd /opt/prisma
git fetch --tags origin && git checkout v1.3.0
export TAG=v1.3.0
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile full up -d --remove-orphans
```

## Referencias

- Detalle de cada job del pipeline que dispara estos despliegues: [`CI-CD-Infra.md`](CI-CD-Infra.md)
- Análisis de viabilidad de una eventual migración a Kubernetes (no implementada): [`mcu-5.0/Kubernetes-Analisis-Viabilidad.md`](mcu-5.0/Kubernetes-Analisis-Viabilidad.md)
- HTTPS, certificado autofirmado y su renovación: [`Certificados-TLS.md`](Certificados-TLS.md)
- Setup de desarrollo local por SO, con más detalle que la sección 1 de acá: [`Dev-Config.md`](Dev-Config.md)
- Incidentes reales de operación y su causa raíz: [`RUNBOOK.md`](RUNBOOK.md)
