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

> **Contraseñas por ambiente.** En local, dev y staging el valor inicial de todas las contraseñas del
> `.env` es `Admin1234!` (así viene `.env.example`); donde el servicio exige 12 caracteres, como
> Portainer, es `Admin1234!!!`. En **producción** no se usan esos valores:
> `make prod-up` (o `make prod-secrets`) genera una contraseña aleatoria para cada una, la escribe
> en el `.env` del servidor y la muestra una sola vez en su salida — ver
> [Producción](#4-producción--docker-compose-por-ssh-por-tag-semver--aprobación).
>
> **Variables que se generan en producción**:
>
> | Variable | Para qué es |
> |---|---|
> | `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `SONAR_DB_PASSWORD` | Acceso a las bases de datos |
> | `JWT_SECRET` | Clave HMAC del sello de certificación de las evaluaciones aprobadas (salvo que se defina `CERTIFICATION_SECRET`) y del decodificador JWT alternativo que se usa sin Keycloak |
> | `AI_INTERNAL_API_KEY` | Sin esto, `backend-ai` queda alcanzable sin autenticación interna |
> | `KEYCLOAK_ADMIN_PASSWORD` | Admin de Keycloak (IAM completo) |
> | `RUSTFS_SECRET_KEY` | Acceso a las evidencias almacenadas |
> | `GF_SECURITY_ADMIN_PASSWORD`, `ELASTIC_PASSWORD`, `KIBANA_SYSTEM_PASSWORD` | Grafana, Elasticsearch y Kibana |
> | `PGADMIN_DEFAULT_PASSWORD` | Admin de pgAdmin |
> | `.secrets/portainer_admin_password` (archivo) | Admin de Portainer |
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

Ver [Requisitos previos](../../README.md#requisitos-previos) del README principal — en resumen:
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
`make up` funcione — las contraseñas iniciales de `.env.example` (`Admin1234!`) sirven tal cual para levantar la
stack en tu máquina (no está expuesta a nadie más). Igual, revisá:

- Si ya tenés algo corriendo en tu máquina en los puertos 5432, 6379, 8080, 8081, 8180, 9000-9003,
  3000, 9090, 5050 o 11434, cambiá el `*_PORT` correspondiente en el `.env` para evitar el
  conflicto (ver [Problemas comunes](#problemas-comunes) más abajo).
- `TZ` si tu zona horaria no es `America/Montevideo`.

```bash
# 3) Generar el certificado TLS autofirmado de nginx y la CA interna con los certificados de
#    cada servicio (una vez; sin esto nginx y los demas contenedores no arrancan -- ver
#    docs/operacion/Certificados-TLS.md)
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
[Puesta en marcha local](../../README.md#puesta-en-marcha-local-quickstart) del README principal.

### Problemas comunes

- **nginx no arranca / `cannot load certificate prisma.crt`**: te salteaste el paso 3
  (`make certs`). Corré `make certs` y `make restart-nginx`.
- **Un servicio no arranca por un volumen de `infra/tls/...` que no existe (Docker crea una carpeta en
  su lugar)**: falta la CA interna. Borrá esas carpetas vacías y corré `make internal-ca`.
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

1. **Contraseñas:** en dev y staging se dejan los valores iniciales de `.env.example`
   (`Admin1234!`); no hay que generar nada. Por eso estos ambientes no deben quedar expuestos a
   internet sin otra protección (VPN, firewall o Cloudflare): las contraseñas son conocidas. En
   **producción** se generan al azar al desplegar, ver
   [Contraseñas de producción](#contraseñas-de-producción).
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
4. Si vas a entrar por un dominio real (no una IP pelada), completá estas tres variables del
   `.env` con ese dominio — ejemplo para `dev.prisma.example.uy`:
   ```bash
   KEYCLOAK_TRUSTED_ISSUERS=https://dev.prisma.example.uy/auth/realms/prisma
   CORS_ALLOWED_ORIGINS=https://dev.prisma.example.uy
   PRISMA_APP_URL=https://dev.prisma.example.uy
   ```
   `PRISMA_APP_URL` es la dirección con la que los usuarios entran a PRISMA: la usan los correos de
   invitación para armar su enlace. Sin definirla, el enlace sale con `http://localhost:8080`.
   (ver el mismo paso explicado en detalle en [`Certificados-TLS.md`](Certificados-TLS.md)).

```bash
# Certificado TLS (si tenés un dominio real, mejor un cert de una CA pública -- este autofirmado
# es la opción rápida, igual que en local; ver docs/operacion/Certificados-TLS.md)
scripts/generate-nginx-cert.sh --cn <tu-host> --san <tu-host>,localhost,127.0.0.1

# CA interna y certificados de cada servicio (cifrado entre contenedores); el workflow de deploy la
# genera sola si falta infra/tls/ca.crt. Para un host propio: CERT_EXTRA_SAN=DNS:<tu-host>
scripts/generate-internal-ca.sh

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

**Política de recuperación de contraseña y bloqueo de cuenta** (RF-AUTH-05 y RF-AUTH-08): también hay
que aplicarla en **cada ambiente que ya exista**, por la misma razón (`--import-realm` solo corre
la primera vez). Deja el enlace de «¿Olvidaste tu contraseña?» con 30 minutos de vigencia y bloquea
la cuenta 15 minutos tras 10 intentos fallidos en 15 minutos:

```bash
make auth-policy          # idempotente; detalle en docs/operacion/RUNBOOK.md → "Bloqueo de cuenta"
make auth-policy-status   # muestra los valores vigentes contra los esperados
```

**Accedas por el host que accedas** (una IP, un dominio real, `localhost`) no hace falta tocar
nada en Keycloak: el client `prisma-frontend` trae `redirectUris`/`webOrigins` en `"*"` a propósito
(ver [`SECURITY.md`](../SECURITY.md#endurecimientos-recientes-para-que-no-se-repitan), ítem 10) —
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
servidor de staging: Docker + Compose, repo clonado en `/opt/prisma`, `.env` copiado de
`.env.example` (contraseñas iniciales `Admin1234!`), `GHCR_ORG`/login a GHCR y certificado TLS. Un dominio real apuntando
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

### Contraseñas de producción

Las contraseñas de producción no se escriben a mano ni se copian de `.env.example`: se generan al
desplegar.

```bash
make prod-up        # genera las que falten, las muestra y levanta el stack de producción
make prod-secrets   # solo genera y muestra, sin levantar nada
```

- Se genera un valor aleatorio (32 caracteres alfanuméricos; 64 para `JWT_SECRET`) para
  `POSTGRES_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `REDIS_PASSWORD`,
  `RUSTFS_SECRET_KEY`, `GF_SECURITY_ADMIN_PASSWORD`, `ELASTIC_PASSWORD`, `KIBANA_SYSTEM_PASSWORD`,
  `SONAR_DB_PASSWORD`, `PGADMIN_DEFAULT_PASSWORD`, `JWT_SECRET` y `AI_INTERNAL_API_KEY`, y la
  contraseña del administrador de Portainer (`.secrets/portainer_admin_password`).
- **Solo se reemplaza lo que sigue en su valor inicial** (`Admin1234!`, `Admin1234!!!`, vacío o `change_me*`). Lo que
  ya se cambió no se toca, así que repetir el comando no rota nada.
- La salida muestra una tabla con variable, servicio, usuario y contraseña. **Se muestra una sola
  vez**: guardala en un gestor de contraseñas. Los valores quedan en el `.env` (permisos 600) y se
  deja una copia del `.env` anterior (`.env.bak.<fecha>`).
- **Ambiente que ya tiene datos:** las contraseñas de las bases, Keycloak, Grafana, pgAdmin y RustFS
  quedan fijadas en los volúmenes al primer arranque. Si esos volúmenes ya existen, el comando no las
  cambia y avisa cuáles siguen con el valor inicial, para rotarlas a mano (ver
  [`RUNBOOK.md`](RUNBOOK.md)). `PROD_SECRETS_FORCE=1` las genera igual: usarlo solo en una
  instalación nueva.
- `KEYCLOAK_ADMIN_CLIENT_SECRET` no se genera solo, porque tiene que coincidir con el secreto del
  cliente `prisma-backend` dentro de Keycloak: el comando lo recuerda al final.
- En GitHub Actions los valores no se imprimen, para que no queden en los logs del pipeline.
- `make swarm-deploy` hace lo mismo antes de desplegar cuando el `.env` tiene `ENV=production`.

El script es `scripts/generate-prod-secrets.sh`.

## Ollama: configuración y precalentamiento

Hay **un solo Ollama por entorno** (servicio `ollama` de `docker-compose.yml` y de
`docker-stack.yml`). Se configura por variables de entorno, todas con valor por defecto y
sobrescribibles desde el `.env` (ver `.env.example`).

**Servicio `ollama`:**

| Variable | Por defecto | Para qué |
|---|---|---|
| `OLLAMA_KEEP_ALIVE` | `-1` | no descargar el modelo (sin esto se descarga a los 5 min y el siguiente pedido paga la carga en frío) |
| `OLLAMA_NUM_PARALLEL` | `2` | pedidos simultáneos |
| `OLLAMA_CONTEXT_LENGTH` | `16384` | contexto **por ranura**: la memoria crece con `CONTEXT_LENGTH × NUM_PARALLEL` |
| `OLLAMA_MAX_LOADED_MODELS` | `2` | LLM y embedding cargados a la vez |
| `OLLAMA_MAX_QUEUE` | `64` | cola interna de Ollama |

**Servicio `backend-ai`** (opciones que envía en cada pedido):

| Variable | Por defecto | Para qué |
|---|---|---|
| `OLLAMA_MODEL` | `llama3:8b` | modelo de LLM; cambiarlo es solo configuración |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | modelo de embedding. **Cambiarlo exige reindexar todas las colecciones** (`evidence-<org>` y la del marco): cambian las dimensiones (768 → 1024 con `bge-m3`) |
| `AI_NUM_CTX` | `16384` | `num_ctx` que se **pide** en cada pedido. **Tiene que valer lo mismo que `OLLAMA_CONTEXT_LENGTH`**; si no, Ollama recarga el modelo. Ojo: el modelo puede tener un máximo menor (`llama3:8b` carga con **8192**, `nomic-embed-text` con **2048**); ver «Contexto efectivo» más abajo |
| `AI_KEEP_ALIVE` | `-1` | `keep_alive` de cada pedido (igual que `OLLAMA_KEEP_ALIVE`) |
| `AI_NUM_PREDICT` | `1024` | tope de tokens de la respuesta. **Provisional**: definirlo con las guías reales |
| `AI_TEMPERATURE`, `AI_SEED`, `AI_NUM_THREAD` | sin definir | si están definidos se envían; si no, rige el valor del modelo. `AI_NUM_THREAD` depende del servidor (12 hilos dio 9,4 tok/s contra 6,7 por defecto en el VPS de pruebas) |
| `AI_CITATION_MIN_SCORE` | sin definir | puntaje mínimo de una cita de evidencia (relevancia de Chroma); lo que puntúa menos no se devuelve. **Sin definir = no se filtra**: el valor se calibra con el feedback real de `ai_citation_feedback` (el banco de pruebas calcula uno sugerido; `[POR VERIFICAR]`) |
| `AI_TRACE_RETENTION_DAYS` | `0` | días que se conservan las trazas del modo investigación; **0 = no se borran nunca**. `[POR VERIFICAR]` el plazo |
| `OLLAMA_NUM_PARALLEL` (en `backend-ai`) | `2` | solo se informa en la traza; el valor real lo fija el servicio `ollama` |
| `AI_CLASSIFY_READ_TIMEOUT` | `300s` | espera de `backend-core` a la preclasificación de evidencia global (`backend-ai` la cancela si se corta); aparte de `AI_READ_TIMEOUT` |
| `AI_CLASSIFY_MIN_SCORE` | sin definir | puntaje mínimo para sugerir un control al preclasificar evidencia global; **sin definir = se sugieren siempre los 3 mejores**. Se calibra con el feedback real de `ai_evidence_classification_feedback` (`[POR VERIFICAR]`) |
| `AI_THINK` | `false` | razonamiento; solo se aplica si el modelo declara la capability `thinking` (en `llama3`/`gemma3` Ollama responde 400) |
| `AI_WARMUP` | `true` | precalentar LLM y embedding al arrancar |
| `AI_WARMUP_CHECK_SECONDS` | `60` | cada cuánto revisar que los modelos sigan cargados y recargarlos si no (`0` = desactivado) |
| `AI_PROMPT_MAX_TOKENS` | `min(AI_NUM_CTX, contexto efectivo) − AI_NUM_PREDICT − 256` | límite del prompt: si lo supera, `backend-ai` responde `413 PROMPT_TOO_LARGE` sin llamar a Ollama (Ollama lo recortaría a la mitad sin avisar). Definido a mano solo puede **achicar** el límite, nunca pasar el que permite el contexto efectivo |

**Contexto efectivo.** `AI_NUM_CTX` es lo que se pide; Ollama carga cada modelo con `min(pedido,
máximo del modelo)`. `backend-ai` toma el contexto real de `/api/ps` (`context_length`) al arrancar y
cada vez que el vigilante revisa; si el modelo todavía no está cargado usa el máximo del modelo de
`/api/show`. El límite del prompt se calcula con ese valor, así un prompt de 9 000 tokens no pasa el
control con `llama3:8b` (8192) y no lo recorta Ollama en silencio. `GET /api/v1/runtime` lo muestra en
`context`: `requested_num_ctx`, `llm.effective` (y su `source`: `ps` o `show`), `llm.used_for_limit`,
`embedding.effective`, `embedding.input_max_tokens`, `prompt_max_tokens` y `mismatch` (`true` = el
modelo carga con menos contexto que el pedido; conviene bajar `AI_NUM_CTX` y `OLLAMA_CONTEXT_LENGTH`
al valor del modelo para no reservar memoria de más). En el log de arranque aparece un aviso si
`RAG_CHUNK_SIZE` no entra en el contexto del embedding. Con Ollama caído `/api/v1/runtime` no trae
`models.llm` ni `models.embedding` (solo las opciones configuradas y `ollama.reachable=false`).

**Precalentamiento.** Al arrancar, `backend-ai` envía un pedido vacío al LLM y uno de `embed` al
embedding, con las mismas opciones que usará la app, y reintenta cada 5 s (hasta ~10 min) mientras
Ollama no responda. Corre en segundo plano: no demora el arranque ni el `healthcheck`. Si
`backend-ai` corre con más de un proceso (`--workers 2` en el stack) cada uno lo hace; es idempotente.

**Verificar** (en el servidor, sin credenciales):

```bash
docker compose exec ollama ollama ps          # ambos modelos cargados, "UNTIL: Forever"
curl -s http://localhost:11434/api/ps          # lo mismo por API
# opciones efectivas, digest, versión de Ollama y estado del precalentamiento:
curl -s -H "X-Internal-Api-Key: $AI_INTERNAL_API_KEY" http://localhost:8000/api/v1/runtime
```

**Reinicio de Ollama.** Un vigilante revisa `/api/ps` cada `AI_WARMUP_CHECK_SECONDS` (60 s; `0` =
solo precalentar al arrancar) y, si LLM o embedding ya no están cargados (por ejemplo tras
`docker compose restart ollama`), los vuelve a precalentar. El contador `warmup.reloads` de
`GET /api/v1/runtime` dice cuántas veces lo hizo.

## Cola de trabajos de IA

`backend-core` ejecuta las guías de IA como trabajos asíncronos. Variables (todas opcionales):

| Variable | Por defecto | Para qué |
|---|---|---|
| `AI_JOBS_ENABLED` | `true` | enciende el trabajador (la API de encolado sigue funcionando) |
| `AI_JOBS_CONCURRENCY` | `2` | tope **global** (suma de réplicas) de trabajos corriendo contra Ollama |
| `AI_JOBS_MAX_PENDING_PER_ORG` | `20` | trabajos activos por organización (`429` si se supera) |
| `AI_JOBS_RESULT_TTL_DAYS` | `30` | cuánto se conserva un resultado |
| `AI_JOBS_MAX_ATTEMPTS` | `2` | intentos por trabajo |
| `AI_JOBS_RETRY_BACKOFF_SECONDS` | `30` | espera base entre intentos (se duplica) |
| `AI_JOBS_POLL_INTERVAL_MS` | `2000` | cada cuánto mira la cola cada réplica |
| `AI_JOBS_MAX_BATCH_SIZE` | `20` | controles por lote de guías (`400` si se supera) |
| `AI_JOBS_MAX_PENDING_BATCH_PER_ORG` | `60` | trabajos de lote activos por organización (`429`); aparte del tope de pedidos sueltos |
| `AI_GUIDE_FEEDBACK_DAYS` | `30` | a los cuántos días una guía mostrada y no usada cuenta como rechazada |
| `AI_GUIDE_READ_TIMEOUT` | `300s` | espera de `backend-core` a `backend-ai` por una guía. Es también el valor por defecto de `docker-compose.yml` y `docker-stack.yml` (antes 120 s: con 2 guías en paralelo varias lo superaban y se cortaban). Si se supera el trabajo falla con `AI_TIMEOUT` |

`docker-compose.yml` y `docker-stack.yml` pasan todas estas variables a `backend-core` con su valor por
defecto (`AI_CONNECT_TIMEOUT`, `AI_READ_TIMEOUT` y `AI_GUIDE_READ_TIMEOUT` también), así que alcanza con
definirlas en el `.env` para cambiarlas; no hace falta tocar el compose. Las `AI_*` de Ollama
(`AI_NUM_CTX`, `AI_NUM_PREDICT`, `AI_THINK`, etc.) van a `backend-ai`: ver la sección anterior.

**Errores de un trabajo fallido** (`error_code`): `AI_UNAVAILABLE` (no se pudo conectar con `backend-ai`
o respondió 502/503/504; se reintenta), `AI_TIMEOUT` (`backend-ai` aceptó el pedido pero no contestó
dentro de `AI_GUIDE_READ_TIMEOUT`; **no se reintenta**: repetirlo ocuparía a Ollama otros minutos con la
misma probabilidad de cortarse), `AI_ERROR` (5xx; se reintenta), `AI_REJECTED` (4xx), `PROMPT_TOO_LARGE`,
`WORKER_LOST` e `INTERNAL_ERROR`.

**Generación abandonada.** Cuando `backend-core` corta por timeout, `backend-ai` detecta que el cliente
cerró la conexión (revisa cada segundo) y cancela el pedido al LLM; al cerrarse la conexión con Ollama,
este aborta la generación y libera la ranura. En el log de `backend-ai` queda «Cliente desconectado: se
abortó la generación en curso» (respuesta interna `499`). Antes la generación seguía hasta el final
aunque nadie la leyera, y con la cola eso acumulaba trabajo inútil.

Es seguro con las 2 réplicas de `backend-core` del stack. Estado en vivo (solo lectura):

```sql
SELECT status, count(*) FROM prisma.ai_job GROUP BY status;
SELECT id, status, attempts, queue_wait_ms, run_ms, error_code FROM prisma.ai_job ORDER BY created_at DESC LIMIT 20;
```

## Indexación de evidencia y citas guardadas (hito 3)

La indexación de evidencia ya no ocurre dentro de la subida: es un trabajo `EVIDENCE_INDEX` de la cola de IA
(la misma de las guías, con el mismo tope global de `AI_JOBS_CONCURRENCY`, prioridad 5). Estado visible por
evidencia: `QUEUED`, `INDEXING`, `INDEXED`, `ERROR`, `NOT_INDEXABLE`. Si `backend-ai` falla, la cola reintenta
(`AI_JOBS_MAX_ATTEMPTS`, `AI_JOBS_RETRY_BACKOFF_SECONDS`) y, agotados los intentos, la evidencia queda en `ERROR`
con el motivo y se puede reintentar. Consulta de solo lectura:

```sql
SELECT index_status, count(*) FROM prisma.evidence GROUP BY 1;
SELECT file_name, index_status, index_error FROM prisma.evidence WHERE index_status = 'ERROR';
SELECT type, status, count(*) FROM prisma.ai_job GROUP BY 1, 2;
```

Si `backend-core` **se apaga con evidencia en cola**, los trabajos siguen en la base y se retoman al arrancar (los
`RUNNING` sin latido se reencolan). Con `AI_JOBS_ENABLED=false` la evidencia nueva queda `QUEUED` y no se indexa
hasta encender el trabajador. Para volver a indexar a mano una evidencia: `POST /api/evidence/{id}/index`.

Las citas de la IA se guardan en `evidence_citation_result` (por evaluación y control). Al entrar a auditoría se
encola un trabajo `CITATIONS_PRECOMPUTE`; al salir de auditoría se descartan las guardadas (el dictamen queda).
`backend-ai` devuelve un id estable por fragmento (`<evidenceId>::<posición>`): **reindexar el mismo archivo da los
mismos ids**, así las decisiones del auditor sobreviven a un reindexado.

## Diagnóstico de tiempos de un lote de guías (hallazgo 3.8)

El lote de 10 guías con `evaluationId` tardó casi el doble que el de la prueba B8. Revisado en el código:
**`evaluationId` solo sirve para saber de qué organización es el pedido** (`AiJobService.resolveOrganization`):
no entra en los parámetros del trabajo ni en el prompt, y el contexto del marco que se agrega al prompt
(los 3 fragmentos más parecidos del MCU) no depende de la evaluación. Es decir que el contexto de la
evaluación **no agrega tokens**. La causa más probable es otra: la prueba corrió mientras la preclasificación
de evidencia seguía calculando en `backend-ai` después del corte (hallazgo 3.3: varios pedidos de 1,5 a 4
minutos saturando las 18 vCPU), que se corrige con la cancelación al desconectarse. Sin arreglo de código
para este hallazgo; para confirmarlo hay que medir de nuevo sin otra actividad y comparar tokens y tiempos:

```sql
-- Tokens de entrada y de salida y tiempo de ejecución por lote (solo lectura)
SELECT batch_id,
       count(*)                                         AS guias,
       round(avg(run_ms))                               AS run_ms_prom,
       round(avg((metrics->>'prompt_eval_count')::int)) AS tokens_prompt_prom,
       round(avg((metrics->>'eval_count')::int))        AS tokens_salida_prom
FROM prisma.ai_job
WHERE type = 'GUIDE' AND status = 'SUCCEEDED'
GROUP BY batch_id ORDER BY max(finished_at) DESC LIMIT 10;
```

Si con el servidor quieto `tokens_prompt_prom` es parecido al del lote B8 y el tiempo vuelve a ser el de
B8, la diferencia era carga ajena. Si el prompt es más largo, comparar las descripciones de los controles
elegidos (hay controles con descripciones de varias líneas). `[POR VERIFICAR]` en el servidor.

## Modo investigación (hito 5)

Se enciende por organización desde el detalle de la organización (solo `PRISMA_ADMIN`): «Encender modo
investigación». Mientras esté encendido, la IA guarda la traza de cada guía (con semilla aleatoria por pedido),
de cada búsqueda de citas y de cada preclasificación; apagado no guarda nada. Quienes usan la organización ven
un aviso violeta arriba. Las trazas se miran con «Cómo razonó la IA» (solo admin) en el plan de mejora, las
citas y las observaciones, y se exportan en JSONL desde el mismo detalle. Consulta de solo lectura:

```sql
SELECT o.name, o.research_mode FROM prisma.organizations o WHERE o.research_mode;
SELECT kind, count(*), min(created_at), max(created_at) FROM prisma.ai_trace GROUP BY kind;
```

Sin plazo de retención definido (`AI_TRACE_RETENTION_DAYS=0`) las trazas se acumulan: en la guía, `prompt` +
`response` + `thinking` pesan unos pocos KB por traza. **Para apagar de golpe**: `UPDATE prisma.organizations SET
research_mode = false` (no borra lo guardado).

## Lote de guías y evidencia global (hito 4)

**Lote.** «Generar con IA para las seleccionadas» pide una guía por control como trabajos de **lote**
(prioridad 0): comparten el tope global de `AI_JOBS_CONCURRENCY` con todo lo demás y **un pedido interactivo
(una guía suelta, una indexación) pasa antes**. Con 2 en paralelo y ~60-80 s por guía, 10 guías tardan
~6 a 7 minutos en vaciarse. Consulta de solo lectura:

```sql
SELECT batch_id, status, count(*) FROM prisma.ai_job WHERE batch_id IS NOT NULL GROUP BY 1, 2;
SELECT type, priority, status, count(*) FROM prisma.ai_job GROUP BY 1, 2, 3 ORDER BY 1, 2;
```

**Evidencia global.** Los fragmentos de la evidencia subida sin control entran en las citas de cualquier
control de la evaluación (marcados `unassigned`). Al indexarse, un trabajo `EVIDENCE_CLASSIFY` compara cada
control candidato con la evidencia (una consulta de embedding por control: ~165 a ~670 por evidencia) y guarda
las 3 sugerencias:

```sql
SELECT e.file_name, c.code, s.score, s.rank, s.status
FROM prisma.evidence_control_suggestion s
JOIN prisma.evidence e ON e.id = s.evidence_id JOIN prisma.catalog_controls c ON c.id = s.control_id
ORDER BY e.file_name, s.rank;
```

## Después de recrear contenedores: nginx y Watchtower

**nginx cachea la dirección de los contenedores.** nginx resuelve `frontend`, `backend-core` y los demás
upstreams **una sola vez al arrancar**. Cuando un contenedor se recrea (`docker compose up -d` con una
imagen nueva, `--force-recreate`, un `build` seguido de `up`) recibe otra IP y nginx sigue enviando a la
vieja: todo el sitio responde `502 Bad Gateway` (en las pruebas del hito 1 duró unas dos horas). Después
de recrear cualquier contenedor que nginx use como upstream hay que **recargar nginx**:

```bash
docker exec prisma-nginx nginx -t && docker exec prisma-nginx nginx -s reload
```

Un `502` generalizado justo después de un despliegue casi siempre es esto (distinto del `502` de
`/auth/*` que se describe en [`RUNBOOK.md`](RUNBOOK.md), que es de Keycloak arrancando). El despliegue
automático de `dev` (`deploy.yml`) y los de staging y producción recrean contenedores: si el sitio
queda con `502` después de un deploy, recargar nginx es lo primero que hay que probar.

**Watchtower pisa las imágenes compiladas en local.** `watchtower` (perfil `management`) revisa cada
`WATCHTOWER_POLL_INTERVAL` segundos (300 por defecto) si hay una imagen nueva en el registro para los
contenedores con la label `com.centurylinklabs.watchtower.enable=true` (`backend-core`, `backend-ai` y
`frontend`) y los reemplaza. Si en un servidor de pruebas se compiló código local
(`docker compose build backend-ai`), Watchtower lo reemplaza por la imagen `:latest` del registro y el
servidor vuelve al código viejo sin aviso (pasó en las pruebas del hito 1, a los pocos minutos). Para
probar con código local:

```bash
docker stop prisma-watchtower          # antes de compilar; al terminar: docker start prisma-watchtower
```

o excluir esos contenedores quitando la label `com.centurylinklabs.watchtower.enable` en un override
local del compose. **No dejar Watchtower detenido** en dev, staging ni producción: ahí es lo que
mantiene actualizadas las imágenes.

## Versión y entorno

**Versión.** PRISMA tiene una sola versión (SemVer `MAJOR.MINOR.PATCH`, hoy `0.7.0`) compartida por la
API, el servicio de IA y la web. Vive en cuatro archivos que tienen que coincidir
(`apps/backend-core/pom.xml`, `apps/frontend/package.json`, `apps/backend-ai/app/version.py` y
`sonar-project.properties`); el CI lo verifica (`version-sync`). Para cambiarla:

```bash
scripts/version.sh show          # ver la de cada archivo
scripts/version.sh bump 0.7.0    # cambiar las cuatro a la vez
```

Mientras sea `0.x`, `MINOR` sube con cada entrega/sprint y `PATCH` con las correcciones. El tag que
despliega (`v0.7.0` a producción, `v0.7.0-rc1` a staging) tiene que ser el mismo número que
`scripts/version.sh show`.

**Entorno.** Cada servidor define qué entorno es con `ENV` en su `.env`
(`development` | `staging` | `production`); `docker-compose.yml` lo pasa a los servicios como
`APP_ENV` y `docker-compose.prod.yml` fuerza `production`. Sin la variable se asume `development`
(así, un olvido en producción es visible, no silencioso). Con eso:

- `GET /api/version` (público) y `GET /health` de `backend-ai` informan versión y entorno;
- la web muestra un cartel («Entorno de desarrollo», «Entorno de pruebas (staging)»), una franja de
  color arriba de todo y `[DEV]`/`[STAGING]` en el título de la pestaña; en producción no hay nada de
  eso, solo la versión en el menú lateral y en el login;
- si la web y la API tienen versiones distintas (despliegue a medias) la versión se muestra en
  ámbar con las dos.

El commit y la fecha de construcción los pasa el CI al construir las imágenes (`APP_COMMIT`,
`APP_BUILD_TIME`); aparecen al pasar el mouse sobre la versión y en `/api/version` solo con sesión
iniciada. En una construcción local: `GIT_SHA=$(git rev-parse --short HEAD) docker compose build`.

## Referencias

- Detalle de cada job del pipeline que dispara estos despliegues: [`CI-CD-Infra.md`](CI-CD-Infra.md)
- Publicación en internet con un VPS y Cloudflare Tunnel: [`PUBLICACION-CLOUDFLARE.md`](PUBLICACION-CLOUDFLARE.md)
- Alta disponibilidad con Docker Swarm en un servidor: [`RUNBOOK.md`](RUNBOOK.md#alta-disponibilidad-con-docker-swarm-un-solo-servidor)
- Análisis de viabilidad de una eventual migración a Kubernetes (no implementada): [`proyecto/Kubernetes-Analisis-Viabilidad.md`](../proyecto/Kubernetes-Analisis-Viabilidad.md)
- HTTPS, certificado autofirmado y su renovación: [`Certificados-TLS.md`](Certificados-TLS.md)
- Setup de desarrollo local por SO, con más detalle que la sección 1 de acá: [`Dev-Config.md`](../desarrollo/Dev-Config.md)
- Incidentes reales de operación y su causa raíz: [`RUNBOOK.md`](RUNBOOK.md)
