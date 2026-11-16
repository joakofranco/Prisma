# Anexo — Respuesta a la devolución de Etapa 1 (Área Ciberseguridad y Cloud Computing)

**Equipo ITDice — PRISMA**
Federico De Armas, Fernando Araujo, Joaquín Franco

---

## 1. Introducción

La devolución de tutoría calificó la Línea Base como **aceptable con observaciones**, reconociendo los avances (arquitectura on-premise fundamentada, riesgos de ciberseguridad, planes de continuidad, estrategia de pentesting y enfoque SSDLC), y solicitó definir de manera explícita seis puntos antes de pasar a un estado aceptable sin observaciones:

1. La solución definitiva de identidad y gestión de sesiones.
2. Los perfiles que utilizarán MFA y su carácter obligatorio.
3. El mecanismo de aislamiento entre organizaciones y las pruebas previstas para comprobarlo.
4. La solución para el almacenamiento seguro de evidencias.
5. Cómo se garantizará la integridad/inmutabilidad de la bitácora.
6. Qué eventos de seguridad generarán alertas y quién será responsable de atenderlas.

Este anexo responde a cada punto con la decisión tomada, el mecanismo técnico concreto que la implementa (ya construido o a construir sobre la infraestructura existente) y, donde corresponde, el estado real de implementación al día de hoy — sin presentar como resuelto lo que todavía es un compromiso a corto plazo.

---

## 2. Solución definitiva de identidad y gestión de sesiones

La prueba de concepto quedó cerrada: **Keycloak es la solución definitiva** de identidad para PRISMA, no un candidato en evaluación.

- **Protocolo:** OpenID Connect / OAuth2, flujo *Authorization Code* con **PKCE (S256)**. El cliente `prisma-frontend` es público (SPA, sin secreto que proteger), y PKCE evita que un `code` de autorización interceptado sea canjeable por un token sin el `code_verifier` original, que nunca sale del navegador que inició el login.
- **Tokens:** JWT firmados con RS256, validados por `backend-core` mediante `spring-boot-starter-oauth2-resource-server` contra el JWKS de Keycloak. La API es **100% stateless**: no hay cookies de sesión del lado del servidor, el token viaja en el header `Authorization: Bearer`, y por eso CSRF en el sentido clásico no aplica.
- **Contraseñas:** hash Argon2id (implementación pura Java, no la nativa vía JNA, que se comprobó que hace `SIGSEGV` sobre el runtime Alpine/musl del contenedor — se detectó en pruebas end-to-end reales, no en revisión de código).
- **Protección contra fuerza bruta:** bloqueo progresivo nativo de Keycloak (`failureFactor: 5`, bloqueo escalonado hasta 900 s), configurable en caliente desde la consola de administración sin redeploy. Los intentos fallidos, además, se reflejan en la bitácora propia de PRISMA (`LOGIN_FAILED`) mediante un servicio que sondea los eventos `LOGIN_ERROR` de Keycloak cada 15 s, de forma que un `PRISMA_ADMIN` u `ORG_RESPONSIBLE` los vea desde "Actividad del Sistema" sin necesitar acceso a la consola de Keycloak.
- **Gestión de sesión del lado del cliente:** el SPA guarda el *access token* y el *refresh token* en memoria de la store de sesión, no en `localStorage`; un interceptor HTTP central reintenta una única vez con refresh automático ante un 401 y fuerza logout si el refresh también falla (evita loops de reintento infinitos).

**Estado:** implementado y en uso en todos los entornos (local, MVP, K8s). No queda ningún punto abierto de diseño de identidad para esta etapa.

---

## 3. MFA: perfiles obligados y alcance

**Decisión:** MFA (TOTP, vía la app "Configure OTP" nativa de Keycloak) es **obligatorio** para los dos roles de mayor privilegio del sistema, y queda **recomendado pero opcional** para el resto:

| Rol | MFA | Justificación |
|---|---|---|
| `PRISMA_ADMIN` | **Obligatorio** | Único rol verdaderamente global: opera sobre todos los tenants sin restricción de organización (ver sección 4). Es el objetivo de mayor impacto ante una cuenta comprometida. |
| `AUDITOR` | **Obligatorio** | Accede a evaluaciones y evidencias de las organizaciones que tiene asignadas para auditar, cruzando el límite normal de un único tenant; su cuenta comprometida expone información de varias organizaciones a la vez. |
| `ORG_RESPONSIBLE` | Recomendado (no bloqueante en esta etapa) | Administra usuarios y datos de su propia organización únicamente; el radio de impacto de un compromiso queda acotado a un solo tenant. |
| `INTERNAL_EVALUATOR` / `VIEWER` | Opcional | Menor superficie de impacto (sin capacidad de administración ni de auditar terceros). |

