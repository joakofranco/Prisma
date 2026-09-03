# 🔧 CI/CD y componentes de infraestructura

Este documento explica **cada workflow de CI/CD** y **cada contenedor del despliegue** para que el equipo entienda qué hace cada pieza.

---

## Parte 1 — Pipelines CI/CD

Todos los workflows están en `.github/workflows/`.

### 1. `ci.yml` — Integración continua principal

**Trigger:** push y PR a cualquier rama.

**Componentes:**
| Job | Qué hace | Cuándo se ejecuta |
|---|---|---|
| **`changes`** | Usa `dorny/paths-filter` para detectar qué apps cambiaron y ejecutar sólo los jobs necesarios. Ahorra ~70% del tiempo en PRs pequeños. | Siempre |
| **`frontend`** | ESLint + vue-tsc (type-check) + Vitest (unit + coverage) + build Vite | Si cambió `apps/frontend/**` o es un PR |
| **`backend-core`** | Spotless (format) + Checkstyle + Compile + JUnit unit + Failsafe integration (con Testcontainers para Postgres real) + JaCoCo report | Si cambió `apps/backend-core/**` o es un PR |
| **`backend-ai`** | Ruff (lint) + Black (format) + mypy (types) + pytest + coverage | Si cambió `apps/backend-ai/**` o es un PR |
| **`infra-validation`** | `docker compose config` + hadolint (Dockerfiles) + kubeconform (K8s) + yamllint | Si cambió `infra/**` |
| **`ci-summary`** | Gate final. Falla el CI si alguno de los jobs anteriores falló. | Siempre |

**Cobertura y test reports:** Se suben como artifacts (`coverage-frontend`, `coverage-backend-core`, etc.) y se re-usan en SonarQube.

### 2. `security.yml` — Escaneo de seguridad

**Trigger:** push a `dev`/`main`, semanal (domingo 03:00 UTC), manual.

| Job | Herramienta | Qué detecta |
|---|---|---|
| **`gitleaks`** | Gitleaks | Secretos, API keys, tokens filtrados en el histórico de git |
| **`trivy-fs`** | Trivy FS | Vulnerabilidades en dependencias del código fuente |
| **`trivy-images`** | Trivy Image | CVEs en las imágenes Docker construidas |
| **`owasp-dc`** | OWASP Dependency-Check | Vulnerabilidades conocidas en dependencias Java |
| **`snyk`** | Snyk (opcional) | Vuln en npm + pip + maven + IaC |
| **`codeql`** | GitHub CodeQL | SAST profundo (Java, Python, JS) |

**Resultado:** todos los findings se suben en formato SARIF al tab **Security → Code scanning** del repo.

### 3. `sonarqube.yml` — Calidad de código

**Trigger:** push y PR a `dev`/`main`.

Descarga los artifacts de cobertura del `ci.yml` y ejecuta `sonar-scanner`. **El Quality Gate es bloqueante:** si SonarQube dice `Failed`, el PR no puede mergearse.

Requiere secrets: `SONAR_TOKEN`, `SONAR_HOST_URL`.

### 4. `build-and-push.yml` — Publicación de imágenes

**Trigger:** push a `dev`/`main` y tags `v*`.

Usa **Docker Buildx multi-arch** (amd64 + arm64), publica en **GHCR** (`ghcr.io/<org>/prisma-*`), firma las imágenes con **cosign** y genera **SBOM** con Syft.

Tags automáticos:
- `push dev` → `dev`, `dev-<sha>`
- `push main` → `main`, `main-<sha>`, `latest`
- `tag v1.2.3` → `v1.2.3`, `1.2`, `1`, `latest`

### 5. `deploy.yml` — Despliegues

**Trigger:**
- merge a `dev` → deploy automático a `dev` (SSH + docker compose)
- tag `v*-rc*` → deploy a `staging` (Kubernetes + Kustomize)
- tag `v*` → deploy a `prod` (Kubernetes + Kustomize)
- `workflow_dispatch` → manual, con selección de entorno y tag

