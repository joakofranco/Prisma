# 👨‍💻 DEV-SETUP — Guía por desarrollador

Esta guía cubre el setup local **por primera vez** para cada integrante del equipo, en Windows, macOS y Linux. Todos los comandos están pensados para que **cualquier miembro pueda correr toda la stack** localmente sin depender de un servidor central.

---

## 📦 1. Requisitos comunes

| Herramienta | Versión mínima | Instalación |
|---|---|---|
| **Docker Desktop** | 24.0+ | https://www.docker.com/products/docker-desktop |
| **Git** | 2.40+ | https://git-scm.com/downloads |
| **GitHub CLI (`gh`)** | 2.30+ | https://cli.github.com/ |
| **Make** | 4.0+ | Linux/Mac ya lo tiene; en Windows via `choco install make` o WSL |
| **mkcert** (opcional) | 1.4+ | Solo si querés un certificado local sin advertencia del navegador — `brew install mkcert` / `choco install mkcert` |

Recursos mínimos:
- **CPU:** 4 cores
- **RAM:** 16 GB (Ollama sólo carga bien con al menos 8 GB libres)
- **Disco:** 50 GB libres

Opcional (para desarrollo fuera de contenedores):

| Servicio | Herramienta |
|---|---|
| Frontend | **Node.js 24 LTS**, npm o pnpm |
| Backend Core | **JDK 25**, Maven 3.9 (el repo no trae el wrapper `mvnw`) |
| Backend AI | **Python 3.13** (la versión del contenedor), pip + venv |

---

## 🔑 2. Configuración inicial de Git y SSH

```bash
# Configurar tu identidad
git config --global user.name "Nombre Apellido"
git config --global user.email "nombre.apellido@estudiantes.utec.edu.uy"

# Firma de commits con GPG o SSH (recomendado)
git config --global commit.gpgsign true
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub

# Autenticarte contra GitHub
gh auth login
```

---

## 🌿 3. Clonar el repo y crear tu rama de trabajo

