# 📘 Referencia de API

Cada backend expone su propia especificación **OpenAPI 3.1** en runtime — para el schema completo,
tipado y probar requests interactivamente, usá eso, no este documento:

| Servicio | Swagger UI | Esquema JSON |
| :--- | :--- | :--- |
| **Backend Core** | http://localhost:8081/swagger-ui | http://localhost:8081/v3/api-docs |
| **Backend AI** | http://localhost:8000/docs | http://localhost:8000/openapi.json |

Este documento es el mapa rápido — qué endpoint existe, qué rol lo puede llamar y con qué
restricción de negocio — para no tener que levantar el stack ni bucear el código sólo para saber
si algo existe. Los DTOs completos (todos los campos, cuáles son opcionales) están en
`apps/backend-core/src/main/java/uy/edu/prisma/web/dto/Dto.java` — un único archivo con todos los
`record`s, ver [`Codigo.md`](Codigo.md#web--capa-rest) sobre por qué está así.

## Autenticación

Todos los endpoints de `backend-core` (salvo `/actuator/**` y `/swagger-ui/**`) requieren un JWT de
Keycloak: `Authorization: Bearer <token>`. El token se obtiene vía el flujo normal de Keycloak
(la SPA usa `keycloak-js` con PKCE); para probar por API directo, ver el flujo ROPC documentado en
[`Testing.md`](Testing.md#obtener-un-token-para-probar-la-api-a-mano).

Los endpoints de `backend-ai` (`/api/v1/rag/*`, `/api/v1/evidence/*`) no los llama la SPA — sólo
`backend-core`, autenticado con el header interno `X-Internal-Api-Key` (`AI_INTERNAL_API_KEY`).
Llamarlos sin ese header da `401`.

## Convención de errores

Todos los errores de `backend-core` devuelven el mismo shape (`GlobalExceptionHandler`):

```json
{ "message": "Descripción en español", "code": "CODIGO_MAQUINA", "details": {} }
```

| HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `INVALID_REQUEST` | Regla de negocio violada (ej. transición de estado inválida, evaluación no editable) |
| 400 | `VALIDATION_ERROR` | Bean Validation falló — `details` trae el mapa campo → mensajes |
| 403 | `ACCESS_DENIED` | Rol sin permiso, o `CurrentUserService.assertOrganizationAccess` rechazó por tenant |
| 404 | `NOT_FOUND` | El recurso no existe |
| 409 | `CONFLICT` | Ej. email duplicado al crear un usuario |

> **Nota de orden de evaluación:** en un endpoint con `@PreAuthorize` + `@Valid` (p.ej.
> `POST /api/catalog/import`), Spring resuelve la validación del body **antes** de evaluar el rol
> — un llamado sin permiso con un body inválido recibe `400` con el detalle de validación en vez
> del `403` esperado. No es un bypass de autorización (con un body válido, el mismo rol sin
> permiso sí recibe `403`), pero es una particularidad conocida y sin corregir — ver
> [`CONTRIBUTING.md`](CONTRIBUTING.md) si te encontrás con esto.

## Organizaciones — `/api/organizations`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | `PRISMA_ADMIN` ve todas; `AUDITOR` sólo las que audita; el resto sólo la propia (paginado, `page` 1-indexed) |
| GET | `/{id}` | cualquiera autenticado | 403 si la organización no es la propia (ni auditada, salvo `PRISMA_ADMIN`) |
| POST | `/` | `PRISMA_ADMIN` | `{name, rut?, sector, size, responsibleId?}` — `rut` es **opcional** (Uruguay usa "RUT", no "NIT"); en blanco se normaliza a `NULL` en el backend |
| PUT | `/{id}` | `PRISMA_ADMIN` | mismo shape que POST |
| DELETE | `/{id}` | `PRISMA_ADMIN` | **Baja lógica** (`enabled=false`), no borra la fila — preserva histórico de evaluaciones/usuarios |

## Usuarios — `/api/users`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | mismo criterio de scoping que Organizaciones (por tenant/auditadas/todas) |
| GET | `/{id}` | cualquiera autenticado | un usuario siempre puede ver su propia ficha aunque esté fuera de su tenant |
| POST | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{email, firstName, lastName, organizationIds?, roles[], password, enabled?}` — provisiona la cuenta en Keycloak (sin `password` no puede loguearse nunca). `organizationIds` es la membresía M:N usuario↔organización y su cantidad depende del rol: `ORG_RESPONSIBLE`/`VIEWER` exactamente **1**, `AUDITOR` **≥1**, `INTERNAL_EVALUATOR` cualquier cantidad (con **0** es un evaluador *general* que accede a todas las organizaciones), `PRISMA_ADMIN` ninguna (global; si lo tiene, no se validan los demás roles). Incumplirlo → **400**. `ORG_RESPONSIBLE`: solo puede asignar su propia organización (**403** si pide otra u otorga `PRISMA_ADMIN`) y nunca crea evaluadores generales. En `PUT`, `organizationIds` omitido = sin cambios y `[]` = quitarle todas (solo `PRISMA_ADMIN` las cambia). `enabled` es opcional, `true` si se omite |
| PUT | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | mismo shape; si trae `password`, la resetea (permanente) también en Keycloak. `enabled: false` **deshabilita** al usuario (sincronizado a Keycloak: no puede autenticarse) sin borrarlo — conserva historial, a diferencia de `DELETE`; `enabled` ausente/`null` deja el estado como estaba. **400** si se intenta deshabilitar la propia cuenta o al administrador global sembrado. `ORG_RESPONSIBLE`: **403** si el usuario destino no es de su organización, si intenta reubicarlo en otra, o si intenta otorgar `PRISMA_ADMIN` |
| DELETE | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | **rechaza con 400** si el `id` es el del usuario autenticado actual, o el del administrador global sembrado (`b0000000-0000-0000-0000-000000000001`); `ORG_RESPONSIBLE` además recibe **403** si el usuario destino no es de su organización — ver [`SECURITY.md`](SECURITY.md) |

## Cuenta propia — `/api/account`

| Método | Path | Rol | Notas |
|---|---|---|---|
| PUT | `/profile` | cualquier rol autenticado | `{firstName, lastName}` — corrige el nombre de la cuenta propia (local + Keycloak); no incluye email |
| PUT | `/password` | cualquier rol autenticado | `{currentPassword, newPassword}` — valida la actual contra Keycloak antes de aceptar la nueva |
| POST | `/session-event` | cualquier rol autenticado | `{event}`, `event` ∈ `LOGIN`/`LOGOUT` — el login/logout en sí lo maneja Keycloak directamente (`backend-core` nunca ve ese POST); esto es lo que el frontend llama justo después de autenticarse (una vez por pestaña) y justo antes de desloguear, para que quede una entrada en la bitácora (`GET /api/audit-logs`) |

## Catálogo MCU 5.0 — `/api/catalog`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/versions` | cualquiera autenticado | lista de versiones cargadas (ej. `["5.0"]`) |
| GET | `/{version}` | cualquiera autenticado | árbol completo función→categoría→subcategoría→requisito→control; `?profileId=` acota a un perfil comunitario |
| POST | `/import` | `PRISMA_ADMIN` | da de alta una versión nueva completa (JSON con el árbol armado); afecta a toda la plataforma |
| DELETE | `/{version}` | `PRISMA_ADMIN` | — |

## Perfiles comunitarios — `/api/community-profiles`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | catálogo de referencia compartido, no por tenant |
| GET | `/{id}` | cualquiera autenticado | incluye `controlIds` (el resumen de la lista no) |
| POST | `/` | `PRISMA_ADMIN` | `controlIds` no puede ser vacío |
| PUT | `/{id}` | `PRISMA_ADMIN` | — |
| DELETE | `/{id}` | `PRISMA_ADMIN` | — |

## Evaluaciones — `/api/evaluations`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | filtros `?status=` / `?organizationId=`, scoping por tenant/auditadas/todas |
| GET | `/{id}` | cualquiera autenticado | 403 fuera de tenant/organizaciones auditadas |
| POST | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{name, organizationId, catalogVersion, communityProfileId?}` — nace en `DRAFT` |
| PATCH | `/{id}/status` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR`, `AUDITOR` | `{status}` — **valida la transición** (tabla de estados válidos) y **qué rol puede dispararla**, no sólo que el rol pueda llamar al endpoint. Ver el ciclo de vida completo en el `README.md` y el detalle de reglas en [`Codigo.md`](Codigo.md) |
| DELETE | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | — |
| GET | `/{id}/responses` | cualquiera autenticado (con acceso a la organización) | — |
| POST | `/{id}/responses` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | `{controlId, compliant, observations?}` — **rechaza con 400** si la evaluación no está en `DRAFT`/`IN_PROGRESS`/`RETURNED` (`Evaluation.isSelfAssessmentEditable()`) |
| GET | `/{id}/results` | cualquiera autenticado | resultados de madurez ya calculados (vacío si nunca se calculó). Cada fila trae además `nextLevel` (próximo nivel con controles del perfil pendientes, `null` si no hay brecha accionable) y `missingControls` (controles del perfil de nivel ≤ `nextLevel` sin respuesta «cumple», con `status` `NON_COMPLIANT` o `UNANSWERED`), calculados al vuelo con las respuestas actuales (RF-CALC-07) |
| POST | `/{id}/calculate` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR` | recalcula madurez/brechas — idempotente, pisa el cálculo anterior |

### Ciclo de vida — transiciones válidas

```
DRAFT ──► IN_PROGRESS ──► READY_FOR_AUDIT ──► IN_AUDIT ──┬─► APPROVED ──► ARCHIVED
             ▲                                            └─► RETURNED ──┘
             └────────────────────────────────────────────────┘
```

| Transición | Quién la dispara |
|---|---|
| `DRAFT`/`RETURNED` → `IN_PROGRESS` | `ORG_RESPONSIBLE` o `INTERNAL_EVALUATOR` |
| `IN_PROGRESS` → `READY_FOR_AUDIT` | `ORG_RESPONSIBLE` o `INTERNAL_EVALUATOR` |
| `READY_FOR_AUDIT` → `IN_AUDIT` | `AUDITOR` |
| `IN_AUDIT` → `APPROVED` / `RETURNED` | `AUDITOR` |
| `APPROVED` → `ARCHIVED` | sólo `PRISMA_ADMIN` |

Cualquier otra combinación (saltear estados, retroceder desde uno que no sea `IN_AUDIT`, etc.)
devuelve `400 INVALID_REQUEST`. `PRISMA_ADMIN` puede disparar cualquier transición válida sin
importar el rol que normalmente le correspondería.

## Evidencia — `/api/evidence`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/allowed-formats` | cualquiera autenticado | reglas de carga vigentes: `{ allowedExtensions: ["pdf", "zip", ...], maxSizeBytes }`, con las extensiones sin punto. Salen de `prisma.evidence.allowed-extensions` y `prisma.evidence.max-size` (variables `EVIDENCE_ALLOWED_EXTENSIONS` y `EVIDENCE_MAX_SIZE`); la pantalla la usa para el selector y para avisar antes de enviar |
| GET | `/evaluation/{evaluationId}` | cualquiera autenticado | lista las evidencias; no entrega enlaces de descarga |
| POST | `/` (multipart) | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | form-data `evaluationId`, `controlId?`, `description?`, `file`; **rechaza con 400 si la evaluación no es editable** (mismo criterio que las respuestas) y si la extensión no está en la lista permitida (por defecto `pdf`, `zip`, `docx`, `txt`, `md`, `markdown`, `png`, `jpg`, `jpeg`) o la firma real del archivo no coincide con ella. El mensaje lista las extensiones vigentes. No se admite SVG. **Tamaño máximo por archivo: 50 MB (configurable); si lo supera responde 413 con `El archivo supera el tamaño máximo permitido de 50 MB`** (también cuando lo corta el límite multipart de Spring, antes de llegar al controlador; los Nginx dejan pasar hasta 55 MB para que el rechazo lo dé la aplicación). Los ZIP se revisan solo por los nombres de sus entradas (se rechazan rutas que salen del directorio). **ZIP e imágenes se guardan pero no se mandan a `backend-ai`** (`aiIndexed=false`) |
| DELETE | `/{id}` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR` | mismo bloqueo por estado |
| GET | `/{id}/download` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `AUDITOR` | sirve el archivo por streaming con el JWT, la organización y el rol validados en cada descarga |
| POST | `/{id}/index` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | reintenta la indexación RAG; mismo bloqueo por estado; no hace nada con ZIP e imágenes |
| GET | `/evaluation/{evaluationId}/control/{controlId}/citations` | cualquiera autenticado | fragmentos relevantes de la evidencia YA cargada para ese control, vía `backend-ai` |

## Auditoría — `/api/audit/observations`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{evaluationId}` | `PRISMA_ADMIN`, `AUDITOR`, `ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR` | el administrador y el auditor las ven siempre; la **organización** (responsable o evaluador interno, de esa organización) solo desde que la evaluación vuelve (`RETURNED`, `APPROVED` o `ARCHIVED`) — antes responde `200` con lista vacía, no un error. El `VIEWER` recibe `403`. Cada una trae `controlCode` y `subcategoryCode` (`null` si no está atada a ninguno) |
| POST | `/` | `PRISMA_ADMIN`, `AUDITOR` | `{evaluationId, controlId?, type, description, status?}` — `type` ∈ `OBSERVATION`/`NON_CONFORMITY`/`RECOMMENDATION`, `status` ∈ `OPEN`/`IN_PROGRESS`/`RESOLVED`/`CLOSED` (default `OPEN`) |
| PUT | `/{id}` | `PRISMA_ADMIN`, `AUDITOR` | actualización parcial (sólo los campos presentes); una `description` vacía responde `400` |

Regla de estado: crear (`POST`) y modificar (`PUT`, incluido el cambio de `status`) solo se puede **mientras la evaluación está en `IN_AUDIT`**; en cualquier otro estado responde `409` con el estado actual en el mensaje (igual que el dictamen por control). Escribir es solo del administrador y el auditor: la organización las lee, nunca las edita.

## Plan de mejora — `/api/improvement`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{evaluationId}` | cualquiera autenticado | acciones ya creadas |
| GET | `/{evaluationId}/suggestions` | cualquiera autenticado | sugerencias generadas por reglas a partir de las brechas — no persistidas hasta que se confirman con POST |
| POST | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{evaluationId, controlId?, action, responsible?, priority?, dueDate?, comments?}` — `priority` ∈ `HIGH`/`MEDIUM`/`LOW` (inglés, no `ALTA`/`MEDIA`/`BAJA`); `comments` hasta 2000 caracteres (400 si lo supera) |
| PUT | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | también actualiza `status` (`PENDING`/`IN_PROGRESS`/`COMPLETED`/`OVERDUE`). `comments`: ausente (`null`) = sin cambios; cadena vacía = borra el comentario |

## Reportes — `/api/reports`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{id}/pdf` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reporte final; `Content-Type: application/pdf`. **Solo con la evaluación `APPROVED`**: en cualquier otro estado responde 409 |
| GET | `/{id}/excel` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reporte final; `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. Solo con la evaluación `APPROVED` (409 si no) |
| GET | `/{id}/executive/pdf` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | **reporte ejecutivo gerencial** (RF-REP-01), aparte del reporte final: 1 a 2 páginas con organización y evaluación, perfil comunitario, madurez global, radar por función, las 5 mayores brechas y las 5 acciones principales del plan de mejora (por prioridad y fecha límite; «Sin acciones registradas» si no hay). Solo con la evaluación `APPROVED` (409 si no). El auditor no lo descarga |
| GET | `/{id}/preliminary/pdf`, `/{id}/preliminary/excel` | `PRISMA_ADMIN`, `AUDITOR` | informe preliminar de auditoría. Solo con la evaluación `IN_AUDIT` (409 si no) |

Regla de estado: el informe **preliminar** vale solo en `IN_AUDIT`; el reporte **final** y el **ejecutivo**, solo en `APPROVED`.

## Dashboard — `/api/dashboard`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/stats` | cualquiera autenticado | agregados (`totalEvaluations`, `activeOrganizations`, `avgMaturityLevel`, `evaluationsByStatus`, `maturityByFunction`) — acotados por organización salvo `PRISMA_ADMIN`/`AUDITOR` |

## Administración — `/api/admin/email-settings`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN` | la contraseña SMTP nunca se devuelve (Keycloak la enmascara) |
| PUT | `/` | `PRISMA_ADMIN` | reemplaza la config SMTP del realm de Keycloak entera — ver [`Notificaciones-Email.md`](Notificaciones-Email.md) |

## Bitácora de actividad — `/api/audit-logs`

Distinta de "Auditoría" (`/api/audit/observations`, arriba): esa es la auditoría de cumplimiento
MCU sobre una evaluación puntual; esto es la bitácora técnica de acciones del sistema (quién hizo
qué, cuándo, desde qué IP) — ver [`Arquitectura.md`](Arquitectura.md#trazabilidad-audit-logs).

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `?search=` (texto libre sobre acción/recurso), `?action=` (exacto, ej. `LOGIN_FAILED`), paginado (`page` 1-indexed). `PRISMA_ADMIN` ve todos los tenants; `ORG_RESPONSIBLE` sólo el propio (mismo criterio de aislamiento que el resto de la API) — **no** acepta filtrar por `tenantId` en el body/query, lo decide el rol del token |

Cada fila incluye `resource` (crudo, `"tipo:id"`, ej. `"user:38dab301-..."`) y `resourceName`
(nombre real resuelto de la entidad — `null` si el tipo no tiene entidad propia o ya no existe).
Las acciones registradas hoy: `CREATE`/`UPDATE`/`DELETE`/`UPDATE_STATUS` (por cada agregado),
`UPDATE_PROFILE`/`CHANGE_PASSWORD` (cuenta propia), `SAVE_RESPONSE` (autoevaluación), `LOGIN`/
`LOGOUT` (ver `POST /account/session-event` arriba) y `LOGIN_FAILED` (traído desde Keycloak por
`LoginFailureAuditSyncService`, no por un request HTTP — ver [`SECURITY.md`](SECURITY.md)).

## backend-ai — `/api/v1/*` (sólo llamado por backend-core, requiere `X-Internal-Api-Key`)

| Método | Path | Notas |
|---|---|---|
| GET | `/ping` | health check simple, sin auth |
| GET | `/rag/status` | estado del índice del catálogo MCU 5.0 (`ready`, `collection`, `documents`) |
| POST | `/rag/query` | `{question, k?}` — pregunta libre sobre el catálogo (Llama 3 vía Ollama) |
| POST | `/rag/ingest` | agrega texto a la colección del catálogo |
| POST | `/evaluate/evidence` | `{control_id?, control_description, evidence_text}` — juicio automático sobre si una evidencia satisface un control |
| POST | `/evidence/ingest` | indexa una evidencia recién subida (llamado por `EvidenceService.upload`) |
| POST | `/evidence/citations` | fragmentos relevantes de evidencia ya indexada, acotados a organización/evaluación/control |
| DELETE | `/evidence/{organization_id}/{evidence_id}` | borra los chunks de una evidencia del índice |

## Referencias

- Catálogo de módulos y clases: [`Codigo.md`](Codigo.md)
- Casos de uso con el endpoint exacto de cada uno: [`diagramas/casos-de-uso.md`](diagramas/casos-de-uso.md)
- Cómo probar todo esto (incluida la obtención de un token vía ROPC): [`Testing.md`](Testing.md)