Usa **GitHub Environments** para forzar aprobaciones (2 reviewers obligatorios para `prod`).

### 6. `pr-checks.yml` — Validación de PRs

- Título del PR sigue Conventional Commits.
- Tamaño del PR (label XS/S/M/L/XL).
- Labels obligatorios (tipo).
- Rama al día con base.
- Descripción no vacía.
- **ESLint**: código Frontend sin errores de lint (`--max-warnings 0`).
- **Prettier**: formato e indentación correctos (`prettier --check`).

---

## Parte 2 — Componentes del despliegue (docker-compose)

Cada servicio en `docker-compose.yml` tiene un rol claro. Los perfiles (`--profile`) permiten encender sólo lo necesario.

### Perfil `app` — Aplicación core

| Servicio | Imagen | Rol |
|---|---|---|
| **`postgres`** | `postgres:17-alpine` | Base de datos relacional. Persiste catálogo MCU, evaluaciones, evidencias metadata, audit logs. Init SQL en `scripts/db/init.sql`. |
| **`redis`** | `redis:7-alpine` | Caché de sesiones, refresh tokens, rate limiting. Passworded, AOF activo. |
| **`backend-core`** | Build local Spring Boot | API principal. Endpoints REST, motor de cálculo de madurez, workflow de auditoría, RBAC. Actuator expuesto en `/actuator`. |
| **`backend-ai`** | Build local FastAPI | Servicio de IA que consulta a Ollama y hace RAG sobre la documentación MCU. |
| **`ollama`** | `ollama/ollama:latest` | Motor de LLM local (Llama 3 8B por defecto). Corre inferencia sin salir a internet — cumple con requisito de privacidad. |
| **`frontend`** | Build local Nginx+Vue | SPA con dashboards, cuestionario, visor de evidencias. |

### Perfil `security` — IAM y almacenamiento

| Servicio | Imagen | Rol |
|---|---|---|
| **`keycloak`** | `quay.io/keycloak/keycloak:26.7` | Identity Provider. SSO, OAuth2, políticas de contraseña. Política de OTP/TOTP configurada a nivel de realm pero **no forzada** para ningún rol (ver [`SECURITY.md`](SECURITY.md)) — un usuario puede activarla desde su propia cuenta, no es obligatoria hoy. Realm `prisma` importado automáticamente desde `infra/keycloak/realm-prisma.json`. |
| **`keycloak-db`** | `postgres:17-alpine` | BD dedicada de Keycloak (separada de la de la app). |
| **`minio`** | `minio/minio:latest` | Storage S3-compatible para evidencias. Buckets creados automáticamente por `minio-init`. |
| **`minio-init`** | `minio/mc:latest` | Job one-shot que crea los buckets al arrancar. |

### Perfil `observability` — Monitoreo

| Servicio | Imagen | Rol |
|---|---|---|
| **`prometheus`** | `prom/prometheus:latest` | Scrape de métricas de todos los servicios. Retención 30 días. Reglas de alerta en `infra/monitoring/prometheus/rules/`. |
| **`grafana`** | `grafana/grafana:latest` | Dashboards de negocio (madurez global, cumplimiento) y de sistema (CPU, RAM, latencias). Datasources y dashboards provisionados. |
| **`loki`** | `grafana/loki:latest` | Agregación de logs de contenedores. Consultables desde Grafana con LogQL. |
| **`promtail`** | `grafana/promtail:latest` | Agente que envía los logs de los contenedores a Loki. |
| **`cadvisor`** | `gcr.io/cadvisor/cadvisor:latest` | Métricas de recursos por contenedor. |
| **`node-exporter`** | `prom/node-exporter:latest` | Métricas del host (CPU, RAM, disco, red). |

### Perfil `elastic` — Logs centralizados (Elasticsearch)

