# 📖 Catálogo de Código — PRISMA

> Referencia de todos los módulos/clases del monorepo y su responsabilidad. Complementa a
> [`Arquitectura.md`](Arquitectura.md) (visión de componentes) y a
> [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md) (modelo de dominio): este
> documento cubre además la capa de aplicación (servicios), infraestructura y el frontend.

## backend-core (Spring Boot 3.5 / Java 25) — arquitectura por capas

```
uy.edu.prisma
 ├── application      # Casos de uso — un Service por agregado/módulo funcional
 ├── domain           # Entidades JPA, repositorios, excepciones de negocio
 ├── infrastructure   # Clientes de sistemas externos (Keycloak, backend-ai, ClamAV)
 ├── config           # Beans de configuración (seguridad, clientes HTTP, propiedades)
 └── web              # Controllers REST, DTOs, manejo global de errores
```

### `application/` — casos de uso

| Clase | Responsabilidad |
|---|---|
| `OrganizationService` | CRUD de organizaciones. `list()`/`getById()` acotan por tenant vía `CurrentUserService` (mismo patrón que `EvaluationService`) — `PRISMA_ADMIN` ve todas, `AUDITOR` sólo las que audita, el resto sólo la propia. `delete()` es baja lógica (`enabled=false`), no `DELETE` real — preserva el historial de evaluaciones/usuarios de la organización (ver [`diagramas/casos-de-uso.md#uc1`](diagramas/casos-de-uso.md)). |
| `UserService` | CRUD de usuarios locales; `create()`/`update()` provisionan y sincronizan la cuenta correspondiente en Keycloak vía `KeycloakAdminClient` (contraseña, roles) para que el usuario pueda loguearse de verdad. `list()`/`getById()` acotan por tenant igual que `OrganizationService` (un usuario siempre puede ver su propia ficha aunque esté fuera de su tenant). `delete()` rechaza borrar la propia cuenta o al administrador global sembrado (id fija, ver `V4__seed_admin_user.sql`). |
| `CurrentUserService` | Resuelve el `User` de base de datos a partir del JWT autenticado (claim `email`/`sub`); expone `assertOrganizationAccess()` — el punto central del aislamiento multi-tenant — y `isGlobalRole()` (solo `PRISMA_ADMIN`; el `AUDITOR` está acotado a las organizaciones que se le asignan). |
| `CatalogService` | Lectura del catálogo MCU 5.0 (`getByVersion`, `listVersions`), con soporte para acotar el árbol a un `CommunityProfile` (poda funciones/categorías/subcategorías/requisitos que se quedan sin controles tras filtrar). |
| `CommunityProfileService` | CRUD de perfiles comunitarios (subconjuntos curados de controles). Catálogo de referencia compartido entre organizaciones — solo `PRISMA_ADMIN` puede escribir. El perfil no se asigna a la organización: se elige **por evaluación** (`Evaluation.communityProfile`), de modo que una misma organización puede tener evaluaciones con perfiles distintos. |
| `EvaluationService` | El servicio más grande: ciclo de vida completo de una evaluación (crear, cambiar estado con una tabla de transiciones válidas + rol autorizado por transición, responder controles) y `calculateMaturity()` — el motor de cálculo de madurez acumulativa por subcategoría y del promedio global de la evaluación. Las respuestas sólo se pueden editar mientras `Evaluation.isSelfAssessmentEditable()` es verdadero (`DRAFT`/`IN_PROGRESS`/`RETURNED`). |
| `EvidenceService` | Valida la evidencia (`EvidenceFileValidator`: extensión, firma real y tamaño), la analiza con ClamAV (`ClamAvClient`), la sube a RustFS (S3), dispara indexado best-effort en `backend-ai` (RAG) y expone la búsqueda de citas relevantes por control. La descarga la sirve por streaming; el presigner interno se usa para que `backend-ai` baje el archivo a indexar (ver `ObjectStorageConfig`). Igual que las respuestas, subir/borrar/reindexar evidencia respeta `isSelfAssessmentEditable()`. |
| `ImprovementPlanService` | CRUD del plan de mejora (acciones correctivas por control) + motor de sugerencias automáticas por reglas: una por control no cumplido (con todas sus subcategorías), solo con la madurez calculada y fuera de `DRAFT` (si no, 409). También pide guías prácticas a la IA (`tips`, síncrono) y registra qué se hizo con ellas (`recordFeedback`, tabla `ai_suggestion_feedback`) para un futuro fine-tuning. Al crear una acción con `guideId` marca la guía como usada y registra el feedback en la misma transacción (`AiGuideService.markUsed`). |
| `AiJobService` | Lado API de la cola de IA: encolar la guía (idempotente por organización + control + niveles, con tope de trabajos activos por organización), consultar, listar los propios y cancelar. Reverifica el acceso a la organización en cada consulta. |
| `AiJobQueue` | Máquina de estados del trabajador: reclamo con `FOR UPDATE SKIP LOCKED` y tope global de concurrencia, `complete`/`fail` con reintentos y espera creciente, recuperación de trabajos cuya réplica murió y limpieza. |
| `AiJobWorker` / `AiJobRunner` | Sondeo de la cola bajo ShedLock y ejecución de cada trabajo contra backend-ai, fuera de transacción. Se apaga con `AI_JOBS_ENABLED=false`. |
| `AiGuideService` | Guías de IA persistidas (`GENERATED` → `SHOWN` → `USED`/`EXPIRED`) y su feedback anónimo en el servidor; vencimiento a los 30 días. |
| `AiJobMetrics` | Métricas Prometheus de la cola (espera, ejecución, resultado, reintentos, tokens y tiempos de Ollama). |
| `AuditObservationService` | Observaciones/no conformidades/recomendaciones que un `AUDITOR` registra sobre una evaluación en estado `IN_AUDIT`. |
| `DashboardService` | Agregados de solo lectura para el panel: totales, madurez promedio, distribución por estado/función — acotados por organización salvo rol global. |
| `ReportService` | Genera el reporte de una evaluación en PDF (OpenPDF) y Excel (Apache POI). Sus cuatro PDF (final, preliminar, ejecutivo y plan de mejora) cumplen **PDF/A-1b** (RNF-12) gracias a `PdfA` — ver [PDF/A-1b](#pdfa-1b-rnf-12) más abajo. |
| `AuditLogService` | Escribe la bitácora de auditoría técnica (`audit.audit_logs`) — append-only, desacoplada del modelo de dominio (ver [`diagramas/normalizacion-bd.md`](diagramas/normalizacion-bd.md)). `list()` la consulta (acotado por tenant como el resto de la app) y resuelve en batch (sin N+1) el nombre real de la entidad detrás de cada `resource` (`resolveResourceName` — usuario, organización, evaluación o versión de catálogo). `recordLoginFailure()` es la única escritura que no viene de un request HTTP interactivo, la llama `LoginFailureAuditSyncService`. |
| `AccountService` | Autoservicio sobre la cuenta propia: corregir el nombre, cambiar la contraseña (verificando la actual contra Keycloak) y registrar el inicio y el cierre de sesión en la bitácora. |
| `AuditVerdictService` | Dictamen del auditor por control (`VALIDATED`, `NOT_VALIDATED`, `NEEDS_MORE_EVIDENCE`), solo con la evaluación en `IN_AUDIT`. Convive con las observaciones de auditoría. |
| `EvaluationFlagService` | Marca «para revisar» por control; la ven todos los que acceden a la evaluación. |
| `EvaluationOverviewService` | Estructura de la evaluación (función, categoría, subcategoría, controles por nivel) podada al perfil comunitario, con la prioridad de cada subcategoría. |
| `ControlGuideService` | Guía de interpretación de los controles (interpretación, ejemplo y evidencia esperada): edición, importación y exportación en CSV (`ControlGuideParser`, `ControlGuideCsv`). |
| `TechnicalReportService` | Reporte técnico detallado en PDF: el desglose control por control que respalda el nivel de madurez de cada subcategoría. |
| `AiCitationFeedbackService` | Registra si el auditor aceptó o rechazó cada cita de la IA (tabla `ai_citation_feedback`). |
| `EvidenceRescanScheduler` | Tarea programada que reanaliza con ClamAV las evidencias en cuarentena y las libera o las elimina. |
| `EmailSettingsService` | Configuración del servidor SMTP de Keycloak (recuperación de contraseña, invitaciones) y envío de un correo de prueba. |
| `AlertSettingsService` | Configuración del correo de alertas de Alertmanager: valida, guarda en `alert_settings`, reescribe la configuración de Alertmanager y envía una alerta de prueba. |
| `SessionSettingsService` | Tiempo de inactividad y aviso previo al cierre de sesión. |
| `PlanDeadlineSettingsService` | Plazos por defecto de las acciones del plan de mejora según su prioridad. |
| `PlatformServicesService` | Lanzador de servicios de la infraestructura (Grafana, Kibana, Portainer, etc.) con sus URL editables. |
| `AppVersionService` | Versión y entorno de la instancia (`GET /api/version`). |
| `LoginFailureAuditSyncService` | `@Scheduled` (cada 15s por defecto) — trae desde Keycloak (`KeycloakAdminClient.fetchLoginFailures`) los eventos `LOGIN_ERROR` que Keycloak ya audita solo y los refleja en `AuditLog` como `LOGIN_FAILED`. Necesario porque el login lo maneja Keycloak directamente (Authorization Code + PKCE): `backend-core` nunca ve ese POST. Ver [`SECURITY.md`](../SECURITY.md#registro-de-intentos-de-login-fallidos). |

### `domain/` — entidades y puertos

- `entity/` — 26 entidades JPA, documentadas en
  [`mapeo-jpa.md`](mapeo-jpa.md) y [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md).
- `repository/` — interfaces `JpaRepository`/`Page`-based por entidad; las consultas no triviales (búsqueda por texto, conteos
  filtrados) se declaran con `@Query` JPQL, no SQL nativo.
- `exception/` — `ResourceNotFoundException` (404), `ConflictException` (409),
  `InvalidRequestException` (400) — mapeadas centralmente por `GlobalExceptionHandler`.

### `infrastructure/` — adaptadores hacia sistemas externos

| Clase | Hacia dónde | Nota |
|---|---|---|
| `KeycloakAdminClient` | Keycloak Admin REST API | Usa credenciales de *service account* del client `prisma-backend` (roles `manage-users`/`view-users`/`view-events` de `realm-management`), no un usuario admin humano. `fetchLoginFailures()` (usado por `LoginFailureAuditSyncService`) lee el endpoint de eventos, no de usuarios. |
| `ClamAvClient` | ClamAV (`clamd`, protocolo `INSTREAM`) | Analiza cada evidencia antes de guardarla; si no responde, la evidencia queda en cuarentena. |
| `AiEvidenceClient` | `backend-ai` (RAG de evidencias) | Todas las llamadas son best-effort: si `backend-ai` está caído, la evidencia ya quedó guardada en el almacenamiento/Postgres y el auditor simplemente no ve citas, pero el flujo principal no se interrumpe. |

### `config/` — beans de configuración

| Clase | Qué configura |
|---|---|
| `SecurityConfig` | Cadena de filtros de Spring Security, CORS (`prisma.cors.allowed-origins`), decodificación JWT (issuer de Keycloak o secreto HMAC local), conversión de `realm_access.roles` → `ROLE_*`. |
| `PasswordHasher` | Hash Argon2id vía `Argon2PasswordEncoder` de Spring Security (Bouncy Castle puro-Java — ver el comentario en el código sobre por qué se abandonó `argon2-jvm`). |
| `AiConfig` | Cliente HTTP (`RestClient`) hacia `backend-ai`, con la clave interna `X-Internal-Api-Key`. |
| `KeycloakAdminConfig` | Cliente HTTP hacia la Admin REST API de Keycloak + sus credenciales de service account. |
| `VpnAccessGuard` | Restringe la edición de las URL del lanzador de servicios a las redes de `prisma.services.allowed-cidrs` (`SERVICES_ALLOWED_CIDRS`); el listado no tiene restricción de red. |
| `MaskingPatternLayout` | Enmascara contraseñas, tokens y secretos en los logs (RNF-10). |
| `SchedulerLockConfig` | ShedLock: con varias réplicas, cada tarea programada corre una sola vez. |
| `ClamAvConfig` / `EvidenceConfig` | Conexión con ClamAV y reglas de carga de evidencias (extensiones y tamaño máximo). |
| `ObjectStorageConfig` | Un `S3Client` (AWS SDK v2, path-style, endpoint `S3_ENDPOINT`) y dos `S3Presigner` — interno (`S3_ENDPOINT`) y público (`S3_PUBLIC_ENDPOINT`, hoy sin uso: la descarga se sirve por streaming desde `backend-core`) — todos con región fija (`us-east-1`): así la firma SigV4 queda 100% offline y no necesita conectividad hacia el endpoint público al correr dentro de un contenedor. Propiedades en `prisma.storage.*`. |

### `web/` — capa REST

- `controller/` — 23 controllers, uno por agregado o módulo; cada endpoint mutante declara
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

### PDF/A-1b (RNF-12)

Todos los PDF del sistema cumplen **PDF/A-1b**: los cuatro de `ReportService` (reporte final,
informe preliminar, reporte ejecutivo y plan de mejora) y el reporte técnico detallado de
`TechnicalReportService`. Lo resuelve `application/PdfA.java` con OpenPDF, sin dependencias nuevas.
**Todo PDF nuevo tiene que pasar por `PdfA`**: se llama a `PdfA.prepare(...)` antes de
`document.open()` y a `PdfA.addOutputIntent(...)` justo después. Con el modo PDF/A activo, OpenPDF
falla al generar el documento si queda alguna fuente sin embeber:

- **Fuentes:** Liberation Sans (Regular y Bold, licencia SIL OFL 1.1, métricas compatibles con
  Helvetica) en `src/main/resources/fonts/`, embebidas en subconjunto con codificación Identity-H.
  Todo texto sale con `PdfA.font(...)`, `PdfA.regularBase()` o `PdfA.boldBase()`: **no usar
  `FontFactory.HELVETICA*` ni `BaseFont.HELVETICA`** (no se embeben y rompen la conformidad).
- **Metadatos:** PDF 1.4, `PdfWriter.PDFA1B`, XMP con `pdfaid:part=1` / `pdfaid:conformance=B` y el
  mismo título, autor, creador, productor y fechas que el diccionario Info (veraPDF los compara).
- **Color:** intención de salida con el perfil sRGB del JDK (`PdfA.addOutputIntent`, se llama justo
  después de `document.open()`).
- **Trampa de los elementos vacíos:** un `Paragraph()` o un `Chunk(new LineSeparator(...))` sin texto
  traen Helvetica por defecto y la usan igual para su salto de línea. Crearlos con la fuente
  embebida (`new Paragraph("", PdfA.font(...))`, `chunk.setFont(PdfA.font(...))`).
- **Sin transparencia:** PDF/A-1 la prohíbe. El radar rellena el área del nivel actual con un color
  sólido claro (`RadarChart.CURRENT_FILL`) en lugar de opacidad. No usar `PdfGState` con opacidad,
  máscaras suaves, cifrado ni JavaScript.
- **Sellado (PRI-314):** una firma criptográfica debe agregarse como actualización incremental
  sobre el PDF ya generado; reescribirlo invalidaría la conformidad.

Cómo validarlo con [veraPDF](https://verapdf.org) (Docker, un contenedor a la vez):

```bash
cd apps/backend-core
mvn -o test -Dtest='ReportServiceTest,TechnicalReportServiceTest' -Dpdfa.dumpDir=/ruta/pdfs
docker run --rm -v /ruta/pdfs:/data verapdf/cli --flavour 1b --format text /data/final.pdf
# también preliminar.pdf, ejecutivo.pdf, plan-de-mejora.pdf y reporte-tecnico.pdf; cada uno debe
# responder "PASS"
```

`ReportServiceTest` y `TechnicalReportServiceTest` además comprueban sobre los bytes la declaración
`pdfaid`, las fuentes embebidas, la intención de salida y la ausencia de transparencia; si se define
`VERAPDF_CLI` con el ejecutable de veraPDF, `veraPdfValidatesEveryReportPdfAsPdfA1b` los valida de
punta a punta.

## backend-ai (FastAPI + LangChain + Ollama + ChromaDB)

```
app/
 ├── api/v1/       # Routers FastAPI: evidence.py (ingesta/citas), rag.py (consulta/estado/tips)
 ├── core/         # config.py (Settings), security.py (X-Internal-Api-Key), logging.py
 ├── rag/          # vector_store.py, evidence_store.py, loaders.py, chroma_client.py, ollama_runtime.py
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
- **`rag/ollama_runtime.py`** — opciones de Ollama por pedido (`num_ctx`, `keep_alive`, `num_predict`,
  `temperature`, `seed`, `num_thread`; `reasoning` solo si el modelo declara `thinking`), estimación de
  tokens y `PromptTooLargeError` (el prompt que no entra no llega a Ollama), precalentamiento de LLM y
  embedding al arrancar (reintenta mientras Ollama no responda) e información de runtime para
  `GET /api/v1/runtime`.
- **`rag/loaders.py`** — extracción de texto por tipo de archivo (`.pdf` vía `PyPDFLoader`,
  `.docx`, `.txt`/`.md`).
- **`rag/chroma_client.py`** — fábrica del cliente ChromaDB subyacente: `PersistentClient` local
  (docker-compose y tests). Compartida por
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
 ├── composables/  # useUtils.ts (notificaciones, formateo), useMaturitySummary.ts, useBodyScrollLock.ts
 └── utils/        # constants.ts, helpers.ts, validators.ts
```

- **`services/auth.ts`** — inicialización de `keycloak-js` (`onLoad: 'login-required'`, PKCE
  S256) y refresh de token.
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
- Cómo se prueba cada capa: [`Testing.md`](../desarrollo/Testing.md)
- Modelo de seguridad y endurecimientos recientes: [`SECURITY.md`](../SECURITY.md)