**Mecanismo técnico:** Keycloak permite condicionar una *Required Action* ("Configure OTP") por rol/grupo mediante un flujo de autenticación condicional (*Conditional OTP Form* en el *Browser flow*, evaluando el rol del realm del usuario). Esto no requiere código propio en `backend-core` ni en el frontend: Keycloak fuerza el enrolamiento de TOTP la primera vez que un usuario con rol `PRISMA_ADMIN` o `AUDITOR` inicia sesión, y lo exige en cada login posterior.

**Estado real:** el mecanismo de TOTP ya está configurado y disponible en el realm (`infra/keycloak/realm-prisma.json`), pero la condición de obligatoriedad por rol descrita arriba es la decisión que se está formalizando en esta etapa; su activación en el flujo de autenticación del realm es el próximo paso de configuración, sin impacto en el modelo de datos ni en el backend.

---

## 4. Aislamiento entre organizaciones (multi-tenancy)

### 4.1 Mecanismo técnico

El aislamiento **no se implementa con Row-Level Security de PostgreSQL** ni con un esquema separado por organización: es un control explícito **a nivel de aplicación**, centralizado en un único punto de la capa de servicios de `backend-core`.

Cada tabla de negocio con datos propios de una organización tiene una columna `organization_id`/`tenant_id` (ver el anexo de Base de Datos para el modelo completo). `CurrentUserService.assertOrganizationAccess(organizationId)` es el método que decide si el usuario autenticado puede operar sobre los datos de esa organización, y **todo `Service` que exponga o mute un recurso ligado a una organización debe invocarlo explícitamente antes de devolver o guardar el dato**:

- `PRISMA_ADMIN` siempre puede — es el único rol global.
- `AUDITOR` sólo si la organización está entre las que tiene asignadas explícitamente (`auditedOrganizationIds()`), no todas las organizaciones de la plataforma.
- El resto de los roles (`ORG_RESPONSIBLE`, `INTERNAL_EVALUATOR`, `VIEWER`) sólo si la organización coincide con su propio `tenantId`.
- Cualquier otro caso lanza `403 Forbidden` (`AccessDeniedException`) antes de tocar el recurso.

**Por qué a nivel de aplicación y no de base de datos:** PRISMA es un monolito modular con un único usuario de base de datos para `backend-core` (no un usuario de conexión por organización), por lo que RLS exigiría además setear una variable de sesión por request (`SET app.current_org = ...`) y mantenerla sincronizada con el contexto de seguridad de Spring en cada conexión del pool — una capa adicional de acoplamiento entre el pool de conexiones y el contexto HTTP que el equipo evaluó y descartó para esta etapa, en favor de un único punto de control ya centralizado y testeado. Queda documentado como evolución posible si el volumen de organizaciones o el nivel de exigencia regulatoria lo justifican.

### 4.2 Riesgo reconocido del enfoque actual

Al ser un control de aplicación y no de motor de base de datos, su cobertura depende de que **cada `Service` nuevo lo invoque explícitamente** — nada a nivel de framework lo fuerza automáticamente. Esto no es una limitación teórica: en una pasada de QA exhaustiva contra el stack real se detectó y corrigió que `OrganizationService` y `UserService` no tenían ningún chequeo de este tipo, lo que permitía a cualquier usuario autenticado enumerar todas las organizaciones y usuarios de la plataforma sin importar su propio tenant. El hallazgo y su corrección quedan documentados como lección de proceso, no como un incidente oculto.

### 4.3 Pruebas previstas para comprobarlo