Segunda vía de logs, aparte de Loki/Grafana de arriba (no lo reemplaza): pensada para
búsqueda/investigación libre entre TODOS los servicios (seguridad: Keycloak/nginx; base de datos:
Postgres/keycloak-db; aplicaciones: backend-core/backend-ai/frontend), con Kibana como UI. Config
de Filebeat en `infra/monitoring/filebeat/filebeat.yml`.

| Servicio | Imagen | Rol |
|---|---|---|
| **`elasticsearch`** | `docker.elastic.co/elasticsearch/elasticsearch:9.5.3` | Motor de indexado/búsqueda, nodo único. Seguridad (`xpack.security`) habilitada, usuario `elastic`. |
| **`kibana-init`** | `docker.elastic.co/elasticsearch/elasticsearch:9.5.3` | Job one-shot (mismo patrón que `minio-init`) que le setea password al usuario de servicio `kibana_system`. Kibana rechaza arrancar (loop de restart) si se le da el superusuario `elastic` en cambio. |
| **`kibana`** | `docker.elastic.co/kibana/kibana:9.5.3` | UI para buscar/filtrar/armar dashboards sobre los logs indexados. Se conecta a Elasticsearch como `kibana_system`, no como `elastic`. Healthcheck contra `/api/status` (no basta con que el contenedor esté "Up": tarda ~30-40s en levantar sus plugins). |
| **`kibana-dashboards-init`** | `curlimages/curl:8.11.0` | Job one-shot que importa `infra/monitoring/kibana/dashboards.ndjson` (data view + 4 dashboards, ver abajo) apenas Kibana está sano, para no tener que rearmarlos a mano en cada ambiente nuevo. |
| **`filebeat`** | `docker.elastic.co/beats/filebeat:9.5.3` | Agente que descubre los contenedores del proyecto (por nombre, `prisma-*`) y les manda los logs, igual rol que Promtail para Loki. Usa el input `filestream` con el parser `container` (`infra/monitoring/filebeat/filebeat.yml`) — el input `container` a secas, deprecado desde Beats 8.x, Filebeat 9.x directamente lo rechaza ("won't start runner") sin tumbar el contenedor ni loguear el error a stdout, así que el síntoma es "arranca sano pero no indexa nada". |

```bash
docker compose --profile elastic up -d   # sólo esto, sin el resto de la app
```

Acceso: http://localhost:5601 (Kibana), usuario `elastic` / valor de `ELASTIC_PASSWORD` en tu
`.env`. Los logs quedan en el data stream `filebeat-*` que Filebeat gestiona solo (con su propia
política de ILM — no se fuerza un nombre de índice propio a propósito, ver el comentario en
`filebeat.yml`).

**Dashboards ya armados** (Kibana → Dashboards), importados automáticamente por
`kibana-dashboards-init` desde `infra/monitoring/kibana/dashboards.ndjson`:

| Dashboard | Contenido |
|---|---|
| **PRISMA - Overview** | Volumen de logs de TODOS los servicios en el tiempo, tabla de top servicios por volumen, y una tabla con los logs más recientes de todo el stack. |
| **PRISMA - Seguridad** | Igual, filtrado a Keycloak, nginx y MinIO. |
| **PRISMA - Base de Datos** | Igual, filtrado a Postgres, Redis, keycloak-db y sonar-db. |
| **PRISMA - Aplicaciones** | Igual, filtrado a backend-core, backend-ai y frontend. |

Si agregás un panel nuevo o editás uno existente desde la UI de Kibana y querés versionarlo,
re-exportalo:

```bash
curl -u elastic:$ELASTIC_PASSWORD -X POST 'http://localhost:5601/api/saved_objects/_export' \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
  -d '{"objects":[
    {"type":"dashboard","id":"prisma-dashboard-overview"},
    {"type":"dashboard","id":"prisma-dashboard-seguridad"},
    {"type":"dashboard","id":"prisma-dashboard-bd"},
    {"type":"dashboard","id":"prisma-dashboard-apps"}
  ],"includeReferencesDeep":true}' \
  -o infra/monitoring/kibana/dashboards.ndjson
```

