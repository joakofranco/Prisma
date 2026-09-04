# 🛡️ PRISMA — Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías

> Monorepo oficial del proyecto de grado — Licenciatura en Tecnologías de la Información (UTEC)
> Basado en el **Marco de Ciberseguridad de AGESIC v5.0 (MCU 5.0)**.

[![CI/CD](https://github.com/luisaraujo-utec/proyecto_prisma/actions/workflows/ci.yml/badge.svg)](https://github.com/luisaraujo-utec/proyecto_prisma/actions)
[![License](https://img.shields.io/badge/license-Academic-blue.svg)](LICENSE)

> El análisis de código estático corre sobre una instancia propia de **SonarQube CE** (contenedor
> `sonarqube`, ver [Herramientas de infraestructura](#herramientas-de-infraestructura-desplegadas)),
> no sobre SonarCloud: el job `sonarqube.yml` en CI es opcional y solo se ejecuta si el repositorio
> tiene configurada la variable `SONAR_HOST_URL` apuntando a un servidor accesible.

---

## 👥 Equipo

| Integrante | Rol (según el anteproyecto, `docs/Proyecto.md`) |
|---|---|
| **Fernando Araujo** | Arquitecto de Software y Desarrollador Full Stack |
| **Federico De Armas** | Analista Funcional y QA Tester |
| **Joaquín Franco** | PM y QA Tester |

El historial de commits del repositorio es, a la fecha, enteramente de Fernando Araujo — no existen
ramas personales por integrante (`dev/<nombre>`); ver [Estrategia de branching](#estrategia-de-branching)
para el flujo real usado.

---

## 📚 Índice

1. [¿Qué hace PRISMA?](#qué-hace-prisma)
2. [Arquitectura general](#arquitectura-general)
3. [Estructura del monorepo](#estructura-del-monorepo)
4. [Stack tecnológico](#stack-tecnológico)
5. [Requisitos previos](#requisitos-previos)
6. [Puesta en marcha local](#puesta-en-marcha-local-quickstart)
7. [Ambientes: de desarrollo local a producción](#ambientes-de-desarrollo-local-a-producción)
8. [Estrategia de branching](#estrategia-de-branching)
9. [Flujo CI/CD](#flujo-cicd)
10. [Herramientas de infraestructura](#herramientas-de-infraestructura-desplegadas)
11. [Comandos útiles](#comandos-útiles)
12. [Documentación adicional](#documentación-adicional)

---

## ¿Qué hace PRISMA?

Plataforma web para que una organización autoevalúe su madurez en ciberseguridad contra el
**Marco de Ciberseguridad de AGESIC v5.0**, con seguimiento de auditoría y plan de mejora. Roles
del sistema (multi-tenant, aislado por organización): `PRISMA_ADMIN`, `ORG_RESPONSIBLE`,
`INTERNAL_EVALUATOR`, `AUDITOR` y `VIEWER`.

Funcionalidades operativas:

- **Catálogo MCU 5.0** cargado desde el marco real de AGESIC (funciones → categorías →
  subcategorías → requisitos → controles), con **perfiles comunitarios** (subconjuntos curados del
  catálogo) para acotar una evaluación. Un `PRISMA_ADMIN` puede además dar de alta **nuevas
  versiones de catálogo completas** desde Configuración → Catálogo, importando un JSON o un CSV
  (con plantillas de ejemplo descargables) o armándolas a mano con un editor de árbol; la versión
  nueva queda disponible de inmediato para evaluar y auditar.
- **Evaluaciones guiadas por control**, con cálculo automático de madurez (modelo acumulativo,
  niveles 1-4) y de brechas por función/categoría/subcategoría, y un **ciclo de vida** con estados
  Borrador → En Curso → Lista para Auditoría → En Auditoría → Aprobada / Devuelta → Archivada
  (con stepper visual y glosario de estados en el detalle de cada evaluación).
- **Evidencias**: carga de documentos asociados a un control, almacenados en MinIO (S3-compatible)
  con URLs de descarga firmadas.
- **Asistente de IA sobre evidencia (RAG)**: dado un control, busca en la evidencia ya cargada por
  esa organización y cita el fragmento relevante indicando documento, página y sección (heurística
  de encabezados sobre PDF/DOCX/TXT), vía FastAPI + LangChain + Ollama (modelo local, sin salir
  de la infraestructura propia).
- **Auditoría**: observaciones de auditoría por control, y auditores **acotados explícitamente** a
  las organizaciones que un `PRISMA_ADMIN` les asigna (no ven ni auditan el resto).
- **Planes de mejora**: sugerencias automáticas generadas a partir de las brechas detectadas, más
  seguimiento manual de acciones, responsables y vencimientos.
- **Reportes** ejecutivos/técnicos exportables (PDF y Excel) y **dashboard** con indicadores
  agregados por organización (o por el conjunto de organizaciones que audita un `AUDITOR`).
- **Cuenta propia**: cualquier usuario puede cambiar su contraseña desde "Mi Cuenta", verificando
  la actual contra Keycloak antes de aceptar la nueva.
- **Administración**: gestión de organizaciones/usuarios, configuración global de SMTP (para el
  email de "¿Olvidaste tu contraseña?" de Keycloak), bitácora de auditoría de acciones sensibles.
- **Seguridad**: autenticación/SSO vía Keycloak (JWT), HTTPS same-origin forzado, aislamiento
  multi-tenant validado en cada operación del backend.

Explícitamente **fuera de alcance** (ver `docs/Proyecto.md`): PRISMA no reemplaza el trabajo
profesional de un auditor, no realiza auditorías formales ni emite certificaciones oficiales de
madurez.

---

## Arquitectura general

PRISMA se implementa como una **arquitectura de microservicios contenerizada**, con separación clara de responsabilidades:

```
                          ┌──────────────────────────┐
                          │   Nginx (Reverse Proxy)  │
                          │      + HTTPS/TLS         │
                          └───────────┬──────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
      ┌───────────────┐     ┌──────────────────┐     ┌──────────────────┐
      │  Vue 3 + Vite │     │  Spring Boot API │     │   FastAPI + RAG  │
      │   (Frontend)  │◄───►│   (Core Backend) │◄───►│  (IA / Ollama)   │
      └───────────────┘     └────────┬─────────┘     └─────────┬────────┘
                                     │                          │
                            ┌────────┴─────────┐                ▼
                            ▼                  ▼         ┌──────────────┐
                    ┌──────────────┐  ┌──────────────┐   │    Ollama    │
                    │  PostgreSQL  │  │    MinIO     │   │ (Llama 3 LLM │
                    │  (relacional,│  │ (Evidencias  │   │  local para  │
                    │   Flyway)    │  │  S3 API)     │   │     RAG)     │
                    └──────────────┘  └──────────────┘   └──────────────┘
                    ┌──────────────┐  ┌──────────────┐   ┌──────────────┐
                    │  Keycloak    │  │  SonarQube   │   │    Redis     │
                    │ (SSO, RBAC,  │  │ (self-hosted,│   │ (declarado en│
                    │  JWT)        │  │  calidad)    │   │  el stack;   │
                    └──────────────┘  └──────────────┘   │  sin uso     │
                    ┌──────────────┐  ┌──────────────┐   │  activo hoy) │
                    │  Portainer   │  │  Watchtower  │   └──────────────┘
                    │ (Gestión de  │  │ (Auto-update │   ┌──────────────┐
                    │ contenedores)│  │  contenedor) │   │  Prometheus  │
                    └──────────────┘  └──────────────┘   │  + Grafana   │
                                                          │  + Loki      │
                                                          │ (monitoreo)  │
                                                          └──────────────┘
```

---

## Estructura del monorepo

```
prisma/
├── apps/                          # Código fuente de las aplicaciones
│   ├── frontend/                  # Vue 3 + Vite + Tailwind + Pinia
│   ├── backend-core/              # Spring Boot 3.5 (JDK 25) — API principal
│   └── backend-ai/                # FastAPI + LangChain + Ollama (RAG)
│
├── infra/                         # Infraestructura como código (IaC)
│   ├── docker/                    # Dockerfiles multi-stage por servicio
│   ├── kubernetes/                # Manifiestos K8s (ver docs/Kubernetes-Minikube-Portainer.md)
│   ├── keycloak/                  # Realm import (realm-prisma.json) + configuración SSO
│   ├── monitoring/                # Dashboards de Grafana + reglas/config de Prometheus y Loki
│   └── nginx/                     # Reverse proxy + certificado TLS local
│   # (terraform/ todavía no existe: provisioning cloud queda como trabajo futuro, ver docs/Proyecto.md)
│
├── .github/
│   ├── workflows/                 # Pipelines CI/CD (GitHub Actions)
│   ├── ISSUE_TEMPLATE/
│   ├── PULL_REQUEST_TEMPLATE/
│   ├── CODEOWNERS                 # Reviewers obligatorios
│   └── branch-protection.yml      # Reglas de protección de ramas
│
├── docs/                          # Documentación técnica y funcional
├── sonar-project.properties       # Config de SonarQube/SonarScanner (raíz del repo)
│
├── docker-compose.yml             # Orquestación completa (perfiles: app/security/observability/quality/management/proxy/full)
├── docker-compose.dev.yml         # Overrides de desarrollo
├── docker-compose.prod.yml        # Overrides producción-like
├── .env.example                   # Plantilla de variables de entorno
├── Makefile                       # Atajos: `make up`, `make test`, etc.
└── README.md                      # (este archivo)
```

---

## Stack tecnológico

| Capa | Tecnología | Notas |
|---|---|---|
| **Frontend** | Vue 3 + Vite 6 + Tailwind 4 + Pinia + Vue Router + Chart.js | SPA con dashboards, formularios y gráficos (`chart.js`/`vue-chartjs`). |
| **Backend Core** | Spring Boot 3.5.16, JDK 25 (bytecode target 21) + JPA/Hibernate + Flyway | APIs REST, seguridad, RBAC, cálculo de madurez. |
| **Backend IA** | FastAPI + LangChain + Ollama (Llama 3) + ChromaDB | RAG sobre evidencia cargada y sobre la documentación del MCU 5.0. |
| **BD Relacional** | PostgreSQL 17 | Esquema versionado con Flyway (`apps/backend-core/src/main/resources/db/migration`). |
| **Caché / sesiones** | Redis 7 | Dependencia declarada en el stack; sin uso activo todavía en el código (no hay sesiones ni rate-limiting implementados sobre Redis a la fecha). |
| **Almacenamiento** | MinIO (S3-compatible) | Evidencias con URLs firmadas. |
| **SSO / IAM** | Keycloak 26 | Servido bajo `/auth` (`KC_HTTP_RELATIVE_PATH`), JWT, RBAC. |
| **Reverse Proxy** | Nginx 1.30 | HTTPS con certificado local (ver `docs/Certificados-TLS.md`). |
| **Monitoreo** | Prometheus + Grafana + Loki + Promtail + cAdvisor + node/postgres/redis-exporter | Métricas + logs centralizados. |
| **Logs centralizados (alt.)** | Elasticsearch + Kibana + Filebeat (perfil `elastic`) | Segunda vía de logs para búsqueda libre entre seguridad/BD/apps — no reemplaza a Loki, corre aparte. Ver `docs/CI-CD-Infra.md`. |
| **Monitoreo de frontend** | LogRocket (SaaS, opcional) | Session replay, errores JS y performance real de usuario. Apagado por defecto (requiere cuenta propia y `VITE_LOGROCKET_APP_ID`), ver `apps/frontend/src/plugins/logrocket.ts`. |
| **Calidad de código** | SonarQube CE (self-hosted) | SAST + cobertura + code smells; ver nota sobre `sonarqube.yml` más arriba. |
| **Seguridad SCA** | Trivy (FS + imágenes) + OWASP Dependency-Check + Gitleaks + CodeQL + Snyk (opcional) | Ver [Flujo CI/CD](#flujo-cicd). |
| **Gestión de contenedores** | Portainer CE | UI web para los contenedores de Docker Compose. |
| **Auto-update** | Watchtower | Refresco de imágenes locales cuando cambia el tag. |
| **Orquestación** | Docker Compose (dev/local) + manifiestos Kubernetes (`infra/kubernetes/`) | Ver `docs/Kubernetes-Minikube-Portainer.md`. |
| **CI/CD** | GitHub Actions | Ver [Flujo CI/CD](#flujo-cicd). |

---

## Requisitos previos

Cada desarrollador debe tener instalado localmente:

- **Docker Desktop** ≥ 24.0 (incluye Docker Compose v2)
- **Git** ≥ 2.40
- **Make** (opcional, para los atajos del `Makefile`)
- **Node.js 24** (solo si desea correr el frontend fuera de Docker — misma versión que usan el Dockerfile y CI)
- **JDK 25** + **Maven 3.9** (solo si desea correr backend-core fuera de Docker)
- **Python 3.13** + **pip** (misma versión que corre en el contenedor; CI además valida contra Python 3.14)
- ≥ 16 GB de RAM y ≥ 50 GB de disco libres (Ollama + toda la stack consume recursos)

---

## Puesta en marcha local (Quickstart)

```bash
# 1) Clonar el repo
git clone https://github.com/luisaraujo-utec/proyecto_prisma.git
cd proyecto_prisma

# 2) Copiar variables de entorno y ajustar contraseñas/puertos locales
cp .env.example .env

# 3) Generar el certificado TLS autofirmado de nginx (una vez; sin esto el contenedor
#    de nginx no arranca -- ver docs/Certificados-TLS.md)
make certs

# 4) Levantar TODA la stack (frontend, backends, BD, IAM, monitoreo, etc.)
make up
# equivalente a: docker compose --profile full up -d --build --remove-orphans

# 5) Descargar el modelo Llama 3 (primera vez, ~4.7 GB)
make pull-llm

# 6) Verificar que todo esté OK
make health
```

No hace falta un paso manual de "seed": el catálogo MCU 5.0 y el usuario administrador semilla
(`admin@prisma.local`) se cargan solos vía migraciones Flyway al arrancar `backend-core`, y el
índice RAG de `backend-ai` se arma solo al arrancar ese servicio.

Una vez arriba, las URLs por defecto son (los puertos se pueden cambiar en `.env`):

| Servicio | URL | Credenciales por defecto |
|---|---|---|
| **Frontend PRISMA** | http://localhost:8080 | `admin@prisma.local` / `Admin1234!` (temporal: Keycloak pide cambiarla al primer login) |
| **Backend Core (Swagger)** | http://localhost:8081/swagger-ui | — |
| **Backend AI (docs)** | http://localhost:8000/docs | — |
| **Keycloak** | http://localhost:8180/auth | `admin` / valor de `KEYCLOAK_ADMIN_PASSWORD` en tu `.env` |
| **Portainer** | http://localhost:9000 | (setup inicial en la UI) |
| **SonarQube** | http://localhost:9003 | `admin` / `admin` (Sonar pide cambiarla al primer login) |
| **Grafana** | http://localhost:3000 | `admin` / valor de `GF_SECURITY_ADMIN_PASSWORD` en tu `.env` |
| **Prometheus** | http://localhost:9090 | — |
| **Kibana** | http://localhost:5601 | `elastic` / valor de `ELASTIC_PASSWORD` en tu `.env` |
| **MinIO Console** | http://localhost:9002 | `minioadmin` / valor de `MINIO_ROOT_PASSWORD` en tu `.env` |
| **pgAdmin** | http://localhost:5050 | `admin@prisma.uy` / `admin` |
| **Ollama API** | http://localhost:11434 | — |
| **Nginx (HTTPS)** | https://localhost | certificado local autofirmado, ver `docs/Certificados-TLS.md` |

---

## Ambientes: de desarrollo local a producción

Hay cuatro niveles, cada uno con un mecanismo de despliegue distinto:

| Ambiente | Cómo se levanta | Trigger | Infraestructura |
|---|---|---|---|
| **Local** | `make up` (`docker compose --profile full up -d --build`) | Manual, en tu máquina | Docker Compose, todo en un host |
| **Dev remoto** | Automático | `push`/merge a la rama `dev` | Servidor único, Docker Compose (vía SSH) |
| **Staging** | Automático | Tag `v*.*.*-rc*` (ej. `v1.2.0-rc1`) | Kubernetes (Kustomize), namespace `prisma-staging` |
| **Producción** | Automático, con aprobación | Tag `v*.*.*` (ej. `v1.2.0`) | Kubernetes (Kustomize), namespace `prisma-prod` |

Los tres ambientes remotos (dev, staging, prod) los despliega el workflow **`deploy.yml`** una vez
configurados — no hace falta correr nada a mano en el día a día. Pero cada uno necesita una puesta
a punto manual la primera vez (servidor/cluster + secrets de GitHub, placeholders `CHANGE_ME_*` a
rotar, configuración de Keycloak). La guía paso a paso de **cómo instalar cada uno desde cero**,
pensada para alguien que nunca lo hizo, vive en
[`docs/Ambientes-Despliegue.md`](docs/Ambientes-Despliegue.md); el detalle de cada job del workflow
que los dispara está en [`docs/CI-CD-Infra.md`](docs/CI-CD-Infra.md) (sección "5. `deploy.yml`" y
"Parte 3 — Deployment a Kubernetes").

---

## Estrategia de branching

En la práctica, el repositorio usa **trunk-based con ramas cortas por PR**, no un GitFlow con ramas
personales por integrante:

- **`main`** — protegida, historial de release.
- **`dev`** — rama de integración.
- **`fix/<descripción>`** / **`feature/<descripción>`** — ramas de corta duración creadas desde
  `main`, mergeadas de vuelta a `main` vía Pull Request (ver `.github/CODEOWNERS` y
  `.github/branch-protection.yml` para las reglas de protección reales).

No existen (todavía) ramas personales `dev/<nombre>` por integrante ni ramas `release/*`.

### Convención de nombres

- `feature/RF-EVA-09-workflow-auditoria`
- `fix/PRISMA-123-fix-jwt-refresh`
- `hotfix/prod-2026-08-critical-idor`

### Convención de commits

Adoptamos **[Conventional Commits](https://www.conventionalcommits.org/)**, como puede verse en el historial real del repositorio:

```
feat(admin): configuración global de email SMTP para recuperar contraseña
fix(auth): hostname dinámico en Keycloak -- el login rompía al entrar por un host distinto al configurado
fix(ci): repara el pipeline roto por incompatibilidades con JDK 25
test(backend-core): cubre uy.edu.prisma.infrastructure para cumplir el gate de cobertura JaCoCo
```

---

## Flujo CI/CD

Cada `push` y cada `pull request` dispara pipelines automáticos definidos en `.github/workflows/`.

### Pipelines

| Workflow | Trigger | Qué hace |
|---|---|---|
| **`ci.yml`** | `push` + `PR` | Detecta qué apps cambiaron y corre en paralelo: lint + type-check + tests + coverage + build de frontend, backend-core (spotless/checkstyle/spotbugs/pmd + JUnit + Testcontainers) y backend-ai (ruff/black/mypy + pytest); valida además `docker-compose`, Dockerfiles (hadolint), manifiestos K8s (kubeconform) y YAML (yamllint). |
| **`security.yml`** | `push`/`PR` a `main` + semanal (cron) | Gitleaks (secretos), Trivy (filesystem + imágenes de los 3 servicios), OWASP Dependency-Check, CodeQL, y Snyk (opcional, solo si está seteada la variable de repo `ENABLE_SNYK`). |
| **`sonarqube.yml`** | `push` + `PR` | Análisis SAST contra la instancia de SonarQube configurada — **opcional**, solo corre si la variable de repo `SONAR_HOST_URL` está seteada. |
| **`build-and-push.yml`** | `push` a `main`/`dev` + tags `v*` | Construye las imágenes de las 3 apps y las publica en GHCR. |
| **`deploy.yml`** | `push` a `dev`, tags `v*.*.*`/`v*-rc*`, o manual (`workflow_dispatch`) | Único workflow de despliegue: determina el entorno destino (dev automático, staging/prod manual con aprobación vía GitHub Environments) según el trigger. |
| **`pr-checks.yml`** | Apertura/actualización de PR | Título (Conventional Commits), tamaño del PR, labels requeridas, rama al día con la base, ESLint (`--max-warnings 0`) y Prettier del frontend. |

### Gates de calidad relevantes

- ✅ Tests unitarios y de integración (JUnit + Testcontainers, Vitest, pytest)
- ✅ ESLint / Checkstyle+Spotless+SpotBugs+PMD / Ruff+Black+mypy sin errores
- ✅ Cobertura JaCoCo con umbral mínimo (gate del propio build de backend-core)
- ✅ Sin secretos filtrados (Gitleaks)
- ✅ Título del PR en formato Conventional Commits

---

## Herramientas de infraestructura desplegadas

Todas las herramientas del stack se levantan con Docker Compose usando **profiles**, así cada dev puede elegir qué encender:

```bash
# Solo la aplicación (rápido, ligero)
docker compose --profile app up -d

# App + IAM + almacenamiento
docker compose --profile app --profile security up -d

# Todo (incluye monitoreo, sonar, portainer, watchtower)
docker compose --profile full up -d
```

### Perfiles definidos

- `app` → frontend, backend-core, backend-ai, postgres, redis, ollama
- `security` → keycloak, keycloak-db, minio
- `observability` → prometheus, grafana, loki, promtail, cadvisor, node-exporter, postgres-exporter, redis-exporter
- `elastic` → elasticsearch, kibana, filebeat (logs de seguridad/BD/apps, aparte de Loki)
- `quality` → sonarqube, sonar-db
- `management` → portainer, watchtower, pgadmin
- `proxy` → nginx (con TLS local)
- `full` → todos los anteriores

---

## Comandos útiles

Todos los comandos comunes están en el `Makefile` (correr `make help` para verlos con su descripción):

```bash
make help              # Muestra todos los comandos disponibles
make up                # Levanta todo el stack (perfil full)
make up-app            # Solo la app (frontend, backend-core, backend-ai, postgres, redis, ollama)
make down              # Detiene todo (preserva volúmenes)
make logs              # Sigue logs de todos los servicios
make logs-core / logs-ai / logs-front / logs-elastic   # Logs de un servicio puntual
make ps                # Lista contenedores activos
make build / rebuild    # Reconstruye las imágenes locales (rebuild = sin caché)
make test               # Ejecuta TODAS las suites (frontend + backend-core + backend-ai)
make test-frontend      # Solo tests del frontend (Vitest)
make test-e2e           # E2E con Playwright (necesita la stack levantada)
make test-core          # Solo tests del backend-core (JUnit + Testcontainers)
make test-ai            # Solo tests del backend-ai (pytest)
make lint               # ESLint + Checkstyle/Spotless + Ruff/Black
make format             # Auto-formateo (Prettier + Spotless + Black/Ruff)
make pull-llm           # Descarga Llama 3 + el modelo de embeddings en Ollama
make backup / restore   # Backup y restauración de Postgres
make sonar               # Corre el scanner de SonarQube local contra el contenedor propio
make security-scan       # Trivy local (filesystem)
make certs / certs-force # Genera/regenera el certificado TLS local de nginx
make health              # Verifica que frontend/backend-core/backend-ai respondan OK
make clean / nuke        # Baja la stack; `nuke` además borra volúmenes (¡destructivo!)
```

No hace falta `make seed` ni `make migrate`: las migraciones de base de datos las aplica
automáticamente Flyway al arrancar `backend-core` (`spring-boot-starter-flyway`), y el catálogo
MCU 5.0 se carga como parte de esas mismas migraciones.

---

## Documentación adicional

### Arquitectura y código

- 📄 [`docs/Arquitectura.md`](docs/Arquitectura.md) — Detalles de la arquitectura.
- 📄 [`docs/Codigo.md`](docs/Codigo.md) — Catálogo de módulos y clases del monorepo.
- 📄 [`docs/API.md`](docs/API.md) — Contratos de las APIs.

### Modelo de datos y diagramas UML

- 📄 [`docs/HistoriasDeUsuario.md`](docs/HistoriasDeUsuario.md) — Historias de usuario del sistema completo, con criterios de aceptación.
- 📄 [`docs/diagramas/casos-de-uso.md`](docs/diagramas/casos-de-uso.md) — Actores y casos de uso.
- 📄 [`docs/diagramas/diagrama-clases.md`](docs/diagramas/diagrama-clases.md) — Diagrama de clases (UML) del dominio.
- 📄 [`docs/diagramas/modelo-entidad-relacion.md`](docs/diagramas/modelo-entidad-relacion.md) — Modelo entidad-relación completo.
- 📄 [`docs/diagramas/normalizacion-bd.md`](docs/diagramas/normalizacion-bd.md) — Normalización (1FN/2FN/3FN) de la base de datos.
- 📄 [`docs/mapeo-jpa.md`](docs/mapeo-jpa.md) — Mapeo objeto-relacional (JPA/Hibernate) de cada entidad.

### Seguridad y operación

- 📄 [`docs/SECURITY.md`](docs/SECURITY.md) — Modelo de amenazas, controles OWASP y endurecimientos recientes.
- 📄 [`docs/Testing.md`](docs/Testing.md) — Las 4 suites automatizadas (JUnit/Testcontainers, pytest, Vitest, Playwright) + metodología de QA manual contra el stack real.
- 📄 [`docs/Certificados-TLS.md`](docs/Certificados-TLS.md) — HTTPS, certificado autofirmado y su renovación.
- 📄 [`docs/Notificaciones-Email.md`](docs/Notificaciones-Email.md) — Configurar el SMTP para el email de "¿Olvidaste tu contraseña?".
- 📄 [`docs/RUNBOOK.md`](docs/RUNBOOK.md) — Operación, incidentes y recuperación.
- 📄 [`docs/CI-CD-Infra.md`](docs/CI-CD-Infra.md) — Pipelines de CI/CD e infraestructura.
- 📄 [`docs/Ambientes-Despliegue.md`](docs/Ambientes-Despliegue.md) — Instructivo paso a paso para levantar PRISMA en cada ambiente (local, dev remoto, staging, producción).
- 📄 [`docs/Kubernetes-Minikube-Portainer.md`](docs/Kubernetes-Minikube-Portainer.md) — Cluster Kubernetes local (minikube) y gestión desde Portainer.

### Contribución y contexto del proyecto

- 📄 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) — Cómo contribuir + convenciones.
- 📄 [`docs/Dev-Config.md`](docs/Dev-Config.md) — Setup detallado por SO (Windows/Mac/Linux).
- 📄 [`docs/Proyecto.md`](docs/Proyecto.md) — Anteproyecto de grado (UTEC): equipo, alcance, viabilidad y objetivos.
- 📄 [`docs/mcu-5.0/`](docs/mcu-5.0/) — Marco de Ciberseguridad AGESIC v5.0 (fuente del catálogo).

---

## Licencia

Proyecto de grado académico — Universidad Tecnológica (UTEC), Uruguay — Licenciatura en Tecnologías de la Información — 2026.
