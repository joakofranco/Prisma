# Historias de Usuario

Relevamiento completo de historias de usuario de PRISMA, escrito **a partir del sistema
efectivamente construido** (no es un backlog aspiracional: cada historia listada abajo está
implementada y verificada contra el stack real; donde hay una limitación conocida se aclara en
"Notas"). Sirve como referencia funcional complementaria a
[`docs/diagramas/casos-de-uso.md`](diagramas/casos-de-uso.md) (que describe actores y flujos a
nivel de caso de uso/UML) — acá el mismo sistema se lee desde la perspectiva ágil de "quién
necesita qué, y para qué", con criterios de aceptación verificables.

## Roles (actores)

| Rol | Alcance |
|---|---|
| `PRISMA_ADMIN` | Administrador de la **plataforma**: global, no pertenece a ninguna organización, sin restricciones. |
| `ORG_RESPONSIBLE` | Administrador/dueño de **su propia** organización: crea evaluaciones, gestiona los usuarios de su organización, arma el plan de mejora. |
| `INTERNAL_EVALUATOR` | Responde la autoevaluación de su organización y calcula madurez, sin las capacidades administrativas de `ORG_RESPONSIBLE`. |
| `AUDITOR` | Acotado explícitamente a las organizaciones que se le asignaron (`auditedOrganizationIds`) -- no es un rol global. Revisa evaluaciones en auditoría y registra observaciones. |
| `VIEWER` | Sólo lectura dentro de su organización. |

Ver [`docs/Arquitectura.md`](Arquitectura.md) para el detalle de cómo se aplica el aislamiento
multi-tenant en cada capa.

## Índice

