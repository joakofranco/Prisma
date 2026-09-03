# 📖 Catálogo de Código — PRISMA

> Referencia de todos los módulos/clases del monorepo y su responsabilidad. Complementa a
> [`Arquitectura.md`](Arquitectura.md) (visión de componentes) y a
> [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md) (modelo de dominio): este
> documento cubre además la capa de aplicación (servicios), infraestructura y el frontend.

## backend-core (Spring Boot 3.5 / Java 25) — arquitectura hexagonal

```
uy.edu.prisma
 ├── application      # Casos de uso — un Service por agregado/módulo funcional
 ├── domain           # Entidades JPA, repositorios (puertos), excepciones de negocio
 ├── infrastructure   # Adaptadores hacia sistemas externos (Keycloak, backend-ai)
 ├── config           # Beans de configuración (seguridad, clientes HTTP, propiedades)
 └── web              # Controllers REST, DTOs, manejo global de errores
```

### `application/` — casos de uso

| Clase | Responsabilidad |
|---|---|
| `OrganizationService` | CRUD de organizaciones. `list()`/`getById()` acotan por tenant vía `CurrentUserService` (mismo patrón que `EvaluationService`) — `PRISMA_ADMIN` ve todas, `AUDITOR` sólo las que audita, el resto sólo la propia. `delete()` es baja lógica (`enabled=false`), no `DELETE` real — preserva el historial de evaluaciones/usuarios de la organización (ver [`diagramas/casos-de-uso.md#uc1`](diagramas/casos-de-uso.md)). |
| `UserService` | CRUD de usuarios locales; `create()`/`update()` provisionan y sincronizan la cuenta correspondiente en Keycloak vía `KeycloakAdminClient` (contraseña, roles) para que el usuario pueda loguearse de verdad. `list()`/`getById()` acotan por tenant igual que `OrganizationService` (un usuario siempre puede ver su propia ficha aunque esté fuera de su tenant). `delete()` rechaza borrar la propia cuenta o al administrador global sembrado (id fija, ver `V4__seed_admin_user.sql`). |
| `CurrentUserService` | Resuelve el `User` de base de datos a partir del JWT autenticado (claim `email`/`sub`); expone `assertOrganizationAccess()` — el punto central del aislamiento multi-tenant — y `isGlobalRole()` (`PRISMA_ADMIN`/`AUDITOR`, no atados a una organización). |
| `CatalogService` | Lectura del catálogo MCU 5.0 (`getByVersion`, `listVersions`), con soporte para acotar el árbol a un `CommunityProfile` (poda funciones/categorías/subcategorías/requisitos que se quedan sin controles tras filtrar). |
| `CommunityProfileService` | CRUD de perfiles comunitarios (subconjuntos curados de controles). Catálogo de referencia compartido entre organizaciones — solo `PRISMA_ADMIN` puede escribir. |
| `EvaluationService` | El servicio más grande: ciclo de vida completo de una evaluación (crear, cambiar estado con una tabla de transiciones válidas + rol autorizado por transición, responder controles) y `calculateMaturity()` — el motor de cálculo de madurez acumulativa por subcategoría y del promedio global de la evaluación. Las respuestas sólo se pueden editar mientras `Evaluation.isSelfAssessmentEditable()` es verdadero (`DRAFT`/`IN_PROGRESS`/`RETURNED`). |
| `EvidenceService` | Sube evidencia a MinIO, dispara indexado best-effort en `backend-ai` (RAG) y expone la búsqueda de citas relevantes por control. Usa **dos** `MinioClient` (ver `MinioConfig`): el interno (`minioClient`) para hablarle al bucket y para que `backend-ai` baje el archivo a indexar, y uno público (`minioPublicClient`) sólo para firmar la URL de descarga que ve el navegador — necesitan endpoints distintos porque uno es un hostname interno de Docker. Igual que las respuestas, subir/borrar/reindexar evidencia respeta `isSelfAssessmentEditable()`. |
| `ImprovementPlanService` | CRUD del plan de mejora (acciones correctivas por control) + motor de sugerencias automáticas a partir de los controles con mayor `gap`. |
| `AuditObservationService` | Observaciones/no conformidades/recomendaciones que un `AUDITOR` registra sobre una evaluación en estado `IN_AUDIT`. |
| `DashboardService` | Agregados de solo lectura para el panel: totales, madurez promedio, distribución por estado/función — acotados por organización salvo rol global. |
| `ReportService` | Genera el reporte de una evaluación en PDF (OpenPDF) y Excel (Apache POI). |
| `AuditLogService` | Escribe la bitácora de auditoría técnica (`audit.audit_logs`) — append-only, desacoplada del modelo de dominio (ver [`diagramas/normalizacion-bd.md`](diagramas/normalizacion-bd.md)). `list()` la consulta (acotado por tenant como el resto de la app) y resuelve en batch (sin N+1) el nombre real de la entidad detrás de cada `resource` (`resolveResourceName` — usuario, organización, evaluación o versión de catálogo). `recordLoginFailure()` es la única escritura que no viene de un request HTTP interactivo, la llama `LoginFailureAuditSyncService`. |
| `LoginFailureAuditSyncService` | `@Scheduled` (cada 15s por defecto) — trae desde Keycloak (`KeycloakAdminClient.fetchLoginFailures`) los eventos `LOGIN_ERROR` que Keycloak ya audita solo y los refleja en `AuditLog` como `LOGIN_FAILED`. Necesario porque el login lo maneja Keycloak directamente (Authorization Code + PKCE): `backend-core` nunca ve ese POST. Ver [`SECURITY.md`](SECURITY.md#registro-de-intentos-de-login-fallidos). |

### `domain/` — entidades y puertos

- `entity/` — 16 entidades JPA, documentadas por completo en
  [`mapeo-jpa.md`](mapeo-jpa.md) y [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md).
- `repository/` — interfaces `JpaRepository`/`Page`-based por entidad (puertos de salida, en el
  sentido de arquitectura hexagonal); las consultas no triviales (búsqueda por texto, conteos
  filtrados) se declaran con `@Query` JPQL, no SQL nativo.
- `exception/` — `ResourceNotFoundException` (404), `ConflictException` (409),
  `InvalidRequestException` (400) — mapeadas centralmente por `GlobalExceptionHandler`.

### `infrastructure/` — adaptadores hacia sistemas externos

| Clase | Hacia dónde | Nota |
|---|---|---|
| `KeycloakAdminClient` | Keycloak Admin REST API | Usa credenciales de *service account* del client `prisma-backend` (roles `manage-users`/`view-users`/`view-events` de `realm-management`), no un usuario admin humano. `fetchLoginFailures()` (usado por `LoginFailureAuditSyncService`) lee el endpoint de eventos, no de usuarios. |
| `AiEvidenceClient` | `backend-ai` (RAG de evidencias) | Todas las llamadas son best-effort: si `backend-ai` está caído, la evidencia ya quedó guardada en MinIO/Postgres y el auditor simplemente no ve citas, pero el flujo principal no se interrumpe. |

### `config/` — beans de configuración

| Clase | Qué configura |
|---|---|
| `SecurityConfig` | Cadena de filtros de Spring Security, CORS (`prisma.cors.allowed-origins`), decodificación JWT (issuer de Keycloak o secreto HMAC local), conversión de `realm_access.roles` → `ROLE_*`. |
| `PasswordHasher` | Hash Argon2id vía `Argon2PasswordEncoder` de Spring Security (Bouncy Castle puro-Java — ver el comentario en el código sobre por qué se abandonó `argon2-jvm`). |
| `AiConfig` | Cliente HTTP (`RestClient`) hacia `backend-ai`, con la clave interna `X-Internal-Api-Key`. |
| `KeycloakAdminConfig` | Cliente HTTP hacia la Admin REST API de Keycloak + sus credenciales de service account. |
| `MinioConfig` | Dos beans `MinioClient` — interno (`MINIO_ENDPOINT`) y público (`MINIO_PUBLIC_ENDPOINT`, sólo para firmar URLs de descarga) — ambos con `region` fija (`us-east-1`): sin ella, el SDK necesita conectividad real hacia el endpoint sólo para resolver la región antes de firmar, lo que rompe el cliente público al correr dentro de un contenedor. |

### `web/` — capa REST

- `controller/` — 10 controllers, uno por agregado principal; cada endpoint mutante declara
  `@PreAuthorize` explícito por rol (ver tabla completa en
  [`diagramas/casos-de-uso.md`](diagramas/casos-de-uso.md)); los `GET` sin `@PreAuthorize` solo
  requieren estar autenticado (el filtrado por organización lo hace `CurrentUserService` dentro
  del `Service`, no el controller).
- `dto/Dto.java` — **todos** los DTOs del backend en un único archivo, como `record`s anidados
  (`CreateOrganizationDto`, `EvaluationDto`, `PaginatedDto<T>`, etc.) — decisión deliberada para
  tener en un solo lugar el "contrato" completo de la API sin saltar entre 40 archivos de una
  línea.
- `error/GlobalExceptionHandler.java` — traduce excepciones de dominio y de validación a
  `ApiErrorDto` con mensajes en español, incluyendo el fallback de `HttpStatus.getReasonPhrase()`
  (en inglés) para los pocos casos sin mensaje explícito.

## backend-ai (FastAPI + LangChain + Ollama + ChromaDB)

```
app/
 ├── api/v1/       # Routers FastAPI: evidence.py (ingesta/citas), rag.py (consulta/estado/tips)
 ├── core/         # config.py (Settings), security.py (X-Internal-Api-Key), logging.py
 ├── rag/          # vector_store.py, evidence_store.py, loaders.py, chroma_client.py
 └── main.py       # App FastAPI, CORS, healthcheck, montaje de routers
```

- **`rag/vector_store.py`** — RAG sobre la documentación del marco MCU 5.0: una única colección
  Chroma compartida (no depende de la organización, es catálogo de referencia). También arma las
  sugerencias de remediación de brechas (`suggest_remediation`, prompt propio que no se restringe
  a los fragmentos recuperados como sí hace `answer()`).
- **`rag/evidence_store.py`** — RAG sobre evidencia subida por los usuarios: **una colección Chroma
  por organización** (`evidence-{organization_id}`), aislamiento estructural (no un filtro de
  metadata que se pueda olvidar en una query) — nunca puede devolver fragmentos de otra
  organización. Cada chunk además lleva `evaluation_id`/`control_id` en la metadata para acotar
  las citas a la evaluación/control puntual.
- **`rag/loaders.py`** — extracción de texto por tipo de archivo (`.pdf` vía `PyPDFLoader`,
  `.docx`, `.txt`/`.md`).
- **`rag/chroma_client.py`** — fábrica del cliente ChromaDB subyacente: `PersistentClient` local
  (docker-compose, tests, default) o `HttpClient` contra un servidor Chroma aparte si
  `CHROMA_SERVER_HOST` está seteado (Kubernetes, backend-ai con 2+ réplicas — ver
  [`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md)). Compartida por
  `vector_store.py` y `evidence_store.py` para que ambos usen el mismo criterio.
- **`core/security.py`** — valida el header `X-Internal-Api-Key` en los endpoints llamados por
  `backend-core` (no expuestos directamente a usuarios finales).

## frontend (Vue 3 + Vite + Pinia + Tailwind)

```
src/
 ├── views/        # Una carpeta por módulo funcional (organizations, evaluations, audit, activity, ...)
 ├── components/   # common/ (Base*, DataTable, ConfirmDialog) + charts/ (Chart.js) + layout/
 ├── stores/       # Pinia — un store por recurso (auth, organizations, evaluations, theme, ...)
 ├── services/     # api.ts (instancia Axios + interceptors JWT), auth.ts (Keycloak-JS), resources.ts
 ├── router/       # Vue Router, guards por rol
 ├── composables/  # useUtils.ts (notificaciones, formateo)
 └── utils/        # constants.ts, helpers.ts, validators.ts
```

- **`services/auth.ts`** — inicialización de `keycloak-js` (PKCE, silent-check-sso vía
  `public/silent-check-sso.html`), refresh de token.
- **`services/api.ts`** — instancia Axios única con interceptor que agrega el `Bearer` token y
  reintenta tras un refresh; todos los `stores/*.ts` la reutilizan en vez de crear clientes HTTP
  propios.
- **`components/common/`** — sistema de componentes base (`BaseButton`, `BaseInput`, `BaseSelect`,
  `BaseModal`, `DataTable`, `ConfirmDialog`) reutilizados por todas las vistas — es la capa que le
  da consistencia visual a la app sin depender de una librería de componentes de terceros. Todos
  soportan tema claro/oscuro (`dark:` de Tailwind).
- **`stores/theme.ts`** — tema claro/oscuro. Aplica/quita la clase `dark` en `<html>` (Tailwind
  configurado en modo `class`, ver `@custom-variant dark` en `assets/styles/main.css`, no
  `prefers-color-scheme`), persiste en `localStorage` y se inicializa **antes** de montar la app
  (`main.ts`) para no parpadear en el tema incorrecto al cargar. Los gráficos de `components/charts/`
  (Chart.js, `<canvas>`, no siguen el CSS de la página) leen este store para su propia paleta —
  sin eso, sus textos quedaban con el gris oscuro por defecto de Chart.js sobre una tarjeta oscura.
- **`views/activity/ActivityLogView.vue`** — UI de "Actividad del Sistema" (bitácora técnica, ver
  `AuditLogService` arriba). Distinta de `views/audit/AuditView.vue` (auditoría de cumplimiento
  MCU sobre una evaluación) — dos conceptos con nombre parecido, no confundir.

## Referencias

- Arquitectura de componentes: [`Arquitectura.md`](Arquitectura.md)
- Casos de uso (con el endpoint exacto de cada uno): [`diagramas/casos-de-uso.md`](diagramas/casos-de-uso.md)
- Modelo de dominio: [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md)
- Mapeo JPA: [`mapeo-jpa.md`](mapeo-jpa.md)
- Contratos de API: [`API.md`](API.md)
- Cómo se prueba cada capa: [`Testing.md`](Testing.md)
- Modelo de seguridad y endurecimientos recientes: [`SECURITY.md`](SECURITY.md)
