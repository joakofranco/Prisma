# 🔒 Modelo de seguridad de PRISMA

## Amenazas cubiertas

| Amenaza | Mitigación |
|---|---|
| **SQL Injection** | JPA/JPQL con parámetros nombrados (`@Param`), sin SQL nativo concatenado en ningún repositorio; Bean Validation en los DTOs de entrada. |
| **XSS** | Vue escapa interpolaciones por default (no se usa `v-html` con datos de usuario); CSP restrictiva servida por nginx (`default-src 'self'`, ver `apps/frontend/nginx.conf`). |
| **CSRF** | No aplica en el sentido clásico: la API es 100% stateless (JWT en header `Authorization: Bearer`, sin cookies de sesión que un request cross-site pueda reenviar solo). |
| **BOLA / IDOR** | **No es un middleware global** — cada `Service` llama explícitamente a `CurrentUserService.assertOrganizationAccess(organizationId)` (o filtra por tenant en la query) antes de devolver/mutar un recurso. Es responsabilidad de quien escribe un `Service` nuevo acordarse de llamarlo; nada a nivel de framework lo fuerza. **Esto no siempre se cumplió**: `OrganizationService`/`UserService` no tuvieron ningún chequeo de este tipo hasta que se detectó en una pasada de QA exhaustiva y se corrigió (cualquier usuario autenticado podía enumerar todas las organizaciones y usuarios de la plataforma, sin importar su propio tenant) — ver "Endurecimientos recientes" más abajo. Al agregar un `Service` nuevo que exponga datos por organización, agregar el chequeo explícitamente y no asumir que "ya está cubierto en otro lado". |
| **Broken auth** | Keycloak (SSO, JWT firmado RS256, brute-force protection — `failureFactor: 5`, bloqueo escalonado hasta 900s, configurable en vivo desde la consola de Keycloak sin redeploy) + Argon2id para contraseñas locales. Los intentos fallidos además quedan en la bitácora propia (`LOGIN_FAILED`, ver "Registro de intentos de login fallidos" abajo) para verlos sin entrar a Keycloak. **MFA/TOTP está configurado pero no es obligatorio** para ningún rol (ver [`Arquitectura.md`](Arquitectura.md)) — queda como mejora pendiente, no como control ya activo. |
| **Sensitive Data Exposure** | HTTPS forzado desde el navegador vía nginx (certificado propio, ver [`Certificados-TLS.md`](Certificados-TLS.md)); evidencias en MinIO con URLs firmadas de corta duración (900s) en vez de un bucket público. |
| **Components with known vulnerabilities** | Trivy (FS + imágenes) + OWASP Dependency-Check + Gitleaks + CodeQL, semanal y en cada push a `main`/`dev` (`security.yml`). |
| **Insufficient Logging** | Bitácora de auditoría de acciones sensibles (`audit.audit_logs`, escritura síncrona — ver [`Arquitectura.md`](Arquitectura.md)), consultable desde la app ("Actividad del Sistema", `GET /api/audit-logs`) e incluye **login/logout y login fallido** (ver "Registro de intentos de login fallidos" abajo); logs de todos los contenedores centralizados en Loki/Grafana y, opcionalmente, Elasticsearch/Kibana (perfil `elastic`). |
| **Broken access control (nivel de negocio)** | Ver "Endurecimientos recientes" — el ciclo de vida de una evaluación y la evidencia asociada ahora se bloquean explícitamente por estado, no sólo por rol. |

## Endurecimientos recientes (para que no se repitan)

Encontrados y corregidos en una pasada de QA exhaustiva contra el stack real (no sólo revisión de
código) — se documentan con su causa para que un patrón similar se evite en código nuevo:

1. **Fuga de datos entre organizaciones** — `GET /api/organizations` y `GET /api/users` no
   filtraban por tenant: cualquier usuario autenticado veía la plataforma completa. Corregido
   aplicando el mismo patrón que ya usaba `EvaluationService` (`CurrentUserService`). Lección: un
   `Service` nuevo que lista/expone entidades ligadas a una organización necesita el chequeo
   explícito — no hay nada que lo haga por default.