> **No existen ramas personales `dev/<nombre>`** — eso fue el plan original del anteproyecto
> (`docs/proyecto/Proyecto.md`). En la práctica el repo usa **ramas cortas por PR sobre `dev`**, la rama de
> integración (ver [`README.md` → Estrategia de branching](../../README.md#estrategia-de-branching)):
> se rama desde `dev` y se abre el PR contra `dev`; `dev` se promueve a `main`.

```bash
git clone https://github.com/joakofranco/Prisma.git
cd Prisma
git checkout dev
git pull
```

### Ciclo de trabajo diario

```bash
# 1) Partir siempre de dev actualizado
git checkout dev
git pull origin dev

# 2) Crear una rama corta (feat/, fix/ o chore/), nombre descriptivo
git checkout -b fix/lock-evidence-during-audit

# 3) Trabajar, commit (Conventional Commits), push
git add .
git commit -m "fix(core): bloquea evidencia mientras la evaluación está en auditoría"
git push -u origin fix/lock-evidence-during-audit

# 4) Abrir PR contra dev
gh pr create --base dev --title "fix(core): bloquea evidencia durante auditoría" \
  --body-file .github/PULL_REQUEST_TEMPLATE/pull_request_template.md

# 5) Con CI verde (o sin CI configurado localmente, ver docs/desarrollo/Testing.md para correr todo a mano)
#    y aprobación de CODEOWNERS si aplica, mergear a dev
gh pr merge --merge   # o --squash, según el tamaño/atomicidad del PR
```

Ramas cortas (< 1 semana idealmente), un PR = una responsabilidad. Ver
[`CONTRIBUTING.md`](../CONTRIBUTING.md) para la convención de nombres/commits completa y el checklist
de revisión.

---

## ⚙️ 4. Preparar el entorno local

### 4.1 Variables de entorno

```bash
cp .env.example .env
# Editá `.env` para poner contraseñas locales (nunca commitear .env)
```

### 4.2 Certificados HTTPS locales

```bash
make certs    # certificado autofirmado de nginx (5 años) + CA interna y certificados por servicio
```

Genera `infra/nginx/certs/prisma.crt`/`prisma.key` y la CA interna en `infra/tls/` (gitignored, uno
por máquina). Sin la CA interna los contenedores no arrancan. El navegador va
a mostrar la advertencia de "conexión no privada" la primera vez (es autofirmado, no lo emite una
CA pública) — se acepta una sola vez. Procedimiento completo, renovación al expirar y la
alternativa con `mkcert` (certificado confiable para el navegador, pero limitado a ~825 días de
validez) en [`Certificados-TLS.md`](../operacion/Certificados-TLS.md).

### 4.3 (Opcional) Agregar `prisma.local` a `/etc/hosts`

Keycloak usa hostname dinámico (arma sus URLs a partir del host real de cada request, ver
[`Certificados-TLS.md`](../operacion/Certificados-TLS.md#3-hostname-dinámico-de-keycloak-el-login-funciona-por-cualquier-host-configurado)),
así que **el login funciona sin este paso**: `https://localhost` alcanza para todo, login
incluido. Si preferís usar `prisma.local` de forma estable (es el CN del certificado por defecto):

```bash
# Linux/Mac
echo "127.0.0.1 prisma.local" | sudo tee -a /etc/hosts

# Windows (PowerShell como admin)
Add-Content -Path C:\Windows\System32\drivers\etc\hosts -Value "127.0.0.1 prisma.local"
```

---

## 🚀 5. Levantar la stack

### Opción rápida (sólo la aplicación)

```bash
make up-app                # frontend + backends + BD + Redis + Ollama + ClamAV
```

### Opción media (con IAM y almacenamiento)

```bash
make up-core               # + Keycloak + RustFS + nginx + fail2ban
```

### Opción completa (todo, incluye monitoreo y calidad)

```bash
make up                    # incluye monitoreo y alertas, Elasticsearch/Kibana, SonarQube, Portainer, pgAdmin y respaldos
```

### Descargar el modelo Llama 3 (primera vez, ~4.7 GB)

```bash
make pull-llm
```

### Catálogo MCU 5.0

No hay que cargarlo a mano: el catálogo MCU 5.0, el usuario administrador inicial y los perfiles comunitarios los aplican las migraciones de Flyway al arrancar `backend-core`. No hay datos demo de organizaciones ni evaluaciones; se crean desde la aplicación.

---

## 🔎 6. Verificar que todo funcione

```bash
make health
make ps
```

Abrí estas URLs en el navegador (usuario/contraseña son los que hayas puesto en tu `.env` al copiar
`.env.example` — las contraseñas iniciales de ahí (`Admin1234!`) sirven tal cual para desarrollo local, ver la nota en el
propio `.env.example`; **excepción:** `KEYCLOAK_ADMIN_CLIENT_SECRET` debe coincidir EXACTO con
`CHANGE_ME_IN_PRODUCTION`, el valor que trae hardcodeado `infra/keycloak/realm-prisma.json` —
Keycloak no lee variables de entorno de ese JSON, así que rotarlo solo en `.env` rompe el alta de
usuarios nuevos, ver [`SECURITY.md`](../SECURITY.md)):

- 🌐 **PRISMA:** https://prisma.local (o https://localhost — el login funciona en ambos, ver [`Certificados-TLS.md`](../operacion/Certificados-TLS.md#3-hostname-dinámico-de-keycloak-el-login-funciona-por-cualquier-host-configurado); `http://localhost:8080` sirve la SPA directo desde el contenedor `frontend`, sin pasar por nginx/TLS)
  - Usuario semilla: `admin@prisma.local` / `Admin1234!` (temporal — Keycloak pide cambiarla al primer login)
- 📘 **API docs (backend-core):** http://localhost:8081/swagger-ui
- 🤖 **API docs (backend-ai):** http://localhost:8000/docs
- 🔐 **Keycloak admin:** http://localhost:8180/auth (usuario `admin`, contraseña = `KEYCLOAK_ADMIN_PASSWORD` de tu `.env`)
- 🗂️ **RustFS Console:** http://localhost:9002 (usuario = `RUSTFS_ACCESS_KEY`, contraseña = `RUSTFS_SECRET_KEY`)
- 🐳 **Portainer:** http://localhost:9000 (`make up` solamente — usuario `admin`, contraseña en `.secrets/portainer_admin_password`, ver [`RUNBOOK.md`](../operacion/RUNBOOK.md#portainer-administrador-inicial))
- ✉️ **Mailpit** (buzón de pruebas de las alertas): http://localhost:8025
- 📊 **Grafana:** http://localhost:3000 (usuario `admin`, contraseña = `GF_SECURITY_ADMIN_PASSWORD`)
- 🔬 **SonarQube:** http://localhost:9003 (usuario/contraseña por defecto `admin`/`admin`, pide cambiarla al primer login)
- 🔎 **Kibana** (perfil `elastic`): http://localhost:5601 (usuario `elastic`, contraseña = `ELASTIC_PASSWORD`)
- 🗄️ **pgAdmin:** http://localhost:5050 (`admin@prisma.uy` / `admin` por defecto — ver `scripts/db/pgadmin-servers.json` para los servers preconfigurados)

> Los puertos de arriba son los que trae `.env.example` (`*_PORT`); si alguno choca con algo que
> ya tenés corriendo, cambiá esa variable en tu `.env` — ver la sección de problemas frecuentes más
> abajo.

---

## 🧪 7. Ejecutar tests

```bash
make test              # Todos (frontend + backend-core + backend-ai)
make test-frontend     # Sólo frontend (Vitest)
make test-core         # Sólo backend-core (JUnit + Testcontainers -- necesita Docker corriendo)
make test-ai           # Sólo backend-ai (pytest)
make test-e2e          # E2E + accesibilidad con Playwright (necesita Keycloak + backend-core arriba)
```

Ver [`Testing.md`](Testing.md) para cómo correr cada suite fuera de Docker (con hot-reload), qué
cubre cada una, el gate de cobertura real de CI, y la metodología de QA manual/exploratoria contra
el stack real que complementa a los tests automatizados.

---

## 🐛 8. Debug remoto

### Backend Core (Java, puerto 5005)

Configurá tu IDE (IntelliJ, VS Code) con un debugger remoto apuntando a `localhost:5005`. En `docker-compose.dev.yml` el JVM ya se levanta con el flag `-Xrunjdwp:address=*:5005`.

### Backend AI (Python)

El stack no trae un depurador remoto configurado para `backend-ai`. Para depurarlo, corré el servicio
fuera de Docker desde tu IDE (`uvicorn app.main:app --reload`, ver [`Testing.md`](Testing.md)).

### Frontend

Los sourcemaps de Vite ya funcionan directamente en Chrome DevTools.

---

## ⚡ 9. Comandos frecuentes

```bash
make logs-core          # Sólo logs del backend-core
make shell-db           # Consola psql
make shell-core         # Shell dentro del contenedor Java
make sonar              # Análisis SonarQube local
make security-scan      # Trivy local
make backup             # Backup Postgres + evidencias (RNF-13, ver RUNBOOK.md)
make nuke               # ⚠️ Reset TOTAL de datos locales
```

---

## 🆘 10. Problemas frecuentes

<details>
<summary><b>"Cannot connect to Docker daemon"</b></summary>

Verificar que Docker Desktop esté corriendo. En Linux: `sudo systemctl start docker`.

</details>

<details>
<summary><b>Ollama tarda mucho en descargar el modelo</b></summary>

El modelo Llama 3 pesa ~4.7 GB. Es normal que tarde 5-15 minutos la primera vez. Se cachea en el volumen `prisma-ollama-data`.

</details>

<details>
<summary><b>Puerto 8080 en uso</b></summary>

Cambiá el puerto en tu `.env`: `FRONTEND_PORT=8090`. Los demás servicios también se pueden reasignar de la misma manera.

**Ojo con este puerto en particular:** si cambiás `FRONTEND_PORT`, tenés que agregar también
`http://localhost:<tu-puerto-nuevo>/auth/realms/prisma` a `KEYCLOAK_TRUSTED_ISSUERS` en tu `.env` y
recrear `backend-core` (`docker compose up -d --force-recreate backend-core`). Si no, el login
completa pero **todas las llamadas a la API dan 401 en loop** (el dashboard se queda cargando para
siempre): el token que Keycloak emite trae como `iss` justo esa URL con el puerto nuevo, y si no
está en la lista de orígenes confiables, backend-core lo rechaza aunque la firma sea válida. Mismo
mecanismo que explica [`Certificados-TLS.md` §4](../operacion/Certificados-TLS.md#4-referencia-rápida--variables-involucradas).

</details>

<details>
<summary><b>El login funciona pero el dashboard queda cargando / todo da 401</b></summary>

Es el mismo problema del punto anterior, pero puede pasar sin haber tocado ningún puerto: revisá
que `KEYCLOAK_TRUSTED_ISSUERS` en tu `.env` incluya el origen exacto (esquema + host + puerto) por
el que estás entrando a PRISMA. Confirmalo decodificando el JWT (por ejemplo en
[jwt.io](https://jwt.io)) y comparando el claim `iss` contra la lista — tienen que matchear
carácter por carácter.

</details>

<details>
<summary><b>Los tests con Testcontainers fallan</b></summary>

Verificá que Docker esté corriendo y tu usuario esté en el grupo `docker`: `sudo usermod -aG docker $USER` (relogueate después).

</details>

<details>
<summary><b>Certificado TLS no confiable en el navegador</b></summary>

Es esperable con el certificado autofirmado por defecto (`scripts/generate-nginx-cert.sh`) — el
navegador no tiene forma de verificar quién lo emitió. Aceptá la advertencia una vez (queda
recordado por origen). Si preferís que el navegador no muestre ninguna advertencia, usá `mkcert`
en su lugar (ver [`Certificados-TLS.md`](../operacion/Certificados-TLS.md#alternativa-mkcert-sin-advertencia-del-navegador)):
`mkcert -install` instala su CA local, después generás el certificado con `mkcert` en vez del
script.

</details>
