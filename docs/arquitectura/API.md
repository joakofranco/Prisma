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

Todos los endpoints de `backend-core` (salvo `/actuator/**`, `/swagger-ui/**` y `/api/version`) requieren un JWT de
Keycloak: `Authorization: Bearer <token>`. El token se obtiene vía el flujo normal de Keycloak
(la SPA usa `keycloak-js` con PKCE); para probar por API directo, ver el flujo ROPC documentado en
[`Testing.md`](../desarrollo/Testing.md#obtener-un-token-para-probar-la-api-a-mano).

Los endpoints de `backend-ai` (`/api/v1/rag/*`, `/api/v1/evidence/*`) no los llama la SPA — sólo
`backend-core`, autenticado con el header interno `X-Internal-Api-Key` (`AI_INTERNAL_API_KEY`).
Llamarlos sin ese header da `401`.

`backend-core` no espera para siempre a `backend-ai` (RF-EVI-13): conexión 3 s (`AI_CONNECT_TIMEOUT`),
lectura 60 s para la ingesta y las citas (`AI_READ_TIMEOUT`) y 300 s para la guía de mejora, que
genera con el LLM y con documentos largos en CPU puede tardar minutos (`AI_GUIDE_READ_TIMEOUT`).
La guía corre como **trabajo asíncrono** (ver [`/api/ai/jobs`](#trabajos-de-ia--apiaijobs)), así que
ese timeout no depende del frontend, de nginx ni de Cloudflare. Si se agotan o `backend-ai` está
caído, la evidencia se guarda igual con `aiIndexed = false` y se puede reintentar con
`POST /api/evidence/{id}/index` («Analizar con IA»), sin volver a subirla.

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
> [`CONTRIBUTING.md`](../CONTRIBUTING.md) si te encontrás con esto.

## Organizaciones — `/api/organizations`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | `PRISMA_ADMIN` ve todas; `AUDITOR` sólo las que audita; el resto sólo la propia (paginado, `page` 1-indexed). `?search=` busca en el nombre y en el identificador, sin distinguir mayúsculas, tildes ni puntos; en el identificador además ignora guiones y espacios (`1.234.567-8`, `1234567-8` y `12345678` son lo mismo) |
| GET | `/{id}` | cualquiera autenticado | 403 si la organización no es la propia (ni auditada, salvo `PRISMA_ADMIN`) |
| POST | `/` | `PRISMA_ADMIN` | `{name, identifier?, identifierIsRut?, sector, size, responsibleId?}` — `identifier` es **opcional** (RUT u otro identificador); en blanco se normaliza a `NULL`. Con `identifierIsRut: true` el backend valida el RUT uruguayo (12 dígitos `AA-BBBBBB-001-D`, dígito verificador módulo 11) y lo guarda normalizado; sin la marca exige al menos 8 caracteres (máximo 20). Siempre se rechaza (409) un identificador ya usado por otra organización, comparando sin puntos, guiones ni espacios, sea o no un RUT. Los errores vienen en `details.identifier` (400 inválido, 409 duplicado). La respuesta trae `identifier` e `identifierIsRut` |
| PUT | `/{id}` | `PRISMA_ADMIN` | mismo shape que POST |
| DELETE | `/{id}` | `PRISMA_ADMIN` | **Baja lógica** (`enabled=false`), no borra la fila — preserva histórico de evaluaciones/usuarios |
| PUT | `/{id}/enable` | `PRISMA_ADMIN` | reactiva una organización dada de baja. No rehabilita a sus usuarios: la respuesta trae `disabledUsers`, la cantidad que sigue deshabilitada |

> **El perfil comunitario no es un atributo de la organización.** Estos endpoints no reciben ni
> devuelven un perfil: se elige **por evaluación**, al crearla (`communityProfileId` en
> `POST /api/evaluations`) y se puede cambiar después con `PATCH /api/evaluations/{id}/profile`
> (sujeto a las reglas de `EvaluationService.changeProfile`).

## Usuarios — `/api/users`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | cualquiera autenticado | mismo criterio de scoping que Organizaciones (por tenant/auditadas/todas). `?search=` busca en el email, en «Nombre Apellido» y en el nombre de cualquiera de sus organizaciones, sin distinguir mayúsculas, tildes ni puntos (`ana.perez` y `anaperez` coinciden). Cada usuario trae `locked` (`true` = su cuenta está bloqueada por intentos fallidos ahora; `false` = no; `null` = sin cuenta en Keycloak o Keycloak no respondió) y `lockedUntil` (hasta cuándo, solo si `locked`) |
| GET | `/{id}` | cualquiera autenticado | un usuario siempre puede ver su propia ficha aunque esté fuera de su tenant |
| POST | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{email, firstName, lastName, organizationIds?, roles[], password, enabled?}` — provisiona la cuenta en Keycloak (sin `password` no puede loguearse nunca). `organizationIds` es la membresía M:N usuario↔organización y su cantidad depende del rol: `ORG_RESPONSIBLE`/`VIEWER` exactamente **1**, `AUDITOR` **≥1**, `INTERNAL_EVALUATOR` cualquier cantidad (con **0** es un evaluador *general* que accede a todas las organizaciones), `PRISMA_ADMIN` ninguna (global; si lo tiene, no se validan los demás roles). Incumplirlo → **400**. `ORG_RESPONSIBLE`: solo puede asignar su propia organización (**403** si pide otra u otorga `PRISMA_ADMIN`) y nunca crea evaluadores generales. En `PUT`, `organizationIds` omitido = sin cambios y `[]` = quitarle todas (solo `PRISMA_ADMIN` las cambia). `enabled` es opcional, `true` si se omite |
| PUT | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | mismo shape; si trae `password`, la resetea (permanente) también en Keycloak. `enabled: false` **deshabilita** al usuario (sincronizado a Keycloak: no puede autenticarse) sin borrarlo — conserva historial, a diferencia de `DELETE`; `enabled` ausente/`null` deja el estado como estaba. **400** si se intenta deshabilitar la propia cuenta o al administrador global sembrado. `ORG_RESPONSIBLE`: **403** si el usuario destino no es de su organización, si intenta reubicarlo en otra, o si intenta otorgar `PRISMA_ADMIN` |
| DELETE | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | **rechaza con 400** si el `id` es el del usuario autenticado actual, o el del administrador global sembrado (`b0000000-0000-0000-0000-000000000001`); `ORG_RESPONSIBLE` además recibe **403** si el usuario destino no es de su organización — ver [`SECURITY.md`](../SECURITY.md) |
| POST | `/invitations` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | alta por invitación: crea la cuenta sin contraseña y Keycloak envía un correo para verificar el email y definirla. Mismas reglas de roles y organizaciones que `POST /` |
| GET | `/invitations/availability` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | indica si se pueden enviar invitaciones (requiere el correo de Keycloak configurado) |
| POST | `/{id}/invitation/resend` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reenvía el correo de invitación a un usuario que todavía no la completó |
| POST | `/{id}/unlock` | `PRISMA_ADMIN` | desbloquea una cuenta bloqueada por intentos fallidos de inicio de sesión |

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
| GET | `/{version}/controls` | cualquiera autenticado | lista plana de los controles de la versión (`id`, `code`, `description`, `targetLevel`, requisito y dominio) |
| GET | `/{version}/guide` | `PRISMA_ADMIN` | resumen de la guía de interpretación de controles de la versión |
| GET | `/{version}/guide/items` | `PRISMA_ADMIN` | guía de interpretación, control por control |
| GET | `/{version}/guide/export` | `PRISMA_ADMIN` | exporta la guía en CSV |
| POST | `/{version}/guide` (multipart) | `PRISMA_ADMIN` | importa la guía desde un CSV (`file`) |
| PUT | `/controls/{controlId}/guide` | `PRISMA_ADMIN` | guarda la guía de un control |
| POST | `/guide/delete` | `PRISMA_ADMIN` | borra las guías de los controles indicados |

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
| GET | `/{id}/overview` | cualquiera autenticado (con acceso a la organización) | RF-EVA-04: estructura de la evaluación (función > categoría > subcategoría) podada al perfil comunitario de la evaluación, con descripción, requisitos MCU, controles por nivel (1-4) y `priority` (`ALTA`/`MEDIA`/`BAJA`, o `null`) de cada subcategoría. La prioridad se deriva de los perfiles oficiales del catálogo: `ALTA` si la subcategoría está en el perfil Básico, `MEDIA` si solo llega al Estándar, `BAJA` si solo llega al Avanzado; `null` si esos perfiles no existen. Sin perfil en la evaluación muestra el catálogo completo |
| POST | `/{id}/calculate` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR` | recalcula madurez/brechas — idempotente, pisa el cálculo anterior |
| PATCH | `/{id}/profile` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{communityProfileId}` — cambia el perfil comunitario mientras la evaluación no comenzó; `null` vuelve al catálogo completo |
| GET | `/deletable` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `?ids=` — de los ids pedidos, los de las evaluaciones que se pueden eliminar ahora (accesibles y sin datos asociados) |
| GET | `/{id}/subcategory-comments` | cualquiera autenticado (con acceso a la organización) | comentarios por subcategoría de la evaluación |
| PUT | `/{id}/subcategory-comments/{subcategoryId}` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | `{comment}` (hasta 5000 caracteres); vacío lo elimina |
| GET | `/{id}/audit-trail` | cualquiera autenticado (con acceso a la organización) | historial de ciclos de auditoría de la evaluación (RF-AUD-10) |
| GET | `/{evaluationId}/flags` | cualquiera autenticado (con acceso a la organización) | controles marcados para revisar |
| PUT, DELETE | `/{evaluationId}/flags/{controlId}` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE`, `AUDITOR` | marca o desmarca un control |

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
| `DRAFT` / `IN_PROGRESS` / `RETURNED` / `APPROVED` → `ARCHIVED` | `ORG_RESPONSIBLE` |

Archivar no está permitido desde `READY_FOR_AUDIT` ni `IN_AUDIT` (hay una auditoría en marcha).

Cualquier otra combinación (saltear estados, retroceder desde uno que no sea `IN_AUDIT`, etc.)
devuelve `400 INVALID_REQUEST`. `PRISMA_ADMIN` puede disparar cualquier transición válida sin
importar el rol que normalmente le correspondería.

## Evidencia — `/api/evidence`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/allowed-formats` | cualquiera autenticado | reglas de carga vigentes: `{ allowedExtensions: ["pdf", "zip", ...], maxSizeBytes }`, con las extensiones sin punto. Salen de `prisma.evidence.allowed-extensions` y `prisma.evidence.max-size` (variables `EVIDENCE_ALLOWED_EXTENSIONS` y `EVIDENCE_MAX_SIZE`); la pantalla la usa para el selector y para avisar antes de enviar |
| GET | `/evaluation/{evaluationId}` | cualquiera autenticado | lista las evidencias; no entrega enlaces de descarga |
| POST | `/` (multipart) | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | form-data `evaluationId`, `controlId?`, `description?`, `file`; **antes de guardar, el archivo se analiza con ClamAV** (RNF-09): si tiene malware responde **422** con `code=MALWARE_DETECTED` y la firma en `details.signature`, y no se guarda. **Si el antivirus no responde, el archivo se guarda en cuarentena** (`scanStatus=QUARANTINED` en la respuesta y en el listado): no se puede descargar (409) ni reindexar (409) y no se indexa en el asistente de IA hasta que una tarea programada lo reanalice y lo libere (`CLEAN`) o lo rechace y borre (`INFECTED`). Cada resultado queda en la bitácora (`EVIDENCE_SCAN_CLEAN`, `EVIDENCE_SCAN_INFECTED`, `EVIDENCE_QUARANTINED`, `EVIDENCE_QUARANTINE_RELEASED`, `EVIDENCE_QUARANTINE_INFECTED`); **rechaza con 400 si la evaluación no es editable** (mismo criterio que las respuestas) y si la extensión no está en la lista permitida (por defecto `pdf`, `zip`, `docx`, `txt`, `md`, `markdown`, `png`, `jpg`, `jpeg`) o la firma real del archivo no coincide con ella. El mensaje lista las extensiones vigentes. No se admite SVG. **Tamaño máximo por archivo: 100 MB (configurable); si lo supera responde 413 con `El archivo supera el tamaño máximo permitido de 100 MB`** (también cuando lo corta el límite multipart de Spring, antes de llegar al controlador; los Nginx dejan pasar hasta 105 MB para que el rechazo lo dé la aplicación). Los ZIP se revisan solo por los nombres de sus entradas (se rechazan rutas que salen del directorio). **ZIP e imágenes se guardan pero no se mandan a `backend-ai`** (`aiIndexed=false`) |
| DELETE | `/{id}` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR` | mismo bloqueo por estado |
| GET | `/{id}/download` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `AUDITOR` | sirve el archivo por streaming con el JWT, la organización y el rol validados en cada descarga |
| POST | `/{id}/index` | `PRISMA_ADMIN`, `INTERNAL_EVALUATOR`, `ORG_RESPONSIBLE` | reintenta la indexación RAG; mismo bloqueo por estado; no hace nada con ZIP e imágenes |
| GET | `/evaluation/{evaluationId}/control/{controlId}/citations` | `PRISMA_ADMIN`, `AUDITOR` | fragmentos relevantes de la evidencia YA cargada para ese control, vía `backend-ai` (RF-EVI-14). **Solo con la evaluación `IN_AUDIT`**: en otro estado responde `409` con el estado actual. Si `backend-ai` no responde (caído, colgado o con error) responde `503` con `code: AI_UNAVAILABLE`; una lista vacía significa «sin resultados» |
| POST | `/citations/feedback` | `PRISMA_ADMIN`, `AUDITOR` | el auditor acepta o rechaza citas de la IA: `{evaluationId, items:[{controlId, fileName?, location?, snippet, score?, decision, evidenceId?, citationId?}], confirmVerdictOverride?}` (hasta 100). `decision` ∈ `ACCEPTED`/`REJECTED`. **Aceptar una cita significa que el fragmento es relevante y respalda el control** (D5): en la **misma transacción** el control pasa a `VALIDATED` con origen `CITATION` y la cita como respaldo (`evidenceId`, archivo, ubicación, puntaje y el fragmento recortado). Reglas: sin dictamen → `VALIDATED`; ya `VALIDATED` → sin cambios (la cita es un respaldo adicional y se conserva el origen); `NOT_VALIDATED` o `NEEDS_MORE_EVIDENCE` puesto a mano → **`409` `VERDICT_OVERRIDE_REQUIRED`** (con `details.currentVerdict`, `controlCode` y `controlId`) salvo que se repita el pedido con `confirmVerdictOverride=true`; no se escribe nada si falta la confirmación. **Rechazar una cita (una o todas) nunca cambia el dictamen** (D1). Pasar de aceptada a rechazada no revierte el dictamen: eso llega con la persistencia de citas (hito 3). Cada cambio de dictamen queda en la bitácora como `SET_CONTROL_VERDICT` con `source` y la cita. Responde `200` `{verdicts:[{controlId, verdict, source, changed}]}` (vacío si solo hubo rechazos); `404` si un control no existe; `409` fuera de `IN_AUDIT`. La evaluación solo valida acceso y estado y **no se guarda** en el dataset (`ai_citation_feedback` sigue siendo anónimo): se registra el control y el fragmento, para medir la búsqueda y un futuro fine-tuning |

## Auditoría — `/api/audit/observations`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{evaluationId}` | `PRISMA_ADMIN`, `AUDITOR`, `ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR` | el administrador y el auditor las ven siempre; la **organización** (responsable o evaluador interno, de esa organización) solo desde que la evaluación vuelve (`RETURNED`, `APPROVED` o `ARCHIVED`) — antes responde `200` con lista vacía, no un error. El `VIEWER` recibe `403`. Cada una trae `controlCode` y `subcategoryCode` (`null` si no está atada a ninguno) |
| POST | `/` | `PRISMA_ADMIN`, `AUDITOR` | `{evaluationId, controlId?, type, description, status?, evidenceId?, citation?}` — `type` ∈ `OBSERVATION`/`NON_CONFORMITY`/`RECOMMENDATION`, `status` ∈ `OPEN`/`IN_PROGRESS`/`RESOLVED`/`CLOSED` (default `OPEN`). `citation` (`{fileName?, location?, snippet, score?, prefillText}`) se manda al guardar desde **«Registrar como observación»** de una cita: se registra el feedback de la diferencia (C4b) en `ai_citation_validation_feedback`, anónimo (control, archivo, ubicación, fragmento, puntaje, `prefill_text`, `final_text`, `final_type` y resultado `UNEDITED`/`EDITED`, sin organización, evaluación ni usuario). Se escribe solo al guardar; `400` si trae `citation` sin `controlId`. **No dictamina el control** (el tipo puede ser una no conformidad) |
| PUT | `/{id}` | `PRISMA_ADMIN`, `AUDITOR` | actualización parcial (sólo los campos presentes); una `description` vacía responde `400` |

### Dictamen por control — `/api/audit/verdicts`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{evaluationId}` | `PRISMA_ADMIN`, `AUDITOR` | dictámenes del auditor por control (`controlId`, `verdict`, quién lo fijó y cuándo, `source` `MANUAL`/`CITATION` y, si vino de una cita, `sourceRef`) |
| PUT | `/{evaluationId}/controls/{controlId}` | `PRISMA_ADMIN`, `AUDITOR` | `{verdict}` — fija el dictamen de un control **a mano** (origen `MANUAL`: borra el origen `CITATION` si lo tenía). Solo con la evaluación `IN_AUDIT` |

**Evidencia citada (RF-EVI-15):** `POST` y `PUT` aceptan un `evidenceId` opcional, la evidencia de la que sale la observación (se manda al registrarla desde una cita de la IA). Tiene que ser de **la misma evaluación**: si es de otra responde `400`, y `404` si no existe. En `PUT`, si no se manda se conserva el vínculo. Las observaciones devuelven `evidenceId` y `evidenceFileName` (la organización ve de qué documento habla; son `null` si no viene de una cita o si la evidencia se borró). La ubicación y el fragmento citados siguen viajando en la descripción.

Regla de estado: crear (`POST`) y modificar (`PUT`, incluido el cambio de `status`) solo se puede **mientras la evaluación está en `IN_AUDIT`**; en cualquier otro estado responde `409` con el estado actual en el mensaje (igual que el dictamen por control). Escribir es solo del administrador y el auditor: la organización las lee, nunca las edita.

## Plan de mejora — `/api/improvement`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{evaluationId}` | cualquiera autenticado | acciones ya creadas |
| GET | `/{evaluationId}/suggestions` | cualquiera autenticado | sugerencias generadas por reglas a partir de las brechas — no persistidas hasta que se confirman con POST. **Precondición (RF-PLN-01):** la madurez tiene que estar calculada (`globalMaturity` no nulo) y la evaluación no puede estar en `DRAFT`; si no, `409` «Calculá la madurez de la evaluación antes de generar sugerencias de mejora». Devuelve **una sugerencia por control** (no una por subcategoría): `subcategoryId`/`subcategoryName` son la subcategoría principal (la primera por nombre) y `subcategoryNames` lista todas las subcategorías donde el control cuenta |
| POST | `/suggestions/tips` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | guía práctica con IA para una brecha: `{controlCode, description, functionName?, categoryName?, subcategoryName?, currentLevel, targetLevel}` → `{summary, tips[], generated}`. Con la IA caída responde `200` con un texto de reemplazo y `generated=false` (la pantalla no lo ofrece para usar ni lo registra). Con Ollama local puede tardar ~1 min |
| POST | `/suggestions/tips/async` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | versión asíncrona de la guía: `{controlId, evaluationId?, functionName?, categoryName?, subcategoryName?, currentLevel, targetLevel}` → **`202`** `{jobId, status}` en menos de 1 s. El código y la descripción del control los resuelve el servidor. El resultado se consulta en `GET /api/ai/jobs/{jobId}`. Pedir lo mismo mientras hay un trabajo activo (misma organización, control y niveles) devuelve ese trabajo, no crea otro. `404` control o evaluación inexistente; `403` si la evaluación es de otra organización; `429` si la organización ya tiene demasiados trabajos activos (`AI_JOBS_MAX_PENDING_PER_ORG`, 20). Convive con el endpoint síncrono |
| POST | `/suggestions/feedback` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | registra qué se hizo con las guías de IA, para un futuro fine-tuning: `{items:[{controlId, currentLevel, targetLevel, originalSummary, originalTips[], outcome, editedText?}]}` (hasta 200). `outcome` ∈ `ACCEPTED_UNEDITED` (usada y creada sin cambios), `ACCEPTED_EDITED` (usada, creada y modificada; `editedText` obligatorio con el texto final) y `REJECTED` (generada pero no usada, deseleccionada, regenerada o con el modal cerrado). Responde `204`; `404` si un control no existe. Se guarda el **control** del catálogo, sin evaluación, organización ni usuario. Lo que la IA no llegó a generar no se registra |
| POST | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `{evaluationId, controlId?, action, responsible?, priority?, dueDate?, comments?, guideId?, guideEdited?}` — `guideId` es la guía de IA (trabajo asíncrono) con la que se armó la acción: en la **misma transacción** se la marca `USED` y se registra el feedback (`ACCEPTED_UNEDITED`, o `ACCEPTED_EDITED` con `action` como texto final si `guideEdited=true`); `400` si la guía ya se usó, venció o es de otro control; `403` si es de otra organización. `priority` ∈ `HIGH`/`MEDIUM`/`LOW` (inglés, no `ALTA`/`MEDIA`/`BAJA`); `comments` hasta 2000 caracteres (400 si lo supera) |
| PUT | `/{id}` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | también actualiza `status` (`PENDING`/`IN_PROGRESS`/`COMPLETED`/`OVERDUE`). `comments`: ausente (`null`) = sin cambios; cadena vacía = borra el comentario |

## Trabajos de IA — `/api/ai/jobs`

Cola de trabajos de IA (hoy solo `GUIDE`). Mismos roles que la función de origen: `PRISMA_ADMIN`,
`ORG_RESPONSIBLE`. La organización del trabajo se **reverifica en cada consulta** (conocer el id no
alcanza: otra organización recibe `403`).

| Método | Path | Notas |
|---|---|---|
| GET | `/{id}` | estado del trabajo: `{id, type, status, queuePosition, attempts, maxAttempts, errorCode, errorMessage, createdAt, startedAt, finishedAt, guide}`. `status` ∈ `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`. `queuePosition` solo en cola (1 = el próximo). `guide` (`{id, controlId, summary, tips[], model}`) solo con `SUCCEEDED`; la primera vez que quien la pidió la consulta, la guía pasa de `GENERATED` a `SHOWN` |
| GET | `/?mine=true` | los últimos 50 trabajos de quien consulta (sin el cuerpo de la guía): alimenta el indicador «Tareas de IA» |
| DELETE | `/{id}` | cancela un trabajo **en cola**. `409` si ya se está ejecutando o ya terminó. Cancelar uno ya cancelado es idempotente |

Códigos de error de un trabajo fallido (`errorCode`): `AI_UNAVAILABLE` (no se pudo conectar con
backend-ai, o respondió 502/503/504) y `AI_ERROR` (5xx) se reintentan hasta `AI_JOBS_MAX_ATTEMPTS` (2)
con espera creciente (`AI_JOBS_RETRY_BACKOFF_SECONDS`, 30 s, el doble en cada reintento);
`AI_TIMEOUT` (backend-ai aceptó el pedido pero no contestó dentro de `AI_GUIDE_READ_TIMEOUT`),
`PROMPT_TOO_LARGE` (el prompt no entra en el contexto del modelo), `AI_REJECTED` (4xx) e
`INTERNAL_ERROR` no se reintentan; `WORKER_LOST` (la réplica murió a mitad de camino) se reencola.

Cómo funciona: cada réplica de `backend-core` mira la cola cada `AI_JOBS_POLL_INTERVAL_MS` (2 s). El
reclamo corre bajo ShedLock y usa `SELECT … FOR UPDATE SKIP LOCKED`: un trabajo nunca se ejecuta dos
veces aunque haya varias réplicas, y el tope **global** de trabajos corriendo contra Ollama es
`AI_JOBS_CONCURRENCY` (2). Los resultados se conservan `AI_JOBS_RESULT_TTL_DAYS` (30) días. Una
limpieza diaria (03:15) vence los resultados viejos y cierra las guías: una guía **mostrada y no
usada** a los 30 días (`AI_GUIDE_FEEDBACK_DAYS`) pasa a `EXPIRED` y se registra como `REJECTED`; una
nunca mostrada solo vence. El feedback sigue sin organización ni usuario (V32).

Métricas (Prometheus, `/actuator/prometheus`): `prisma_ai_job_queue_wait_seconds`,
`prisma_ai_job_run_seconds`, `prisma_ai_jobs_finished_total{type,outcome,error}`,
`prisma_ai_job_retries_total`, `prisma_ai_jobs{status="queued|running"}`,
`prisma_ai_ollama_tokens_total{kind="prompt|completion"}` y
`prisma_ai_ollama_seconds_total{phase="prompt_eval|generation|load"}`.

### Lote de guías y evidencia global (hito 4)

**Lote de guías (F3).** `POST /api/improvement/suggestions/tips/batch` (`PRISMA_ADMIN`, `ORG_RESPONSIBLE`):
`{items:[{controlId, evaluationId?, functionName?, categoryName?, subcategoryName?, currentLevel, targetLevel}]}`
(1 a 100 ítems; el tope efectivo es `AI_JOBS_MAX_BATCH_SIZE`, 20) → **`202`** `{batchId, jobs:[{controlId, jobId, status}]}`.
Crea **un trabajo `GUIDE` por control** con **prioridad de lote** (0): un pedido interactivo suelto pasa
siempre antes. Un control que ya tiene un trabajo activo (misma organización, control y niveles) **reutiliza
ese trabajo** y no se duplica (incluido el mismo control repetido en el lote). Topes: `400` si el lote supera
`AI_JOBS_MAX_BATCH_SIZE`; `429` si sumaría más de `AI_JOBS_MAX_PENDING_BATCH_PER_ORG` (60) trabajos de lote
activos en la organización. Los trabajos de lote **no cuentan** contra el tope de pedidos sueltos
(`AI_JOBS_MAX_PENDING_PER_ORG`), que ahora cuenta solo guías sueltas (los trabajos del sistema —indexar,
precalcular, clasificar— tampoco lo agotan).

Avance: `GET /api/ai/jobs?ids=<id>,<id>,…` (hasta 100) devuelve el estado de varios trabajos de una vez,
con la guía de los que terminaron (el mismo `AiJobDto`, que ahora trae `controlId` y `batchId`); los ids que ya
no existen se omiten y la organización de cada trabajo se verifica. `DELETE /api/ai/jobs/batches/{batchId}` →
`{cancelled}`: cancela de un lote lo que **sigue en cola** (lo que ya corre termina). No hay pregeneración
de guías (D12): el lote se pide a mano.

**Evidencia global (F4).** Una evidencia subida **sin control** se indexa con `control_id = ""`.
1. *Citas:* la búsqueda de citas de un control incluye ahora también los fragmentos de la evidencia global de
   la misma evaluación (D7); cada cita trae `unassigned` (`true` = «sin control asignado») y se guarda con esa
   marca. Aceptarla valida el control como cualquier cita.
2. *Preclasificación:* cuando una evidencia global termina de indexarse se encola un trabajo
   `EVIDENCE_CLASSIFY` (lote, del sistema) que compara cada control de la evaluación (acotados a su perfil
   comunitario, si lo tiene) con los fragmentos de esa evidencia y guarda los **3 más probables** como
   sugerencias con su puntaje. `GET /api/evidence/evaluation/{evaluationId}/suggestions` (`PRISMA_ADMIN`,
   `AUDITOR`, evaluación `IN_AUDIT`, `409` si no) las lista: `{id, evidenceId, fileName, controlId,
   controlCode, controlDescription, score, rank, status}`. `POST /api/evidence/suggestions/{id}/decision`
   `{decision: ACCEPTED|REJECTED}` las decide. **Son solo sugerencias (D7): aceptar una NO asigna la
   evidencia a ese control** (una política típica respalda varios); el control pasa a la lista de la
   auditoría y los fragmentos de la evidencia global ya entran en sus citas. Cada decisión deja una fila
   anónima y mínima en `ai_evidence_classification_feedback` (control, puntaje, posición, decisión: sin
   organización, evaluación, usuario, archivo ni texto, D15). Al volver a clasificar se conservan las
   decididas (y no se vuelven a sugerir) y se rehacen las pendientes. `backend-ai`: `POST
   /api/v1/evidence/classify` (`{organization_id, evaluation_id, evidence_id, candidates:[{control_id, query}],
   top_n}` → `{suggestions:[{control_id, score, fragment_id}]}`); `AI_CLASSIFY_MIN_SCORE` (sin definir = los 3
   mejores) filtra por puntaje.

### Citas guardadas e indexación con estado (hito 3)

**Citas guardadas (C2).** `GET /api/evidence/evaluation/{e}/control/{c}/citations` devuelve **primero lo guardado**
(tabla `evidence_citation_result`, ligada a la evaluación) y solo busca en `backend-ai` si no hay nada guardado o
si la evidencia del control cambió. Cada cita trae `citationId`, `fragmentId` (id estable de `backend-ai`:
evidencia + posición del fragmento), `status` (`PENDING`, `ACCEPTED`, `REJECTED`; `VALIDATED` está reservado),
`position`, `controlId` y `stale`. Con `backend-ai` caído y citas guardadas (viejas), devuelve las guardadas en
vez de `503`. Se guardan los fragmentos que `backend-ai` devuelve; el puntaje mínimo (`AI_CITATION_MIN_SCORE`) se
aplica antes.

Ciclo de vida: la decisión de una cita (`POST /api/evidence/citations/feedback` con `citationId`) queda guardada
(estado, quién y cuándo) en la misma transacción que el dictamen. Cuando **cambia la evidencia de un control**
(una indexación termina) las citas sin decidir se descartan y las decididas se marcan `stale`: la próxima consulta
busca de nuevo y conserva las decisiones de los fragmentos que siguen. Al **salir de auditoría** (devuelta o
aprobada) se descartan todas las citas guardadas; el dictamen ya fijado **no se toca** (conserva su cita en
`source_ref`). Al entrar a auditoría se encola `CITATIONS_PRECOMPUTE` (lote, prioridad 0): busca y guarda las
citas de los controles con evidencia ya indexada, para que el auditor las encuentre listas.

Completan C4a: **pasar una cita de aceptada a rechazada** revierte el dictamen a «Sin dictamen» si vino de la cita
(`source = CITATION`) y no queda otra cita aceptada del control (si quedan, el dictamen se mantiene y el respaldo
pasa a otra; uno fijado a mano no se toca). `DELETE /api/audit/verdicts/{evaluationId}/controls/{controlId}/citation-verdict`
(`PRISMA_ADMIN`, `AUDITOR`, evaluación `IN_AUDIT`) es el **«Deshacer»**: quita un dictamen que salió de aceptar una
cita y deja esas citas `PENDING` (`204`; `409` si el dictamen lo fijó el auditor a mano; `404` si no hay dictamen).
La respuesta de `POST /citations/feedback` informa una reversión con `verdict = null` y `changed = true`. La
bitácora recoge cada cambio como `SET_CONTROL_VERDICT` con `reverted`, `reason` (`CITATION_REJECTED` o `UNDO`) y la
cita.

**Indexación con estado (C7).** Cada `EvidenceDto` trae `indexStatus` y `indexError`: `QUEUED`, `INDEXING`,
`INDEXED`, `ERROR` (se puede reintentar con `POST /api/evidence/{id}/index`) y `NOT_INDEXABLE` (ZIP, imágenes, en
cuarentena —con el motivo— o infectada). La subida **ya no espera a `backend-ai`**: la evidencia indexable queda
`QUEUED` y un trabajo `EVIDENCE_INDEX` de la misma cola que las guías la indexa (prioridad 5, trabajo del sistema:
no aparece en «Tareas de IA»), con los reintentos y la espera creciente de la cola; si se agotan, queda `ERROR` con
el motivo. `aiIndexed` es `true` solo con `INDEXED`. Al liberarse de cuarentena se encola sola.

## Reportes — `/api/reports`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/{id}/pdf` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reporte final; `Content-Type: application/pdf`. **Solo con la evaluación `APPROVED`** (o `ARCHIVED` después de haber sido aprobada): en cualquier otro estado responde 409 |
| GET | `/{id}/excel` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reporte final; `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. Solo con la evaluación `APPROVED` (409 si no) |
| GET | `/{id}/executive/pdf` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | **reporte ejecutivo gerencial** (RF-REP-01), aparte del reporte final: 1 a 2 páginas con organización y evaluación, perfil comunitario, madurez global, radar por función, las 5 mayores brechas y las 5 acciones principales del plan de mejora (por prioridad y fecha límite; «Sin acciones registradas» si no hay). Solo con la evaluación `APPROVED` (409 si no). El auditor no lo descarga |
| GET | `/{id}/technical/pdf` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | reporte técnico detallado. Mismo criterio de estado que el reporte final |
| GET | `/{id}/preliminary/pdf`, `/{id}/preliminary/excel` | `PRISMA_ADMIN`, `AUDITOR` | informe preliminar de auditoría. Solo con la evaluación `IN_AUDIT` (409 si no) |
| GET | `/{id}/improvement-plan/pdf`, `/{id}/improvement-plan/excel` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `AUDITOR` | **exportación del plan de mejora** (RF-PLN-05): acción, control, prioridad, estado, responsable, vencimiento y comentarios, ordenado por prioridad y fecha límite. El PDF es A4 horizontal con un resumen de avance por estado y prioridad; el Excel es una hoja «Plan de mejora» con filtros y primera fila fija. Una acción abierta con fecha vencida figura como «Vencida». No depende del estado de la evaluación. **400 si el plan no tiene acciones** |

Regla de estado: el informe **preliminar** vale solo en `IN_AUDIT`; el reporte **final** y el **ejecutivo**, solo en `APPROVED`. La exportación del plan de mejora no tiene restricción de estado.

## Versión y entorno — `/api/version`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | **público** (lo muestra la pantalla de login) | `{application, version, environment, development, commit?, buildTime?}`. Sin sesión trae solo `version` (la del `pom.xml`) y `environment` (`development`, `staging` o `production`, de `APP_ENV`; `development` también si no está definida, para que un olvido en producción se note); con sesión suma `commit` y `buildTime` si el CI los pasó al construir la imagen. `development: true` cuando el entorno es de desarrollo. También está en `/actuator/info` (red interna). |

`backend-ai` informa lo mismo (solo `version` y `environment`) en `GET /health`. La versión es una sola
para todos los servicios y se cambia con `scripts/version.sh bump X.Y.Z`
(ver [`Ambientes-Despliegue.md`](../operacion/Ambientes-Despliegue.md#versión-y-entorno)).

## Dashboard — `/api/dashboard`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/stats` | cualquiera autenticado | agregados (`totalEvaluations`, `activeOrganizations`, `avgMaturityLevel`, `evaluationsByStatus`, `maturityByFunction`) — acotados por organización salvo `PRISMA_ADMIN`/`AUDITOR` |

## Administración — `/api/admin/email-settings`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN` | la contraseña SMTP nunca se devuelve (Keycloak la enmascara) |
| PUT | `/` | `PRISMA_ADMIN` | reemplaza la config SMTP del realm de Keycloak entera — ver [`Notificaciones-Email.md`](../operacion/Notificaciones-Email.md) |
| POST | `/test` | `PRISMA_ADMIN` | `{recipientEmail}` — envía un correo de prueba con la configuración SMTP guardada |

## Alertas del sistema — `/api/admin/alert-settings`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN` | configuración del correo de alertas de Alertmanager. La contraseña SMTP nunca se devuelve; `passwordSet` indica si hay una guardada y `configured` si alguna vez se guardó desde la plataforma |
| PUT | `/` | `PRISMA_ADMIN` | `{recipients, smtpHost, smtpPort, from, authEnabled, username?, password?, requireTls}` — hasta 10 destinatarios separados por coma. Con autenticación, la contraseña es obligatoria la primera vez; después, vacía conserva la guardada. Reescribe la configuración de Alertmanager y le pide recargarla |
| POST | `/test` | `PRISMA_ADMIN` | comprueba la conexión con el servidor SMTP guardado y envía una alerta de prueba |

## Lanzador de servicios — `/api/admin/services`

El listado se sirve a cualquier `PRISMA_ADMIN`, desde cualquier red: los enlaces apuntan a direcciones de la VPN y solo abren conectado a ella. Las operaciones que modifican las URL exigen además llegar desde una red permitida (`SERVICES_ALLOWED_CIDRS`); si no, responden `403` con `code: VPN_REQUIRED`.

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN` | servicios de la infraestructura (Grafana, Kibana, Portainer, etc.) con su URL. Sin restricción de red |
| PUT | `/{id}` | `PRISMA_ADMIN` | `{url}` — cambia la URL de un servicio. Solo desde una red permitida |
| POST | `/{id}/reset` | `PRISMA_ADMIN` | restablece la URL por defecto. Solo desde una red permitida |
| POST | `/replace-host` | `PRISMA_ADMIN` | `{host}` — reemplaza el host en las URL de todos los servicios (nombre o IP, sin esquema, puerto ni ruta). Solo desde una red permitida |

## Parámetros de la plataforma — `/api/settings`

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/session` | cualquiera autenticado | `{idleTimeoutMinutes, warningSeconds}` — cierre de sesión por inactividad |
| PUT | `/session` | `PRISMA_ADMIN` | mismo shape |
| GET | `/plan-deadlines` | cualquiera autenticado | `{highMonths, mediumMonths, lowMonths}` — plazos por defecto de las acciones del plan de mejora según su prioridad |
| PUT | `/plan-deadlines` | `PRISMA_ADMIN` | mismo shape |

## Bitácora de actividad — `/api/audit-logs`

Distinta de "Auditoría" (`/api/audit/observations`, arriba): esa es la auditoría de cumplimiento
MCU sobre una evaluación puntual; esto es la bitácora técnica de acciones del sistema (quién hizo
qué, cuándo, desde qué IP) — ver [`Arquitectura.md`](Arquitectura.md#trazabilidad-audit-logs).

| Método | Path | Rol | Notas |
|---|---|---|---|
| GET | `/` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE` | `?search=` (texto libre, sin distinguir mayúsculas ni tildes; ver «Qué busca `?search=`» abajo), `?action=` (exacto, ej. `LOGIN_FAILED`), paginado (`page` 1-indexed). `PRISMA_ADMIN` ve todos los tenants; `ORG_RESPONSIBLE` sólo el propio (mismo criterio de aislamiento que el resto de la API) — **no** acepta filtrar por `tenantId` en el body/query, lo decide el rol del token |
| GET | `/export` | `PRISMA_ADMIN`, `ORG_RESPONSIBLE`, `AUDITOR` | exporta la bitácora en CSV con los mismos filtros (`?search=`, `?action=`) |

Cada fila incluye `resource` (crudo, `"tipo:id"`, ej. `"user:38dab301-..."`) y `resourceName`
(nombre real resuelto de la entidad — `null` si el tipo no tiene entidad propia o ya no existe).
Las acciones registradas hoy: `CREATE`/`UPDATE`/`DELETE`/`UPDATE_STATUS` (por cada agregado),
`UPDATE_PROFILE`/`CHANGE_PASSWORD` (cuenta propia), `SAVE_RESPONSE` (autoevaluación), `LOGIN`/
`LOGOUT` (ver `POST /account/session-event` arriba) y `LOGIN_FAILED` (traído desde Keycloak por
`LoginFailureAuditSyncService`, no por un request HTTP — ver [`SECURITY.md`](../SECURITY.md)).

**Qué busca `?search=`** (PRI-368) — lo que muestra la pantalla **y** los códigos internos, así quien
administra puede escribir lo que ve o pegar un identificador:

- Usuario que hizo la acción: email o «Nombre Apellido».
- Organización: nombre.
- Acción: código (`LOGIN`) o etiqueta en español (`Inicio de Sesión`). Es búsqueda por contenido:
  «Inicio de Sesión» también incluye «Inicio de Sesión Fallido»; para una acción exacta usar `?action=`.
- Recurso afectado: el texto crudo (`user:<uuid>`, o el email intentado en un `LOGIN_FAILED`), el
  tipo en español (`Usuario`, `Evaluación`, …) y el nombre de la entidad referenciada (usuario,
  organización, evaluación, versión de catálogo).
- IP y los `uuid` del usuario y de la organización de la fila. Como se comparan contra la propia
  bitácora, un `uuid` sigue encontrando filas **aunque el usuario o el recurso ya se hayan eliminado**
  (la bitácora es append-only).

El mismo filtro vale para el export CSV. Las etiquetas en español están duplicadas en
`AuditLogService` (backend) y `apps/frontend/src/utils/constants.ts`: si se agrega una acción o un
tipo nuevo, sumarlo en los dos lados.

## backend-ai — `/api/v1/*` (sólo llamado por backend-core, requiere `X-Internal-Api-Key`)

| Método | Path | Notas |
|---|---|---|
| GET | `/ping` | health check simple, sin auth |
| GET | `/health` (fuera de `/api/v1`) | estado del servicio, más `version` y `environment` |
| GET | `/rag/status` | estado del índice del catálogo MCU 5.0 (`ready`, `collection`, `documents`) |
| POST | `/rag/query` | `{question, k?}` — pregunta libre sobre el catálogo (Llama 3 vía Ollama) |
| POST | `/rag/ingest` | agrega texto a la colección del catálogo |
| POST | `/rag/remediation-tips` | guía de remediación: `{control_code, control_description, function_name?, category_name?, subcategory_name?, current_level, target_level}` → `{summary, tips[], generated, model, metrics}`. `generated=false` = el LLM no respondió y `tips` es un texto de reemplazo (la cola de backend-core lo trata como falla reintentable). `metrics` trae los tiempos (ns) y tokens que informa Ollama. Si el cliente corta la conexión mientras el LLM genera, se **aborta** la generación (log «Cliente desconectado…»). Con un prompt que no entra en el contexto **efectivo** del modelo (`min(AI_NUM_CTX, contexto de /api/ps)`) responde **`413`** `{code: "PROMPT_TOO_LARGE", detail, estimated_tokens, limit_tokens}` sin llamar a Ollama (Ollama lo recortaría en silencio) |
| GET | `/runtime` | modelo (con `digest`), versión de Ollama, opciones realmente enviadas (`num_ctx`, `keep_alive`, `num_predict`, `temperature`, `seed`, `num_thread`, límite del prompt), estado de carga de LLM y embedding (`/api/ps`), estado del precalentamiento y `context`: contexto **pedido** vs **efectivo** de cada modelo (`requested_num_ctx`, `llm.effective`, `embedding.effective`, `prompt_max_tokens`, `mismatch`). Nunca falla: con Ollama caído informa `ollama.reachable=false` y no trae `models` |
| POST | `/evaluate/evidence` | `{control_id?, control_description, evidence_text}` — juicio automático sobre si una evidencia satisface un control |
| POST | `/evidence/ingest` | indexa una evidencia recién subida (llamado por `EvidenceService.upload`) |
| POST | `/evidence/citations` | fragmentos relevantes de evidencia ya indexada, acotados a organización/evaluación/control |
| DELETE | `/evidence/{organization_id}/{evidence_id}` | borra los chunks de una evidencia del índice |

## Referencias

- Catálogo de módulos y clases: [`Codigo.md`](Codigo.md)
- Casos de uso con el endpoint exacto de cada uno: [`diagramas/casos-de-uso.md`](diagramas/casos-de-uso.md)
- Cómo probar todo esto (incluida la obtención de un token vía ROPC): [`Testing.md`](../desarrollo/Testing.md)
