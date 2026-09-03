# 🏛️ Arquitectura de PRISMA

## Vista general

PRISMA sigue una **arquitectura hexagonal (Ports & Adapters)** distribuida en microservicios contenerizados, con estricto aislamiento multitenant y una capa de IA opcional basada en RAG.

## Componentes clave

### 1. Frontend (Vue 3)

- **Framework:** Vue 3 + Composition API
- **State management:** Pinia
- **Routing:** Vue Router 4 (guards por rol)
- **Estilos:** Tailwind CSS 4 + Headless UI + Heroicons — tema claro/oscuro (modo `class`,
  `stores/theme.ts`), toggle en el menú del usuario
- **Auth:** Keycloak-JS (OIDC/PKCE)
- **HTTP:** Axios con interceptors para JWT y refresh
- **Visualización:** Chart.js (radar/spider para madurez, KPIs)

### 2. Backend Core (Spring Boot)

Estructura hexagonal:

```
uy.edu.prisma
 ├── application      # Casos de uso, orquestación
 ├── domain           # Entidades, agregados, value objects, puertos
 ├── infrastructure   # Adaptadores hacia sistemas externos: Keycloak Admin API, backend-ai (RAG)
 └── web              # Controllers REST, DTOs, mappers
```

Responsabilidades:
- Gestión de organizaciones, usuarios, roles (RBAC)
- CRUD del catálogo MCU 5.0 (versionado)
- Motor de evaluación y cálculo de madurez
- Máquina de estados del workflow de auditoría
- Almacenamiento seguro de evidencias (MinIO + URLs firmadas)
- Bitácora inmutable de auditoría (append-only)
- Generación de reportes PDF/Excel

### 3. Backend AI (FastAPI + RAG)

Pipeline RAG:

```
Documento → Loader → Splitter → Embeddings → ChromaDB
                                                 ↓
Pregunta → Embedding → Retriever (top-k) → LLM (Llama 3 vía Ollama) → Respuesta + citas
```

Endpoints:
- `POST /api/v1/rag/query` — Pregunta sobre un requisito MCU
- `POST /api/v1/rag/ingest` — Ingesta un nuevo documento
- `POST /api/v1/evaluate/evidence` — Evalúa si una evidencia satisface un control

### 4. Base de datos (PostgreSQL 17)

- **Completamente relacional**, catálogo incluido: el catálogo MCU 5.0 es un árbol de tablas
  normalizadas (`catalog_versions → catalog_functions → catalog_categories →
  catalog_subcategories → catalog_requirements → catalog_controls`), con
  `catalog_requirement_subcategories` como tabla N:M explícita desde V15 (un Requisito puede
  pertenecer a más de una Subcategoría) — no hay ningún campo JSONB en el modelo del catálogo. Ver
  el detalle completo en [`diagramas/modelo-entidad-relacion.md`](diagramas/modelo-entidad-relacion.md)
  y [`mapeo-jpa.md`](mapeo-jpa.md).
- **Sin Row-Level Security**: el aislamiento multi-tenant se hace en la capa de aplicación
  (`CurrentUserService.assertOrganizationAccess()` llamado explícitamente por cada `Service`), no
  con políticas RLS de Postgres. Esto es deliberado pero tiene un costo real: es responsabilidad de
  cada `Service` nuevo llamarlo — nada a nivel de base de datos lo hace por vos. Ver
  [`SECURITY.md`](SECURITY.md) para un caso real donde este chequeo faltaba en dos servicios
  enteros (`OrganizationService`/`UserService`) hasta que se detectó y corrigió.
- Migrations con Flyway (`src/main/resources/db/migration/V*__*.sql`).

### 5. Caché (Redis)

Declarado en el stack (`docker-compose.yml`, perfil `app`) pero **sin ningún uso activo en el
código todavía** — no hay sesiones, rate-limiting ni blacklist de tokens implementados sobre él a
la fecha (la autenticación es 100% stateless vía JWT de Keycloak). Queda reservado para si en el
futuro hace falta cachear resultados costosos (p.ej. el árbol completo del catálogo) o
implementar rate-limiting real.

### 6. Almacenamiento (MinIO)

- Bucket `prisma-evidences` — evidencias privadas.
- Bucket `prisma-reports` — PDFs generados.
- URLs firmadas con TTL para descargas.

### 7. IAM (Keycloak)