2. **Sin máquina de estados en el ciclo de vida de una evaluación** — el endpoint de cambio de
   estado aceptaba cualquier transición del enum sin validar el estado actual ni el rol que
   correspondía a cada paso (se podía revertir una evaluación `ARCHIVED` a `DRAFT` con un solo
   PATCH). Corregido con una tabla de transiciones válidas + rol autorizado por transición
   (`EvaluationService.assertRoleCanTransitionTo`).
3. **Evidencia editable durante auditoría** — se podía subir/borrar/reindexar evidencia mientras un
   auditor estaba revisando esa misma evaluación (`IN_AUDIT`), o incluso después de aprobada/
   archivada. Corregido: `Evaluation.isSelfAssessmentEditable()` es ahora la única fuente de verdad
   de "en qué estados se puede seguir tocando la autoevaluación", usada tanto por
   `EvaluationService.saveResponse` como por todo `EvidenceService`.
4. **Sin protección contra auto-eliminación ni contra borrar al administrador global** —
   `UserService.delete` no impedía que un `PRISMA_ADMIN` se borrara a sí mismo, ni que borrara al
   usuario semilla (`admin@prisma.local`). Este bug se manifestó de verdad durante las propias
   pruebas (ver el incidente documentado en [`RUNBOOK.md`](RUNBOOK.md)). Corregido con dos
   chequeos explícitos al inicio de `delete()`.
5. **Secreto de Keycloak desincronizado por defecto** — `.env.example` traía un
   `KEYCLOAK_ADMIN_CLIENT_SECRET` que nunca coincidía con el hardcodeado en
   `infra/keycloak/realm-prisma.json` (Keycloak no soporta variables de entorno en ese JSON), así
   que siguiendo el Quickstart tal cual, ningún usuario nuevo creado desde la app podía loguearse.
   Corregido sincronizando el valor de ejemplo.
6. **Restablecer la clave de un usuario desde el panel no la dejaba operativa** —
   `UserService.update()` usaba `KeycloakAdminClient.resetPassword()` (`temporary=true`) para el
   reseteo que hace un admin sobre OTRO usuario ya activo. Keycloak descarta esa clave en el
   siguiente login y fuerza al usuario a elegir otra en una pantalla propia de Keycloak antes de
   dejarlo entrar — para quien prueba el restablecimiento (loguearse con la clave que el admin
   acaba de fijar) esto se veía exactamente como "no funciona". Corregido usando
   `setPermanentPassword()` (`temporary=false`) para este caso — igual que ya hacía el cambio de
   clave autoservicio — dejando `resetPassword()` sólo para el alta inicial de una cuenta nueva.
7. **`ORG_RESPONSIBLE` no podía gestionar los usuarios de su propia organización** —
   `POST/PUT/DELETE /api/users` estaban restringidos a `PRISMA_ADMIN` únicamente, así que el
   "administrador" de una organización no podía dar de alta a sus propios evaluadores/auditores/
   visualizadores sin pedirle a un PRISMA_ADMIN que lo hiciera por él. Se relajó el
   `@PreAuthorize` a `PRISMA_ADMIN`/`ORG_RESPONSIBLE`, pero **cada operación queda acotada en
   `UserService`** (no sólo en la UI): un `ORG_RESPONSIBLE` nunca puede tocar un usuario fuera de
   su propio tenant, el `tenantId` que mande en el body se ignora y se fuerza el propio, no puede
   otorgarle a nadie el rol global `PRISMA_ADMIN`, y si asigna `AUDITOR` sólo puede auditar su
   propia organización. `PRISMA_ADMIN` sigue siendo el único rol sin esa restricción.