Para filtrar manualmente en vez de por dashboard: `container.name` (todos arrancan con `prisma-`)
o `docker.container.labels.com_docker_compose_service` acotan a un servicio puntual.

### Perfil `quality` — Calidad de código

| Servicio | Imagen | Rol |
|---|---|---|
| **`sonarqube`** | `sonarqube:community` | Análisis SAST, cobertura, code smells, deuda técnica. Quality Gate configurable. |
| **`sonar-db`** | `postgres:17-alpine` | BD dedicada de SonarQube. |

### Perfil `management` — Gestión

| Servicio | Imagen | Rol |
|---|---|---|
| **`portainer`** | `portainer/portainer-ce:latest` | UI web para gestionar contenedores, imágenes, volúmenes y redes. Alternativa gráfica a la CLI de Docker. |
| **`watchtower`** | `containrrr/watchtower:latest` | Auto-update de contenedores cuando aparece una nueva imagen en el registry. Sólo actúa sobre los que tienen la label `com.centurylinklabs.watchtower.enable=true`. |
| **`pgadmin`** | `dpage/pgadmin4:latest` | Cliente web para PostgreSQL. Servers preconfigurados en `scripts/db/pgadmin-servers.json`. |

### Perfil `proxy`

| Servicio | Imagen | Rol |
|---|---|---|
| **`nginx`** | `nginx:1.27-alpine` | Reverse proxy HTTPS. Termina TLS, rutea `/api/*` → backend-core, `/ai/*` → backend-ai, `/auth/*` → Keycloak, `/*` → frontend. Rate limiting, headers de seguridad. |

---

## Parte 3 — Deployment a Kubernetes (`infra/kubernetes/`)

Estructura **Kustomize** (base + overlays por entorno):

```
infra/kubernetes/
├── base/                       # Manifiestos comunes
│   ├── namespace.yaml
│   ├── configmap.yaml          # ConfigMap + PVC evidencias (el Secret real NO está acá, ver abajo)
│   ├── secret.example.yaml     # Plantilla del Secret -- se crea a mano por namespace, nunca vía kustomize
│   ├── postgres.yaml           # StatefulSet con PVC
│   ├── redis.yaml
│   ├── keycloak.yaml           # StatefulSet keycloak-db + Deployment keycloak
│   ├── minio.yaml              # StatefulSet con PVC 50 GB
│   ├── minio-init-job.yaml     # Job: crea los buckets de evidencias/reportes
│   ├── backend-core.yaml       # Deployment (2 replicas) + Service + HPA + PDB
│   ├── chroma.yaml             # Deployment (1 réplica, con estado) + PVC 10 GB + Service -- índice RAG
│   ├── backend-ai.yaml         # Deployment (2 replicas, sin PVC propio -- stateless, le pega a "chroma" por HTTP)
│   ├── ollama.yaml             # StatefulSet con PVC 30 GB para modelos
│   ├── frontend.yaml           # Deployment 2 replicas + Service
│   ├── ingress.yaml            # Ingress NGINX + cert-manager (incluye /auth → Keycloak)
│   └── kustomization.yaml
└── overlays/
    ├── dev/                    # Namespace prisma-dev, tags dev
    ├── staging/                # Namespace prisma-staging
    └── prod/                   # Namespace prisma-prod, 4 replicas, más recursos
```

Ver [`infra/kubernetes/README.md`](../infra/kubernetes/README.md) para el detalle de cómo crear el
Secret real y sincronizar los assets (`make sync-k8s-assets`) antes del primer deploy a un
namespace nuevo.

**Cómo se despliega cada ambiente** (ver también la tabla resumen en el README, sección
"Ambientes: de desarrollo local a producción", y la guía paso a paso completa en
[`Ambientes-Despliegue.md`](Ambientes-Despliegue.md)):