- Realm `prisma` con roles: `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR`, `AUDITOR`, `VIEWER`.
- Política de OTP/TOTP configurada a nivel de realm (`otpPolicyType: totp` en
  `infra/keycloak/realm-prisma.json`) — **disponible, no obligatoria**: cualquier usuario puede
  activarla desde su cuenta de Keycloak, pero ningún rol la tiene forzada vía `requiredActions`.
- Política de password: longitud mínima 8 (`passwordPolicy: length(8)`).
- Brute-force protection habilitado (`bruteForceProtected: true`, 5 intentos, configurable en vivo
  desde la consola de Keycloak sin redeploy).
- `eventsEnabled: true` — Keycloak audita sus propios eventos de login (éxito/error); el rol
  `view-events` de `realm-management`, concedido al *service account* `prisma-backend`, es lo que
  le permite a `backend-core` leerlos (`LoginFailureAuditSyncService`) y reflejar los intentos
  fallidos en la bitácora propia — ver [`SECURITY.md`](SECURITY.md).

## Seguridad transversal

- HTTPS en todos los entornos (certificado autofirmado propio o `mkcert` en local — ver
  [`Certificados-TLS.md`](Certificados-TLS.md) —, Let's Encrypt/cert-manager en Kubernetes).
- Argon2id para el hash de contraseñas locales (`PasswordHasher`, ver [`Codigo.md`](Codigo.md));
  Keycloak gestiona el suyo propio para las credenciales con las que se loguea.
- JWT firmado por Keycloak (RS256), validado por `backend-core` contra las JWKS del realm; sin
  estado de sesión del lado de la API (no hay refresh-token store ni blacklist — ver la nota sobre
  Redis más arriba).
- Aislamiento multi-tenant verificado **explícitamente en cada `Service`**
  (`CurrentUserService.assertOrganizationAccess()`), no por un middleware/filtro transversal — ver
  la advertencia en la sección de PostgreSQL y el detalle en [`SECURITY.md`](SECURITY.md).
- Rate limiting a nivel de nginx (`infra/nginx/conf.d/prisma.conf`, zonas `limit_req`/`limit_conn`
  en `/api` y `/ai`); no hay un rate limiter adicional del lado de Spring.
- Escaneo continuo: Trivy, OWASP Dependency-Check, Snyk (opcional), CodeQL, Gitleaks — ver
  `security.yml` en [`CI-CD-Infra.md`](CI-CD-Infra.md).

## Trazabilidad (Audit Logs)

Cada operación crítica se persiste en `audit.audit_logs` (schema separado del resto de la app, ver
[`diagramas/normalizacion-bd.md`](diagramas/normalizacion-bd.md)) vía `AuditLogService.record(...)`,
con:
- `user_id`, `tenant_id`, `action`, `resource`, `payload` (JSON), `ip_address`, `user_agent`, `created_at`.
- Escritura **síncrona**, dentro de la misma transacción que la operación que audita (no hay cola
  de eventos ni proceso async) — si la escritura del audit log falla, la transacción completa hace
  rollback. Un efecto colateral real de esto: al auditar un `DELETE` sobre el propio usuario
  autenticado, `AuditLogService` intenta resolver "quién hizo esto" *después* de que la fila ya se
  borró en esa misma transacción, y la resuelve vacía (ver el caso real documentado en
  [`RUNBOOK.md`](RUNBOOK.md)).
- **Login/logout/login fallido:** Keycloak maneja el login directamente (Authorization Code +
  PKCE) — `backend-core` nunca ve ese request, así que estas tres acciones no siguen el camino
  normal de arriba. `LOGIN`/`LOGOUT` los reporta el frontend explícitamente
  (`POST /account/session-event`, una vez por sesión de pestaña) justo después de autenticarse y
  justo antes de desloguear. `LOGIN_FAILED` lo trae un job programado
  (`LoginFailureAuditSyncService`, cada 15s) desde los eventos que Keycloak ya audita solo — ver
  [`SECURITY.md`](SECURITY.md#registro-de-intentos-de-login-fallidos).
- **Consultable desde la UI:** "Actividad del Sistema" (`GET /api/audit-logs`,
  `ActivityLogView.vue`), acotado por tenant como el resto de la app (`PRISMA_ADMIN` ve todos,
  `ORG_RESPONSIBLE` sólo el propio). `AuditLogService.list()` resuelve en batch el nombre real de
  la entidad detrás de cada `resource` para que la columna diga, por ejemplo, "Usuario: Jane Doe"
  en vez de un UUID crudo.