1. [Autenticación y cuenta propia](#1-autenticación-y-cuenta-propia)
2. [Organizaciones](#2-organizaciones)
3. [Usuarios y roles](#3-usuarios-y-roles)
4. [Catálogos MCU 5.0](#4-catálogos-mcu-50)
5. [Perfiles comunitarios](#5-perfiles-comunitarios)
6. [Evaluaciones — ciclo de vida y autoevaluación](#6-evaluaciones--ciclo-de-vida-y-autoevaluación)
7. [Evidencias](#7-evidencias)
8. [Auditoría](#8-auditoría)
9. [Plan de mejora](#9-plan-de-mejora)
10. [Reportes](#10-reportes)
11. [Panel (Dashboard)](#11-panel-dashboard)
12. [Experiencia general (UX transversal)](#12-experiencia-general-ux-transversal)
13. [Configuración de la plataforma](#13-configuración-de-la-plataforma)
14. [Observabilidad y trazabilidad](#14-observabilidad-y-trazabilidad)
15. [Infraestructura y despliegue](#15-infraestructura-y-despliegue)

---

## 1. Autenticación y cuenta propia

### HU-AUTH-01 — Iniciar sesión con SSO
> Como usuario de cualquier rol, quiero iniciar sesión con mis credenciales corporativas contra
> Keycloak, para acceder a PRISMA sin que la aplicación maneje contraseñas por su cuenta.

- El login real es 100% delegado a Keycloak (Authorization Code flow); PRISMA nunca ve ni guarda
  la contraseña en texto plano.
- Un JWT inválido o vencido redirige de nuevo al login.
- **Implementado en:** `apps/frontend/src/services/auth.ts`, `SecurityConfig.java`.

### HU-AUTH-02 — Cerrar sesión
> Como usuario, quiero cerrar sesión, para proteger mi cuenta en un equipo compartido.

- **Implementado en:** `AppHeader.vue` (menú de usuario → "Cerrar Sesión").

### HU-AUTH-03 — Cambiar mi propia contraseña
> Como usuario de cualquier rol, quiero cambiar mi contraseña indicando la actual, para
> mantenerla segura sin depender de que un administrador me la resetee.

- Rechaza el cambio si la contraseña actual no es correcta (validada contra Keycloak).
- La nueva contraseña exige mínimo 8 caracteres.
- Queda operativa de inmediato (no fuerza otro cambio en el próximo login).
- **Implementado en:** `PUT /api/account/password`, `AccountService.changePassword`,
  `AccountView.vue`.

### HU-AUTH-04 — Corregir mi propio nombre
> Como usuario de cualquier rol, quiero corregir mi nombre y apellido si quedaron mal cargados al
> darme de alta, sin tener que pedirle a un administrador que edite mi ficha.

- El cambio se sincroniza en `prisma.users` y en el perfil de Keycloak.
- Se refleja en la pantalla al instante (no depende de que el JWT se renueve).
- No incluye el email (cambiarlo tocaría la identidad de login en Keycloak; queda fuera de
  alcance).
- **Implementado en:** `PUT /api/account/profile`, `AccountService.updateProfile`,
  `AccountView.vue` → "Corregir nombre".

### HU-AUTH-05 — Restablecer la contraseña de otro usuario
> Como `PRISMA_ADMIN` u `ORG_RESPONSIBLE` (de su propia organización), quiero fijarle una
> contraseña nueva a otro usuario, para que un usuario bloqueado recupere el acceso sin pasar por
> el flujo de autoservicio.

- La contraseña fijada queda operativa de inmediato -- no se le pide al usuario que la cambie de
  nuevo en su próximo login (a diferencia del alta inicial, donde sí tiene sentido forzarlo).
- **Implementado en:** `PUT /api/users/{id}`, `UserService.update` (usa
  `KeycloakAdminClient.setPermanentPassword`, no `resetPassword`). Ver "Endurecimientos
  recientes" en [`SECURITY.md`](SECURITY.md).

---

## 2. Organizaciones

### HU-ORG-01 — Alta de organización
> Como `PRISMA_ADMIN`, quiero dar de alta una organización (nombre, RUT, sector, tamaño), para
> empezar a evaluarla.

- El RUT es **opcional**: no toda organización lo tiene cargado al momento del alta. Un RUT en
  blanco se normaliza a `NULL` (no `""`) en el backend, para que la constraint `UNIQUE` no choque
  entre dos organizaciones sin RUT cargado.
- **Implementado en:** `POST /api/organizations`, `OrganizationsView.vue`.

### HU-ORG-02 — Guiar la designación del responsable al crear una organización
> Como `PRISMA_ADMIN`, al crear una organización nueva quiero que se me guíe a designar primero
> su `ORG_RESPONSIBLE`, para que no quede una organización sin nadie a cargo antes de sumarle
> evaluadores, auditores o visualizadores.

- Al guardar la organización, un modal ofrece "Asignar responsable ahora" (lleva directo al alta
  de usuario con la organización y el rol `ORG_RESPONSIBLE` preseleccionados) o "Hacerlo más
  tarde".
- Es una regla de UI/orden, no una restricción del modelo de datos.
- **Implementado en:** `OrganizationsView.vue`, `UsersView.vue` (flujo guiado vía
  `?newOrgId=`).

### HU-ORG-03 — Listar y ver el detalle de organizaciones
> Como `PRISMA_ADMIN` (todas) u `ORG_RESPONSIBLE`/`AUDITOR` (las propias/asignadas), quiero ver
> el listado y el detalle de organizaciones, incluida la evolución de su madurez en el tiempo.

- **Implementado en:** `GET /api/organizations`, `OrganizationsView.vue`,
  `OrganizationDetailView.vue` (incluye `EvaluationMaturityTrendChart`).

### HU-ORG-04 — Editar una organización
> Como `PRISMA_ADMIN`, quiero editar los datos de una organización existente.

- **Implementado en:** `PUT /api/organizations/{id}`.

### HU-ORG-05 — Dar de baja una organización sin perder su historial
> Como `PRISMA_ADMIN`, quiero dar de baja una organización, conservando todo su historial de
> evaluaciones, evidencia y auditoría.

- Es una baja **lógica** (`enabled=false`): no borra la fila, porque violaría las FK de
  `users.tenant_id`/`evaluations.organization_id`.
- **Implementado en:** `DELETE /api/organizations/{id}`, `OrganizationService.delete`.

---

## 3. Usuarios y roles

### HU-USR-01 — Alta de usuario con rol y organización
> Como `PRISMA_ADMIN`, quiero dar de alta un usuario con nombre, email, contraseña, rol(es) y
> organización, para que quede provisionado y pueda loguearse de inmediato.

- Provisiona automáticamente la cuenta en Keycloak; sin contraseña, el alta se rechaza (nunca
  queda un usuario "a medias", visible en la app pero sin poder loguearse).
- **Implementado en:** `POST /api/users`, `UserService.create`.

### HU-USR-02 — Un `ORG_RESPONSIBLE` administra los usuarios de su propia organización
> Como `ORG_RESPONSIBLE`, quiero dar de alta, editar y borrar los usuarios de **mi propia**
> organización (evaluadores, auditores, visualizadores), sin depender de que un `PRISMA_ADMIN`
> lo haga por mí.

- El `tenantId` que mande en el pedido se ignora: siempre se fuerza el propio (no puede crear ni
  mover usuarios a otra organización).
- Nunca puede otorgarle a nadie -- ni a sí mismo -- el rol global `PRISMA_ADMIN`.
- Si asigna `AUDITOR`, las organizaciones a auditar deben ser exclusivamente la suya.
- La UI oculta el rol "Administrador" y bloquea el selector de organización a la propia para no
  invitar a un intento que el backend igual rechaza.
- **Implementado en:** `UserService.create/update/delete` (acotamiento vía
  `CurrentUserService.assertOrganizationAccess`), `UsersView.vue`.

### HU-USR-03 — Ver a qué organización pertenece cada usuario
> Como `PRISMA_ADMIN` (u `ORG_RESPONSIBLE` viendo los suyos), quiero ver en la tabla de usuarios
> a qué organización pertenece cada uno, sin tener que abrir cada ficha.

- Un usuario sin tenant (p.ej. `PRISMA_ADMIN`) se muestra como "Global (sin organización)".
- **Implementado en:** columna "Organización" en `UsersView.vue`, `UserDto.organizationName`.

### HU-USR-04 — Protección contra auto-eliminación y contra borrar al administrador global
> Como sistema, no debo permitir que un usuario se borre a sí mismo, ni que se borre al
> administrador global sembrado (`admin@prisma.local`), para que la plataforma nunca quede sin
> nadie que pueda administrarla.

- Ambas protecciones son independientes entre sí y se verifican explícitamente en
  `UserService.delete`.
- **Implementado en:** `UserService.delete`. Incidente real documentado en
  [`RUNBOOK.md`](RUNBOOK.md).

### HU-USR-05 — Un auditor necesita al menos una organización asignada
> Como sistema, no debo permitir que se cree o edite un usuario con rol `AUDITOR` sin al menos
> una organización para auditar, porque quedaría "auditando nada" en toda la plataforma.

- **Implementado en:** `UserService.validateAuditorHasOrganizations`.

### HU-USR-06 — Deshabilitar un usuario sin borrarlo
> Como `PRISMA_ADMIN`/`ORG_RESPONSIBLE`, quiero poder deshabilitar (y volver a habilitar) un
> usuario sin eliminarlo, para bloquearle el acceso de forma reversible (ej. licencia, cuenta
> sospechosa) conservando su historial (evaluaciones, evidencia, auditoría) intacto — a
> diferencia de eliminarlo, que borra la cuenta por completo.

- Checkbox "Usuario habilitado" en el formulario de edición, más un ícono de acción rápida en la
  grilla (sin abrir el modal completo) para alternar el estado con un click.
- Deshabilitar sincroniza el estado con Keycloak (`enabled=false` ahí también) — un usuario
  deshabilitado no puede autenticarse, no sólo "se ve inactivo" en la app.
- Mismas dos protecciones que `HU-USR-04`: no podés deshabilitar tu propia cuenta ni la del
  administrador global sembrado.
- **Implementado en:** `PUT /api/users/{id}` (`enabled`), `UserService.update`,
  `UsersView.vue`.

---

## 4. Catálogos MCU 5.0

### HU-CAT-01 — Explorar el catálogo
> Como cualquier usuario autenticado, quiero recorrer el árbol completo del catálogo (Función →
> Categoría → Subcategoría → Requisito → Control) con su nivel objetivo, para entender qué se
> evalúa antes de responder o auditar.

- La vista muestra **una pestaña por cada catálogo dado de alta** (`GET /api/catalog/versions`) en
  vez de un selector desplegable escondido — con un solo catálogo cargado (el caso más común) se
  ve una única pestaña, pero la existencia de más de uno queda visible sin tener que abrir nada.
- **Implementado en:** `GET /api/catalog/{version}`, `CatalogView.vue`.

### HU-CAT-02 — Importar una versión nueva del catálogo
> Como `PRISMA_ADMIN`, quiero importar una versión nueva del catálogo completa (JSON), para
> incorporar actualizaciones del marco MCU sin tocar código ni migraciones.

- **Implementado en:** `POST /api/catalog/import`, `CatalogImportPanel.vue`.

### HU-CAT-03 — Editar el árbol antes de confirmarlo
> Como `PRISMA_ADMIN`, quiero poder editar (agregar/quitar función, categoría, subcategoría,
> requisito o control) el árbol que estoy por importar, para corregir errores antes de que quede
> persistido.

- **Implementado en:** `CatalogTreeEditor.vue`.

### HU-CAT-04 — Eliminar una versión de catálogo
> Como `PRISMA_ADMIN`, quiero eliminar una versión de catálogo que ya no se usa.

- **Implementado en:** `DELETE /api/catalog/{version}`.

---

## 5. Perfiles comunitarios

### HU-PERF-01 — Crear un perfil comunitario
> Como `PRISMA_ADMIN`, quiero crear un perfil comunitario (subconjunto curado de controles, p.ej.
> "Básico" o "PYME"), para que una evaluación pueda acotarse a lo relevante de un sector en vez
> del catálogo completo.

- **Implementado en:** `POST /api/community-profiles`, `CommunityProfilesView.vue`.

### HU-PERF-02 — Elegir un perfil comunitario al crear una evaluación
> Como `ORG_RESPONSIBLE`, quiero elegir (opcionalmente) un perfil comunitario al crear una
> evaluación, para no tener que responder controles que no aplican a mi sector.

- **Implementado en:** `NewEvaluationView.vue`, `CreateEvaluationDto.communityProfileId`.

---

## 6. Evaluaciones — ciclo de vida y autoevaluación

Ciclo de vida completo: `DRAFT → IN_PROGRESS → READY_FOR_AUDIT → IN_AUDIT → APPROVED|RETURNED`,
`APPROVED → ARCHIVED` (`RETURNED` vuelve a `IN_PROGRESS` para corregir). Detalle de transiciones
y roles habilitados en [`docs/API.md`](API.md).

### HU-EVAL-01 — Crear una evaluación
> Como `ORG_RESPONSIBLE`, quiero crear una evaluación nueva para mi organización, eligiendo
> versión de catálogo y, opcionalmente, un perfil comunitario.

- Nace en estado `DRAFT`.
- **Implementado en:** `POST /api/evaluations`.

### HU-EVAL-02 — Responder la autoevaluación control por control
> Como `ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR`, quiero marcar cada control como cumple/no cumple y
> agregar observaciones, para ir completando la autoevaluación a mi ritmo.

- Sólo se puede seguir respondiendo mientras la evaluación esté en `DRAFT`/`IN_PROGRESS`/
  `RETURNED` (`Evaluation.isSelfAssessmentEditable()`).
- **Implementado en:** `POST /api/evaluations/{id}/responses`, `EvaluationRespondView.vue`.

### HU-EVAL-03 — Marcar preguntas para revisar después
> Como `ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR`, quiero marcar (bookmark) una pregunta para
> volver a ella más tarde, sin perder el resto del avance.

- **Implementado en:** `EvaluationRespondView.vue` (`toggleFlag`).

### HU-EVAL-04 — Calcular la madurez
> Como `INTERNAL_EVALUATOR`/`ORG_RESPONSIBLE`, quiero recalcular la madurez global y por función/
> subcategoría en cualquier momento, para ver el avance sin esperar a terminar toda la
> evaluación.

- Cálculo acumulativo por nivel (no puede alcanzar el nivel N sin cumplir todos los controles de
  los niveles anteriores dentro de la subcategoría).
- Idempotente: recalcular pisa el resultado anterior.
- **Implementado en:** `POST /api/evaluations/{id}/calculate`,
  `EvaluationService.calculateMaturity`.

### HU-EVAL-05 — Avanzar el estado de la evaluación
> Como `ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR` (autoevaluación) o `AUDITOR`/`PRISMA_ADMIN`
> (auditoría), quiero avanzar el estado de una evaluación siguiendo el flujo del ciclo de vida.

- Cada transición valida tanto el estado actual como qué rol puede dispararla -- no sólo que el
  rol pueda llamar al endpoint.
- **Implementado en:** `PATCH /api/evaluations/{id}/status`,
  `EvaluationService.ALLOWED_TRANSITIONS` / `assertRoleCanTransitionTo`.

### HU-EVAL-06 — Rechazar transiciones de estado inválidas
> Como sistema, no debo permitir revertir una evaluación a un estado anterior fuera del flujo
> definido (p.ej. de `ARCHIVED` a `DRAFT` con un PATCH directo).

- **Implementado en:** `EvaluationService.ALLOWED_TRANSITIONS` (las claves ausentes del mapa son
  estados terminales sin transiciones admitidas).

### HU-EVAL-07 — Ver organización, evaluador y auditor asignado en la grilla
> Como `PRISMA_ADMIN`, quiero ver en la grilla de evaluaciones a qué organización pertenece cada
> una, quién la evaluó y qué auditor(es) tiene asignados, sin entrar al detalle de cada una.

- "Auditor asignado" es quién está **habilitado** para auditar esa organización (no
  necesariamente quien ya la auditó -- eso es la estampa de auditoría, ver HU-AUD-04).
- Sólo se muestran para `PRISMA_ADMIN`: para el resto, acotado a su propia organización, sería
  información repetida en cada fila.
- **Implementado en:** columnas "Evaluador"/"Auditor asignado" en `EvaluationsView.vue`,
  `EvaluationDto.createdByName`/`assignedAuditorNames`.

### HU-EVAL-08 — Resultados de madurez colapsados por defecto
> Como cualquier rol con acceso a una evaluación, quiero que los resultados agrupados "por
> función" aparezcan colapsados por defecto, para no saturar la pantalla con el detalle de todos
> los controles apenas abro la evaluación.

- **Implementado en:** `EvaluationDetailView.vue` (`expandedGroups`).

### HU-EVAL-09 — Nivel 0 se muestra como un resultado real
> Como cualquier rol, quiero que un nivel de madurez 0 (calculado) se distinga de "todavía no se
> calculó" -- ambos no pueden verse igual ("—" en ambos casos escondía un 0 real).

- **Implementado en:** comparaciones `!= null` en vez de truthy checks, en
  `EvaluationsView.vue`, `OrganizationDetailView.vue` y los componentes de gráfico.

### HU-EVAL-10 — Paginar los resultados de madurez
> Como cualquier rol viendo una evaluación con muchos controles, quiero paginar los resultados
> por función y elegir cuántos ver por página, en vez de tener que hacer scroll por todo el
> catálogo.

- **Implementado en:** `EvaluationDetailView.vue` (`pageSizeOptions`, `paginatedGroups`).

---

## 7. Evidencias

### HU-EVI-01 — Subir evidencia
> Como `ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR`, quiero subir un archivo de evidencia documental
> para respaldar un control, para que quede disponible durante la auditoría.

- **Implementado en:** `POST /api/evidence`, `EvidenceView.vue`.

### HU-EVI-02 — Indexación automática por IA
> Como sistema, quiero indexar automáticamente el contenido de cada evidencia (embeddings +
> ChromaDB) al subirla, para que después se puedan buscar citas relevantes por control.

- Best-effort: si backend-ai está caído, la evidencia queda igual guardada en MinIO/Postgres, y
  el usuario puede reintentar la indexación más tarde.
- **Implementado en:** `AiEvidenceClient.ingest`, `EvidenceService.upload`.

### HU-EVI-03 — Reintentar la indexación
> Como usuario, quiero reintentar la indexación IA de una evidencia si falló la primera vez, sin
> tener que volver a subir el archivo.

- **Implementado en:** `POST /api/evidence/{id}/index`, botón "Analizar con IA" en
  `EvidenceView.vue`.

### HU-EVI-04 — Eliminar evidencia
> Como `ORG_RESPONSIBLE`/`INTERNAL_EVALUATOR`, quiero eliminar una evidencia que subí por error.

- **Implementado en:** `DELETE /api/evidence/{id}`.

### HU-EVI-05 — Evidencia bloqueada durante y después de la auditoría
> Como sistema, no debo permitir subir, eliminar ni reindexar evidencia mientras la evaluación
> está en auditoría o ya fue aprobada/archivada, para que el auditor revise siempre exactamente
> lo que se le entregó.

- `Evaluation.isSelfAssessmentEditable()` es la única fuente de verdad, compartida entre
  `EvaluationService.saveResponse` y todo `EvidenceService`.
- **Implementado en:** `EvidenceService` (upload/delete/reindex).

### HU-EVI-06 — Buscar citas de evidencia por control (asistente de IA)
> Como `AUDITOR`, quiero que el sistema busque automáticamente en la evidencia cargada los
> fragmentos que podrían respaldar un control puntual, mostrando el documento, la ubicación
> (página y sección) y el texto exacto, para no tener que leer cada archivo entero a mano.

- La búsqueda es semántica (embeddings), acotada a la evidencia de esa organización/evaluación.
- Es sólo una guía de dónde mirar: la validación final la registra el auditor como Observación.
- **Implementado en:** `POST /api/evidence/evaluation/{id}/control/{controlId}/citations`,
  `AiEvidenceClient.citations`, `AuditView.vue` → "Asistente de IA — Evidencias por control".

### HU-EVI-07 — Ir directo al documento en la página de la referencia
> Como `AUDITOR`, quiero poder abrir el documento de evidencia directamente en la página donde se
> encontró el fragmento citado, en vez de tener que buscarla manualmente dentro de un PDF largo.

- El link "Ver documento" arma la URL firmada del archivo con el fragmento `#page=N` cuando la
  ubicación citada incluye un número de página.
- **Implementado en:** `documentUrl()` en `AuditView.vue`.

### HU-EVI-08 — Registrar una observación a partir de una cita
> Como `AUDITOR`, quiero poder registrar una observación de auditoría con un click a partir de
> una cita encontrada, con el contexto (control, documento, ubicación, fragmento) ya precargado,
> para no tener que volver a escribirlo.

- **Implementado en:** `openObservationForCitation()` en `AuditView.vue`.

---

## 8. Auditoría

### HU-AUD-01 — Revisar una evaluación en auditoría
> Como `AUDITOR` asignado a esa organización, quiero revisar una evaluación en estado
> `IN_AUDIT` y registrar observaciones, no conformidades o recomendaciones por control.

- Un `AUDITOR` sólo ve/audita las organizaciones que tiene explícitamente asignadas
  (`auditedOrganizationIds`) -- no es un rol global.
- **Implementado en:** `POST /api/audit/observations`, `AuditView.vue`.

### HU-AUD-02 — Editar una observación
> Como `AUDITOR`/`PRISMA_ADMIN`, quiero editar el control, tipo, descripción o estado de una
> observación ya creada.

- **Implementado en:** `PUT /api/audit/observations/{id}`.

### HU-AUD-03 — Seguimiento del estado de una observación
> Como `AUDITOR`, quiero marcar una observación como "en progreso" y después "resuelta", para
> hacer seguimiento de qué falta cerrar dentro de la propia auditoría.

- **Implementado en:** botones de estado en `AuditView.vue`
  (`updateObservationStatus`).

### HU-AUD-04 — Estampa del auditor que trabajó la auditoría
> Como cualquier usuario que vea los resultados de una auditoría, quiero saber qué auditor(es)
> trabajaron en ella de hecho -- no sólo quién está habilitado para hacerlo -- tanto a nivel de
> cada observación como en general.

- Por observación: "Registrada por &lt;nombre&gt;".
- Agregado: "Auditoría realizada por &lt;nombre(s)&gt;" -- los autores reales (distinct) de las
  observaciones ya cargadas.
- **Implementado en:** `AuditObservationDto.createdByName`, banner en `AuditView.vue`.

### HU-AUD-05 — Aprobar o devolver una evaluación auditada
> Como `AUDITOR`/`PRISMA_ADMIN`, quiero, al terminar la revisión, aprobar la evaluación o
> devolverla a la organización para que corrija.

- `RETURNED` vuelve la autoevaluación a editable (`IN_PROGRESS`) para que la organización
  corrija y la reenvíe.
- **Implementado en:** transición de estado `IN_AUDIT → APPROVED|RETURNED`.

---

## 9. Plan de mejora

### HU-MEJ-01 — Generar sugerencias automáticas a partir de las brechas
> Como `ORG_RESPONSIBLE`, quiero generar automáticamente una lista de sugerencias de mejora a
> partir de los controles no conformes o por debajo del nivel objetivo, para no tener que
> revisar el catálogo entero a mano buscando qué falta.

- Ordenadas por prioridad (nivel 1 primero: es bloqueante de todo lo demás en el modelo
  acumulativo).
- Excluye controles que ya tienen un plan real creado.
- **Implementado en:** `GET /api/improvement/{evaluationId}/suggestions`,
  `ImprovementPlanService.suggest`, `ImprovementView.vue`.

### HU-MEJ-02 — Guía práctica automática (IA) para cerrar una brecha puntual
> Como `ORG_RESPONSIBLE`, quiero pedir una guía práctica generada automáticamente (resumen +
> pasos concretos) para una brecha puntual, para saber **cómo** resolverla y no sólo qué dice el
> control (que ya lo sé, es la brecha que tengo).

- Se pide on-demand, un control a la vez (nunca para toda la lista de sugerencias junta).
- Los pasos están pensados para equipos con recursos limitados: herramientas o prácticas
  concretas, no generalidades.
- Best-effort: si el modelo de IA no está disponible, se informa en vez de romper la pantalla de
  sugerencias.
- **Implementado en:** `POST /api/improvement/suggestions/tips`,
  `VectorStore.suggest_remediation` (backend-ai), botón "¿Cómo puedo solucionar esto?" en
  `ImprovementView.vue`.

### HU-MEJ-03 — Editar y seleccionar sugerencias antes de crearlas
> Como `ORG_RESPONSIBLE`, quiero poder editar el texto, asignar responsable/prioridad/fecha y
> elegir cuáles sugerencias convertir en acciones reales del plan, antes de guardarlas.

- **Implementado en:** modal de sugerencias en `ImprovementView.vue`
  (`acceptSuggestions`).

### HU-MEJ-04 — Crear una acción de mejora manualmente
> Como `ORG_RESPONSIBLE`, quiero crear una acción de mejora manualmente (sin partir de una
> sugerencia automática), para registrar iniciativas que no salen directamente de una brecha del
> catálogo.

- **Implementado en:** `POST /api/improvement`.

### HU-MEJ-05 — Seguimiento del plan de mejora
> Como `ORG_RESPONSIBLE`, quiero ver cuántas acciones están pendientes, en progreso, completadas
> o vencidas, y cambiar el estado de cada una.

- Las acciones vencidas se recalculan al listar (no es un campo estático).
- **Implementado en:** `ImprovementPlanService.listByEvaluation` (marca overdue),
  `ImprovementView.vue`.

---

## 10. Reportes

### HU-REP-01 — Descargar reporte en PDF
> Como usuario con acceso a una evaluación aprobada, quiero descargar un reporte PDF con los
> resultados de madurez, para compartirlo fuera de la plataforma.

- **Implementado en:** `GET /api/reports/{evaluationId}/pdf`, `ReportsView.vue`.

### HU-REP-02 — Descargar reporte en Excel
> Como usuario con acceso a una evaluación aprobada, quiero descargar un reporte Excel con el
> detalle control por control, para procesarlo o auditarlo con otras herramientas.

- **Implementado en:** `GET /api/reports/{evaluationId}/excel`.

---

## 11. Panel (Dashboard)

### HU-DASH-01 — Panel resumen
> Como usuario autenticado de cualquier rol, quiero ver un panel con el total de evaluaciones,
> organizaciones activas, madurez promedio y mejoras pendientes acotado a lo que me corresponde
> ver, para tener un pantallazo general sin entrar a cada sección.

- **Implementado en:** `GET /api/dashboard/stats`, `DashboardView.vue`.

### HU-DASH-02 — Madurez por función y últimas evaluaciones
> Como usuario autenticado, quiero ver un gráfico de madurez por función y una tabla con las
> últimas evaluaciones, para identificar rápido dónde están las brechas más grandes.

- **Implementado en:** `MaturityBarChart.vue`, tabla de últimas evaluaciones en
  `DashboardView.vue`.

---

## 12. Experiencia general (UX transversal)

### HU-UX-01 — Notificaciones de confirmación
> Como usuario, quiero recibir una notificación (toast) confirmando o informando el resultado de
> mis acciones (creado, error, advertencia), sin que tape controles importantes de la pantalla.

- Aparece abajo a la derecha (no arriba, donde competía visualmente con el menú de usuario).
- **Implementado en:** `NotificationToast.vue`, `stores/notification.ts`.

### HU-UX-02 — Tooltips en acciones de sólo ícono
> Como usuario, quiero ver el nombre de una acción representada sólo por un ícono (editar,
> eliminar, ver, volver, etc.) al pasar el mouse por encima, para no tener que adivinar qué hace
> cada botón.

- Cubre las acciones de las tablas, las flechas de "volver", el editor de árbol del catálogo, el
  toggle de colapsar el menú lateral (y sus ítems, que al colapsar quedan sin ninguna etiqueta
  visible), y los botones de cerrar de modales/alertas.
- **Implementado en:** atributos `title` nativos en los componentes correspondientes (ver
  "Endurecimientos recientes"/changelog para el listado completo de archivos).

### HU-UX-03 — Aislamiento multi-tenant transparente
> Como usuario de un rol acotado a organización, quiero que las listas (organizaciones, usuarios,
> evaluaciones) me muestren automáticamente sólo lo que me corresponde ver, sin tener que filtrar
> yo mismo ni arriesgarme a ver datos de otra organización.

- **Implementado en:** `CurrentUserService.assertOrganizationAccess`, aplicado explícitamente en
  cada `Service` que expone datos por organización.

### HU-UX-04 — Cambiar entre tema claro y oscuro
> Como usuario, quiero poder elegir entre tema claro y oscuro, y que la app recuerde mi elección
> en este dispositivo, para poder trabajar cómodo según la luz del ambiente o mi preferencia.

- El toggle vive en el menú del usuario (arriba a la derecha, junto a "Mi Cuenta"/"Cerrar
  Sesión"), no en una pantalla de configuración aparte — es la elección que se cambia con más
  frecuencia de todo el menú.
- Persiste en `localStorage` (por navegador/dispositivo, no sincroniza entre dispositivos ni se
  guarda en el backend) y se aplica **antes** del primer render para no mostrar un parpadeo de
  tema incorrecto al cargar la página.
- Los gráficos (Chart.js, dibujados en `<canvas>`, no siguen el CSS de la página) cambian de
  paleta junto con el resto de la UI — sin esto, sus etiquetas quedaban con el gris oscuro por
  defecto de Chart.js, ilegible sobre una tarjeta en modo oscuro.
- **Implementado en:** `stores/theme.ts`, `AppHeader.vue`, `main.css` (`@custom-variant dark`).

---

## 13. Configuración de la plataforma

### HU-CFG-01 — Configurar el SMTP de Keycloak
> Como `PRISMA_ADMIN`, quiero configurar el servidor SMTP del realm, para que "¿Olvidaste tu
> contraseña?" en el login envíe el correo correspondiente.

- Keycloak enmascara el password guardado (`**********`) al leerlo: hay que volver a ingresarlo
  en cada actualización, no se puede "conservar" el anterior.
- **Implementado en:** `PUT /api/admin/email-settings`, `EmailSettingsPanel.vue`. Ver
  [`docs/Notificaciones-Email.md`](Notificaciones-Email.md).

### HU-CFG-02 — Administrar el catálogo desde Configuración
> Como `PRISMA_ADMIN`, quiero llegar a la importación/edición del catálogo desde la sección de
> Configuración, junto con el resto de la administración de la plataforma.

- **Implementado en:** `SettingsView.vue`.

---

## 14. Observabilidad y trazabilidad

### HU-OBS-01 — Bitácora de auditoría de acciones sensibles
> Como `PRISMA_ADMIN`, quiero que toda acción sensible (crear/editar/borrar usuarios,
> organizaciones, evaluaciones, cambios de contraseña, login/logout) quede registrada con quién,
> qué y cuándo, para poder reconstruir qué pasó ante un incidente.

- Escritura síncrona, dentro de la misma transacción de la operación que audita.
- **Nota conocida:** al resolver "quién" lo hizo *después* de la operación en la misma
  transacción, una auto-eliminación deja el actor en blanco en la bitácora (ver
  [`RUNBOOK.md`](RUNBOOK.md)).
- **Implementado en:** `AuditLogService`, tabla `audit.audit_logs`.

### HU-OBS-02b — Consultar la bitácora desde la UI ("Actividad del Sistema")
> Como `PRISMA_ADMIN` (todos los tenants) u `ORG_RESPONSIBLE` (el propio), quiero poder ver y
> filtrar la bitácora desde la app, sin necesitar acceso directo a la base de datos.

- Filtro por texto libre (acción/recurso) y por tipo de acción; columna "Recurso Afectado"
  resuelve el **nombre real** de la entidad involucrada (ej. `Usuario: Jane Doe`,
  `Organización: Acme S.A.`) en vez de mostrar sólo un id — cae al id acortado (con el string
  completo en el tooltip) si el tipo no tiene nombre propio o la entidad ya no existe.
- Mismo aislamiento multi-tenant que el resto de la app: `ORG_RESPONSIBLE` sólo ve la actividad de
  su propia organización.
- **Implementado en:** `GET /api/audit-logs`, `AuditLogService.list`/`resolveResourceName`,
  `ActivityLogView.vue`.

### HU-OBS-03b — Registro de intentos de login fallidos
> Como `PRISMA_ADMIN`/`ORG_RESPONSIBLE`, quiero ver en la bitácora los intentos de login
> fallidos (quién, desde qué IP, cuándo) para detectar intentos de fuerza bruta contra cuentas de
> mi organización.

- El login lo maneja Keycloak directamente (Authorization Code + PKCE) — `backend-core` nunca ve
  ese POST. Un job programado (`LoginFailureAuditSyncService`, cada 15s por defecto) trae los
  eventos `LOGIN_ERROR` que Keycloak ya audita solo (`eventsEnabled=true` en el realm) y los
  refleja en la bitácora propia como acción `LOGIN_FAILED`.
- Si el email intentado coincide con un usuario real, el intento queda vinculado a su
  organización (para que `ORG_RESPONSIBLE` lo vea) y a su nombre; si no coincide con nadie
  (usuario inexistente), queda sin vincular y sólo lo ve `PRISMA_ADMIN`.
- **El bloqueo en sí no es cosa de esta historia**: ya lo hace Keycloak (`bruteForceProtected`,
  `failureFactor: 5` por defecto — configurable en vivo desde su consola de administración, Realm
  Settings → Security Defenses → Brute Force Detection, sin redeploy). Esto sólo le da
  visibilidad al equipo desde la app.
- **Implementado en:** `LoginFailureAuditSyncService`, `KeycloakAdminClient.fetchLoginFailures`,
  `AuditLogService.recordLoginFailure`. Ver [`SECURITY.md`](SECURITY.md).

### HU-OBS-02 — Logs centralizados y métricas de los contenedores
> Como equipo de operación, quiero ver los logs de todos los contenedores centralizados y
> métricas de infraestructura en un solo lugar, para diagnosticar un incidente sin entrar
> contenedor por contenedor.

- Dos vías paralelas: Grafana + Loki + Prometheus (principal) y, opcionalmente, Elasticsearch +
  Kibana + Filebeat (perfil `elastic`, búsqueda libre).
- **Implementado en:** perfiles `observability`/`elastic` de `docker-compose.yml`. Ver
  [`docs/CI-CD-Infra.md`](CI-CD-Infra.md).

### HU-OBS-03 — Sesiones de frontend grabadas (LogRocket)
> Como equipo de desarrollo, quiero poder reproducir la sesión de un usuario que reportó un
> error en el frontend, para diagnosticar bugs que no se explican sólo con logs de backend.

- **Implementado en:** `apps/frontend/src/plugins/logrocket.ts`.

---

## 15. Infraestructura y despliegue

### HU-INFRA-01 — Levantar el stack completo con un solo comando
> Como desarrollador, quiero levantar todo el stack (frontend, backends, Postgres, Keycloak,
> MinIO, Redis, Ollama/ChromaDB) con un solo comando, para no tener que instalar ni configurar
> cada dependencia a mano.

- **Implementado en:** `docker-compose.yml` (perfiles `app`, `security`, `full`, etc.). Ver
  [`README.md`](../README.md) (Quickstart) y [`docs/Dev-Config.md`](Dev-Config.md).

### HU-INFRA-02 — Desplegar en un cluster Kubernetes con redundancia
> Como equipo de operación, quiero desplegar PRISMA en un cluster Kubernetes (minikube en local)
> con al menos dos réplicas de cada componente que pueda escalar horizontalmente sin
> inconsistencias, para que la caída de un pod no tumbe la aplicación.

- Frontend y backend-core: **2 réplicas** (stateless, sin estado local).
- backend-ai: **2 réplicas** (stateless; el índice de ChromaDB vive en un volumen persistente
  compartido, no en memoria del proceso).
- Postgres, Redis, MinIO, Ollama y la base de Keycloak son, a propósito, réplica única en este
  manifiesto: son componentes con estado (bases de datos, cache, object storage, modelo de IA)
  donde escalar a 2 réplicas sin un mecanismo de clustering real (replicación, Sentinel, modo
  distribuido) no da redundancia -- corrompe o duplica el estado. Ver
  [`docs/Kubernetes-Minikube-Portainer.md`](Kubernetes-Minikube-Portainer.md) para el detalle de
  por qué cada uno queda así y qué camino seguir para una HA real de producción.
- **Implementado en:** `infra/kubernetes/base/*.yaml`, `infra/kubernetes/overlays/*/patches.yaml`.

### HU-INFRA-03 — CI/CD automático
> Como equipo de desarrollo, quiero que cada push corra lint + tests + build automáticamente, y
> que cada release construya y publique las imágenes, para no depender de que alguien se acuerde
> de correrlo a mano.

- **Implementado en:** `.github/workflows/ci.yml`, `build-and-push.yml`, `deploy.yml`,
  `security.yml`, `sonarqube.yml`. Ver [`docs/CI-CD-Infra.md`](CI-CD-Infra.md).

---

## Notas metodológicas

- Cada historia lista, cuando aplica, el endpoint/archivo principal donde vive la implementación
  -- no es exhaustivo (un feature típico toca backend, frontend y a veces backend-ai a la vez),
  es el punto de entrada para quien necesite profundizar.
- Las historias no incluyen estimaciones de esfuerzo ni sprint: este documento es un relevamiento
  funcional retrospectivo, no un backlog de planificación.
- Para el detalle de qué rol puede disparar cada transición de estado y qué HTTP status devuelve
  cada rechazo, ver [`docs/API.md`](API.md) en vez de duplicarlo acá.