- **Pruebas de integración por `Service`:** cada `Service` que expone datos por organización tiene un caso de prueba explícito que verifica que un usuario del tenant A recibe `403` al intentar acceder a un recurso del tenant B (patrón ya aplicado en `EvaluationServiceTest`, `EvidenceServiceTest`, `OrganizationServiceTest`, `UserServiceTest`).
- **Prueba de integración end-to-end (`PrismaApplicationIT`):** contra un Postgres real (Testcontainers), valida que el ciclo completo de una evaluación (creación → respuesta → cálculo → resultado) respeta el aislamiento por organización, no sólo el mock de la capa de servicio.
- **Checklist de revisión de código:** todo `Service` nuevo que reciba un `organizationId`/`evaluationId`/`evidenceId` como parámetro debe documentar explícitamente, en su propia prueba unitaria, el caso "usuario de otra organización → 403" antes de mergearse — evita que se repita el hallazgo de la sección 4.2.
- **Pentesting de caja gris (ya en curso, ver Plan de Pruebas de Penetración):** incluye intentos explícitos de acceso cruzado entre organizaciones como parte de los casos de prueba manuales contra el stack desplegado.

---

## 5. Almacenamiento seguro de evidencias

**Decisión:** separación de responsabilidades entre contenido binario y metadata, siguiendo el patrón estándar para este tipo de sistema:

- **El archivo en sí** se guarda en **MinIO** (object storage S3-compatible, self-hosted, alineado con la decisión on-premise del proyecto). El objeto se nombra con una clave aleatoria (`evidence/<UUID>`), nunca con el nombre original del archivo, evitando colisiones y cualquier problema de interpretación de caracteres del nombre real en el object store.
- **La metadata** (nombre real, tamaño, tipo, descripción, quién y cuándo la subió, control asociado) se guarda en PostgreSQL como columnas escalares — sin binarios en la base de datos, que no está pensada para servirlos eficientemente y que además engordaría backups/replicación con contenido que el motor relacional no necesita interpretar.
- **Descarga directa desde MinIO vía URL prefirmada** (expiración de 900 s), de forma que `backend-core` nunca actúa como proxy del archivo completo en memoria.
- **Doble endpoint** (interno para que los propios contenedores hablen con MinIO por la red de Docker/K8s; público para que la URL firmada sea resoluble desde el navegador del usuario) — evita el error real ya detectado de firmar una URL con un host que ningún navegador externo puede resolver.
- **Control de acceso:** toda descarga pasa primero por `CurrentUserService.assertOrganizationAccess()` sobre la evaluación dueña de la evidencia, antes de generar la URL firmada — el aislamiento multi-tenant de la sección 4 aplica también a evidencias.

**Estado:** diseño de metadatos y modelo de datos ya definidos; la funcionalidad de carga/descarga corresponde al Incremento 2 (ver anexo de Proyecto), fuera del MVP estricto de esta entrega.

---

## 6. Integridad e inmutabilidad de la bitácora

La bitácora técnica (`audit.audit_logs`) está diseñada como un log **append-only** desde su modelo de datos: no tiene claves foráneas hacia `users`/`organizations` (para que un borrado real de esas entidades no deje huecos ni obligue a un `ON DELETE CASCADE` que borraría evidencia forense), y ninguna ruta de la aplicación emite `UPDATE`/`DELETE` contra esa tabla — sólo `INSERT` (escritura síncrona en el mismo `Service` que ejecuta la acción auditada) y `SELECT` (lectura desde "Actividad del Sistema").

**Brecha identificada y decisión de cierre:** hoy esa garantía es **de convención de código**, no una restricción impuesta por el motor de base de datos — el usuario de conexión de `backend-core` técnicamente podría ejecutar un `UPDATE`/`DELETE` sobre `audit.audit_logs` si algún `Service` lo hiciera por error o por una vulnerabilidad de inyección. Para cerrar ese punto, se define el siguiente mecanismo de refuerzo en el motor:

1. **Privilegios de base de datos mínimos:** revocar `UPDATE`/`DELETE` sobre `audit.audit_logs` al rol de conexión de la aplicación (`REVOKE UPDATE, DELETE ON audit.audit_logs FROM <app_role>;`), dejándole únicamente `INSERT` y `SELECT`. Cualquier intento de modificar o borrar una fila, sea por error de programación o por una inyección que lograra ejecutar SQL arbitrario, falla a nivel de motor antes de tocar el dato.
2. **Trigger de defensa en profundidad:** un `BEFORE UPDATE OR DELETE` trigger sobre la tabla que levanta una excepción explícita, como segunda barrera independiente del `GRANT`/`REVOKE` (útil también si en el futuro se agrega un rol administrativo con permisos más amplios sobre el esquema).
3. **Retención documentada:** los backups diarios de PostgreSQL (`make backup`, retención 30 días) cubren `audit.audit_logs` como cualquier otra tabla del schema `prisma`/`audit`, dando una copia externa independiente del motor en producción.