8. **IP spoofeable en la bitácora** — nginx (el reverse-proxy principal y el del contenedor
   frontend) usaba `$proxy_add_x_forwarded_for`, que **hereda y antepone** cualquier
   `X-Forwarded-For` que mande el propio cliente en vez de reemplazarlo — un cliente que mandara
   su propio header quedaba registrado en `audit.audit_logs.ip_address` como si ese fuera su IP
   real. Corregido fijando el header con `$remote_addr` (lo que nginx observó de verdad, sin
   heredar nada del cliente) en los tres bloques `location` afectados. `AuditLogService.currentIp()`
   además se simplificó para leer `getRemoteAddr()` (ya resuelto por
   `server.forward-headers-strategy: framework`) en vez de releer el header a mano.
9. **`CreateUserDto.enabled` sin protección propia** — al agregar la opción de deshabilitar un
   usuario sin borrarlo (ver [`HistoriasDeUsuario.md`](HistoriasDeUsuario.md#hu-usr-06--deshabilitar-un-usuario-sin-borrarlo)),
   se replicaron desde el vamos las mismas dos protecciones que ya tenía `delete()` (ítem 4): no
   se puede deshabilitar la propia cuenta ni al administrador global sembrado — deshabilitarse a
   uno mismo es un lockout tan definitivo como borrarse.
10. **Login roto en cualquier servidor con una IP/dominio distinto al de `localhost`/`prisma.local`
    — relajación deliberada, no un descuido.** `infra/keycloak/realm-prisma.json` traía
    `redirectUris`/`webOrigins` del client `prisma-frontend` como una lista fija de hosts
    conocidos (`localhost`, `prisma.local`, etc.). Como Keycloak sólo importa el realm **una vez**
    (`--import-realm` no reimporta si el realm ya existe), cada servidor/cluster nuevo con un
    host distinto necesitaba el mismo paso manual — entrar al Admin Console y agregar ese host a
    mano — antes de poder loguearse ahí, algo fácil de saltear al copiar el `.env` de un lado a
    otro (en el servidor real donde se detectó esto, además, el JSON había quedado con una coma
    faltante al intentar agregar el host a mano directo en el archivo, lo que rompía el
    `--import-realm` por completo — ver el incidente en [`RUNBOOK.md`](RUNBOOK.md)). Se cambió a
    `redirectUris: ["*"]` / `webOrigins: ["*"]` — un valor especial que Keycloak reconoce como
    "cualquier URI/origen" — para que el mismo realm funcione sin edición manual sin importar la
    IP o el dominio del servidor. **Trade-off aceptado conscientemente, no ideal para un ambiente
    multi-tenant público real:** el riesgo que esto habilita es que un atacante registre SU
    PROPIA página en cualquier otro dominio y arme un link de login legítimo de PRISMA que
    redirija el `code` de autorización hacia esa página en vez de volver a la SPA real. Se
    considera aceptable acá porque (a) `prisma-frontend` es un client público sin secreto — nunca
    tuvo un secreto que proteger — y (b) usa PKCE (`S256`): sin el `code_verifier` original, que
    nunca sale del navegador que inició el login, ese `code` interceptado no alcanza para
    completar el intercambio por un token. Para un despliegue con un dominio fijo conocido de
    antemano (el caso real de producción, no de un ambiente de pruebas con IP variable), lo
    correcto es volver a una lista explícita de hosts en vez de `"*"`.

## Registro de intentos de login fallidos

El login lo maneja Keycloak directamente (Authorization Code + PKCE) — `backend-core` nunca ve el
POST del formulario, así que no hay forma de enterarse de un intento fallido más que yendo a
buscarlo del lado de Keycloak. `LoginFailureAuditSyncService` sondea cada 15s (configurable,
`LOGIN_FAILURE_SYNC_INTERVAL_MS`) los eventos `LOGIN_ERROR` que Keycloak ya audita solo
(`eventsEnabled: true` en `infra/keycloak/realm-prisma.json`, más el rol `view-events` de
`realm-management` para el client `prisma-backend`) y los refleja en `audit.audit_logs` como
`LOGIN_FAILED` — visibles desde "Actividad del Sistema" sin entrar a la consola de Keycloak.

- Si el email intentado coincide con un usuario real, el intento queda vinculado a su
  organización (para que `ORG_RESPONSIBLE` lo vea, mismo aislamiento multi-tenant de siempre); si
  no coincide con nadie, sólo lo ve `PRISMA_ADMIN`.
- El watermark de sondeo es en memoria (no persistido): un reinicio de `backend-core` no reproduce
  el historial completo de fallos viejos, sólo los que ocurran de ahí en adelante — es una vista
  de actividad reciente, no el registro forense definitivo (que sigue íntegro del lado de Keycloak
  mientras dure `eventsExpiration`, 30 días por defecto).
- El **bloqueo** de la cuenta lo sigue haciendo Keycloak solo (`failureFactor`, ver la tabla de
  arriba) — este mecanismo sólo agrega visibilidad, no reemplaza ni reimplementa el lockout.

## Pentesting de caja negra (hallazgos y estado)

Batería de pruebas manuales contra el stack local corriendo (puertos expuestos, credenciales por
defecto, cabeceras, JWT, CORS, actuator). Reportado con fecha para que quede claro qué se probó
contra qué versión del código — un hallazgo "abierto" acá puede haberse corregido después si no
está tachado.

**Corregido a partir de este pentest:**
- Spoofing de IP en la bitácora (ítem 8 arriba).

**Abiertos — requieren una decisión explícita del equipo, no se tocaron automáticamente porque
implican rotar secretos/credenciales compartidas o tocar la topología de red del `docker-compose`:**
- Postgres, MinIO, Grafana, SonarQube y Keycloak (realm `master`) aceptan las credenciales
  placeholder que trae `.env` (`change_me_in_production`, `admin`/`admin`) — acceso total en cada
  caso. **Rotar antes de exponer el stack más allá de `localhost`.**
- Los 23 servicios de `docker-compose.yml` publican en `0.0.0.0` (toda la red), no `127.0.0.1` —
  amplifica el alcance de cada credencial débil a cualquiera en la misma LAN, no sólo la propia
  máquina.
- Métricas sin autenticar: Prometheus, cAdvisor, node/postgres/redis-exporter, y
  `/actuator/prometheus` de `backend-core` — exponen topología interna y patrones de tráfico.
- Ollama (puerto 11434) sin auth — cualquiera en red puede listar/descargar modelos o consumir
  cómputo.

**Verificado seguro (sin acción necesaria):** Elasticsearch y Redis exigen autenticación; JWT con
`alg=none` es rechazado (400) por el decodificador de Spring; `/actuator/env` y `/actuator/beans`
no están expuestos (sólo `health`/`info`/`metrics`/`prometheus`); `.env`/`.git` no los sirve
nginx; headers de seguridad presentes en el frontend (CSP, `X-Frame-Options`,
`X-Content-Type-Options`); los errores de `backend-core` no filtran stack traces; CORS no refleja
orígenes arbitrarios.

## Cumplimiento MCU 5.0

PRISMA implementa los controles necesarios para ser AUDITADA por sí misma:
- Registro de accesos y acciones sensibles (`audit.audit_logs`).
- Segregación de entornos (local / dev / staging / prod, ver `README.md` → Ambientes).
- Backup y restore documentados (`make backup` / `make restore`, ver [`RUNBOOK.md`](RUNBOOK.md)).
- Análisis periódico de vulnerabilidades (`security.yml`, semanal).

## Reportar una vulnerabilidad

Este es un proyecto académico (ver `docs/Proyecto.md`), sin un programa de bug bounty ni un canal
de disclosure formal. Si encontrás algo, abrí un issue privado o contactá directamente a los
integrantes listados en el `README.md` — evitá publicar detalles de una vulnerabilidad explotable
en un issue público del repo.