| Ambiente | Trigger de `deploy.yml` | Mecanismo |
|---|---|---|
| **dev** | `push`/merge a la rama `dev` | SSH a un único servidor + `docker compose --profile full up -d` (**no** usa Kubernetes) |
| **staging** | tag `v*.*.*-rc*` | `kubectl apply -k infra/kubernetes/overlays/staging`, namespace `prisma-staging` |
| **prod** | tag `v*.*.*` (semver, sin `-rc`) | `kubectl apply -k infra/kubernetes/overlays/prod`, namespace `prisma-prod`, requiere 2 aprobadores en el Environment `prod` de GitHub |

> **Sobre la rama `dev`:** existe en el remoto y el trigger de arriba es real (el workflow sí la
> escucha), pero **no forma parte del flujo de trabajo diario** de nadie en el equipo — el
> desarrollo va directo contra `main` en ramas cortas (`fix/*`/`feature/*`), ver
> [`README.md` → Estrategia de branching](../README.md#estrategia-de-branching) y
> [`CONTRIBUTING.md`](CONTRIBUTING.md). En la práctica, el deploy automático a "dev" de esta tabla
> no se ha disparado en el ciclo de vida del proyecto hasta ahora — documentado tal cual está
> configurado, no tal cual se usa, para que quien lo active sepa qué esperar.

El overlay `dev` de Kubernetes (`infra/kubernetes/overlays/dev/`) existe en el repo y se puede
aplicar a mano contra un cluster local (`make k8s-dev`, ver Makefile) para probar los manifiestos
sin depender de staging — pero el deploy automático a "dev" del pipeline **no** pasa por ahí, usa
el servidor con Docker Compose de la tabla de arriba.

Aplicar manualmente (equivalente a lo que hace el job `deploy` del workflow):

```bash
# Staging (reemplazar <org> y el tag por los reales)
cd infra/kubernetes/overlays/staging
kustomize edit set image \
  ghcr.io/<org>/prisma-frontend=ghcr.io/<org>/prisma-frontend:v1.3.0-rc1 \
  ghcr.io/<org>/prisma-backend-core=ghcr.io/<org>/prisma-backend-core:v1.3.0-rc1 \
  ghcr.io/<org>/prisma-backend-ai=ghcr.io/<org>/prisma-backend-ai:v1.3.0-rc1
kubectl apply -k .

# Prod: mismos pasos contra infra/kubernetes/overlays/prod y namespace prisma-prod
```

Requiere `kubectl` y `kustomize` con acceso al cluster (el mismo kubeconfig que CI recibe vía el
secret `KUBE_CONFIG` del Environment correspondiente).

**Auto-escalado:**  
HPA sobre backend-core (2-8 réplicas basadas en CPU/RAM).

**Alta disponibilidad:**  
PDB garantiza mínimo 1 pod disponible durante actualizaciones. Postgres usa StatefulSet con PVC para persistencia; en prod se recomienda migrar a **CloudNativePG** o RDS.

---

## Parte 4 — Governance del repo

| Elemento | Archivo | Efecto |
|---|---|---|
| **CODEOWNERS** | `.github/CODEOWNERS` | Reviewers automáticos por path. `require_code_owner_reviews` bloquea el merge si no aprueban. |
| **Branch protection** | `.github/branch-protection.yml` + `scripts/apply-branch-protection.sh` | `main`: 2 aprobaciones + todos los checks. `dev`: 1 aprobación. Ramas `dev/<user>`: sólo el owner pushea. |
| **PR template** | `.github/PULL_REQUEST_TEMPLATE/` | Formato consistente para descripción, checklist, capturas. |
| **Issue templates** | `.github/ISSUE_TEMPLATE/` | Bug reports y feature requests estructurados. |
| **Environments** | Configurados vía script | `prod` requiere 2 aprobadores + rama protegida. |