**Estado:** el diseño append-only (sin FK en cascada, escritura síncrona centralizada) ya está implementado. El refuerzo a nivel de privilegios de motor (puntos 1 y 2) es la decisión formal que cierra esta observación y se incorpora como migración de base de datos en el próximo incremento de hardening.

---

## 7. Eventos de seguridad que generan alertas y responsables

### 7.1 Estado actual de observabilidad

El stack ya recolecta las métricas y logs necesarios para alertar: Prometheus scrapea `backend-core` (Actuator), `backend-ai`, Postgres y Redis; los logs de todos los contenedores se centralizan en Loki/Grafana. Lo que falta cerrar, y que esta sección define, es **qué condiciones sobre esos datos disparan una alerta activa** y quién la atiende — hoy la infraestructura de alerting (Alertmanager) está declarada pero sin reglas cargadas.

### 7.2 Eventos que generarán alerta

| Evento | Condición de disparo | Severidad | Responsable |
|---|---|---|---|
| Ráfaga de logins fallidos | ≥10 eventos `LOGIN_FAILED` en `audit.audit_logs` para el mismo email o la misma IP en 5 minutos | Alta | `PRISMA_ADMIN` de turno |
| Acceso denegado por aislamiento multi-tenant | ≥5 respuestas `403` de `assertOrganizationAccess()` desde el mismo usuario en 10 minutos (posible intento de acceso cruzado) | Alta | `PRISMA_ADMIN` de turno |
| Caída de un componente con estado | `postgres`, `keycloak` o `minio` fuera de `Ready` por más de 2 minutos (healthcheck) | Crítica | Responsable de infraestructura (rol Infra del equipo) |
| Certificado TLS próximo a vencer | Menos de 15 días para el vencimiento del certificado servido por nginx | Media | Responsable de infraestructura |
| Falla del backup diario | `make backup` (CronJob) termina con código de error, o no se ejecuta en la ventana esperada | Alta | Responsable de infraestructura |
| Vulnerabilidad crítica en dependencias/imagen | Hallazgo `CRITICAL`/`HIGH` de Trivy, OWASP Dependency-Check o CodeQL en el pipeline semanal (`security.yml`) | Alta | Responsable de seguridad (rol Ciberseguridad del equipo) |
| Uso anómalo de CPU/memoria en `backend-core` | Umbral del HPA superado sostenidamente (>70% CPU por más de 10 minutos) sin escalado exitoso | Media | Responsable de infraestructura |

### 7.3 Mecanismo técnico de implementación

Las condiciones de la tabla anterior se implementan como reglas de Prometheus (`rule_files: /etc/prometheus/rules/*.yml`, ya referenciado en la configuración) combinadas con consultas periódicas sobre `audit.audit_logs` para los eventos de negocio (login fallido, acceso denegado) que no son métricas de infraestructura sino filas de base de datos; estas últimas se exponen como métrica propia mediante un endpoint de `backend-core` (`/actuator/prometheus`) que expone contadores derivados de la bitácora. Alertmanager enruta cada severidad a un canal distinto (Alta/Crítica → notificación inmediata al responsable de turno; Media → resumen diario), reemplazando el `targets: []` vacío actual por la configuración real de notificación del equipo (correo/mensajería del grupo).

**Estado:** las métricas y la bitácora que alimentan estas reglas ya se generan hoy; la carga de las reglas de Prometheus y la configuración de Alertmanager es el trabajo pendiente que cierra formalmente esta observación, sin requerir cambios en el modelo de datos ni en el backend.

---

## 8. Conclusión

Los seis puntos solicitados quedan definidos con una decisión técnica concreta y, en cada caso, se indica honestamente si ya está implementado (identidad/sesión, aislamiento multi-tenant, diseño append-only de la bitácora) o si es una decisión que se formaliza en esta etapa para su implementación inmediata sobre infraestructura ya existente (obligatoriedad de MFA por rol, refuerzo de privilegios de la bitácora, reglas de alerta). Ninguno de estos puntos requiere replantear la arquitectura on-premise ni el modelo de datos ya definidos; todos se apoyan en componentes que el proyecto ya opera (Keycloak, PostgreSQL, Prometheus/Grafana).
