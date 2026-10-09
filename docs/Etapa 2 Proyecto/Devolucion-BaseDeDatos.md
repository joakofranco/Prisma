# Anexo — Respuesta a la devolución de Etapa 1 (Área Base de Datos)

**Equipo ITDice — PRISMA**
Federico De Armas, Fernando Araujo, Joaquín Franco

---

## 1. Introducción

La devolución señaló que el documento original identificaba correctamente PostgreSQL como motor principal y mencionaba aspectos relevantes (JSONB, persistencia, auditoría, versionado, índices, filtrado por organización, replicación y alta disponibilidad), pero **no profundizaba el *por qué*** de esas decisiones, no mostraba evidencia concreta del diseño (modelo entidad-relación, esquema lógico/físico, claves, restricciones, estrategia de separación entre organizaciones) y no explicaba de manera explícita los cuatro atributos de calidad pedidos por la pauta: **Escalabilidad, Mantenibilidad, Disponibilidad y Performance**. También se señaló que la frase "base de datos relacional y no relacional (PostgreSQL)" podía prestarse a confusión.

Este anexo corrige los tres puntos: primero aclara la frase ambigua, luego presenta la justificación de la elección tecnológica y el modelo de datos completo (con evidencia real, no descripción de intención), y por último desarrolla los cuatro atributos de calidad con mecanismos concretos ya definidos o implementados.

---

## 2. Aclaración: un único motor relacional, no dos paradigmas

La frase "base de datos relacional y no relacional (PostgreSQL)" del documento original inducía a pensar que se estaban proponiendo dos motores de almacenamiento distintos. **No es así.** PRISMA usa **un único motor**, PostgreSQL, que es un motor **relacional**. Lo que la frase original intentaba señalar es que PostgreSQL incorpora, como una capacidad más del mismo motor, el tipo de dato **`JSONB`** (JSON binario indexable) — una forma de guardar un documento semi-estructurado *dentro de una base relacional*, no una base de datos documental aparte. No hay ni MongoDB, ni Cassandra, ni ningún motor NoSQL independiente en la arquitectura: hay una sola base de datos relacional, normalizada en su gran mayoría, que usa `JSONB` en un único punto puntual y justificado (sección 4.4).

---

## 3. Selección del tipo de base de datos: por qué PostgreSQL y no otra alternativa

La elección no fue "PostgreSQL porque es popular", sino el resultado de contrastar la naturaleza del problema contra las alternativas relevantes:

| Criterio del dominio | Por qué pesa a favor de un motor relacional | Por qué descarta un motor NoSQL puro |
|---|---|---|
| El catálogo MCU 5.0 es una jerarquía estricta con integridad referencial real (una Subcategoría no puede apuntar a una Categoría inexistente) | Las FK con `ON DELETE CASCADE`/`SET NULL` (ver sección 4.3) son una garantía del propio motor, no responsabilidad de la aplicación. | Un motor documental (p. ej. MongoDB) exigiría reimplementar en la aplicación las validaciones de integridad que el motor relacional ya resuelve. |
| Los resultados de una evaluación deben ser consultables por combinaciones arbitrarias (por organización, por rango de fechas, por función/categoría, cruzando evaluación × control × evidencia) | SQL con `JOIN` e índices compuestos resuelve estas consultas ad-hoc sin modelar de antemano cada patrón de acceso. | Un modelo documental exige diseñar la forma del documento en función de los patrones de consulta previstos; las consultas no previstas (frecuentes en un sistema de reporting) son costosas o requieren duplicar datos. |
| El cálculo de madurez y el aislamiento multi-tenant necesitan **transacciones ACID** reales (que un cálculo de madurez no quede a mitad de escribir si falla a la mitad) | PostgreSQL da consistencia transaccional fuerte por defecto. | Los motores NoSQL orientados a escala horizontal (Cassandra, DynamoDB) típicamente sacrifican consistencia fuerte por disponibilidad/partición (teorema CAP), algo innecesario para el volumen real de PRISMA (decenas de organizaciones, no millones). |
| El equipo necesita explotar el motor con herramientas estándar (backups con `pg_dump`, migraciones versionadas con Flyway, `EXPLAIN ANALYZE` para optimizar) sin curva de aprendizaje adicional | Ecosistema maduro, documentado, con soporte nativo en Spring Data JPA. | Justificar un segundo motor (además de PostgreSQL) para un caso de uso puntual agrega una pieza operativa más (backup, monitoreo, alta disponibilidad propios) sin que el dominio lo exija. |

**No se evaluó ni se necesita un segundo motor de base de datos.** El único caso donde el proyecto se apoya en un motor de datos *distinto* a PostgreSQL es el índice vectorial del pipeline de IA (ChromaDB, para búsqueda por similitud semántica sobre embeddings) — y eso queda fuera del alcance de este documento porque (a) no reemplaza a PostgreSQL para ningún dato transaccional de PRISMA, (b) resuelve un problema estructuralmente distinto (similitud vectorial, no consultas relacionales) que PostgreSQL sin la extensión `pgvector` no resuelve de forma nativa, y (c) está fuera del MVP de esta entrega (ver anexo de Proyecto).

---

## 4. Modelo de datos: esquema lógico y evidencia de diseño

> El modelo completo, transcripto directamente de las migraciones Flyway aplicadas (`V1` a `V17`), está documentado en `docs/diagramas/modelo-entidad-relacion.md`; esta sección resume lo relevante para la pauta de evaluación.

### 4.1 Dos esquemas separados a propósito

PostgreSQL organiza el modelo en dos *schemas* dentro de la misma base de datos:

- **`prisma`**: todo el dominio de negocio (organizaciones, usuarios, catálogo, evaluaciones, resultados, evidencia, auditoría funcional, planes de mejora).
- **`audit`**: la bitácora técnica (`audit_logs`), deliberadamente aislada del dominio de negocio — no comparte claves foráneas con `prisma` (ver sección 4.5 y el anexo de Ciberseguridad, sección 6).

### 4.2 Entidades principales

| Entidad | Rol en el modelo |
|---|---|
| `organizations` | Tenant raíz del sistema; toda evaluación, usuario y evidencia cuelga, directa o indirectamente, de una organización. |
| `users` / `user_roles` | Usuario de la plataforma y sus roles (relación 1:N modelada como tabla propia, no como columna multivaluada — ver 4.4). |
| `catalog_versions` → `catalog_functions` → `catalog_categories` → `catalog_subcategories` | Jerarquía del Marco de Ciberseguridad de Uruguay 5.0, versionada explícitamente. |
| `catalog_requirements` → `catalog_controls` | Requisitos y controles evaluables, ligados a una versión del catálogo. |
| `catalog_requirement_subcategories` | Relación N:M entre requisitos y subcategorías (un requisito puede pertenecer a más de una subcategoría desde `V15`). |
| `community_profiles` / `community_profile_controls` | Perfiles comunitarios que acotan qué controles aplican a un tipo de organización. |
| `evaluations` | Evaluación de madurez de una organización, ancla la versión del catálogo usada. |
| `evaluation_responses` | Respuesta a cada control dentro de una evaluación. |
| `maturity_results` | Resultado calculado de madurez por función/categoría/subcategoría (único punto de desnormalización deliberada, ver 4.4). |
| `evidence` | Metadata de archivos de evidencia (el binario vive en MinIO, ver anexo de Ciberseguridad). |
| `improvement_plans` | Planes de mejora derivados de brechas detectadas. |
| `audit_observations` | Observaciones de auditoría funcional (distinta de `audit.audit_logs`, que es la bitácora técnica). |

### 4.3 Claves y restricciones

- **Claves primarias:** todas las tablas usan `id UUID PRIMARY KEY` generado por la aplicación, excepto las tablas puente de relaciones N:M (`user_roles`, `community_profile_controls`), que usan **clave primaria compuesta** (`PRIMARY KEY (user_id, role)` y `PRIMARY KEY (profile_id, control_id)` respectivamente) — evitando un `id` artificial donde el par de columnas ya identifica la fila de forma única.
- **Claves foráneas y su acción en cascada**, elegida caso por caso según la semántica del dominio:

  | Relación | `ON DELETE` | Por qué |
  |---|---|---|
  | `catalog_versions → catalog_functions → catalog_categories → catalog_subcategories` (cadena completa) | `CASCADE` en cada nivel | Si se borra una versión de catálogo completa, toda su jerarquía debe desaparecer con ella — no tiene sentido un nodo de catálogo huérfano de versión. |
  | `catalog_requirements ↔ catalog_subcategories` (N:M) | `CASCADE` en ambos lados | La tabla puente no tiene sentido sin ambos extremos vivos. |
  | `community_profiles → evaluations` | `SET NULL` | Un perfil comunitario es una referencia opcional/informativa para una evaluación; borrar el perfil no debe borrar evaluaciones ya realizadas, sólo desvincularlas. |
  | `evaluations → evaluation_responses / maturity_results / evidence / improvement_plans / audit_observations` | `CASCADE` | Son datos que no tienen sentido sin la evaluación dueña. |
  | `organizations → users` / `organizations → evaluations` | Sin acción (no se permite borrar una organización con usuarios/evaluaciones asociados) | Preserva la integridad histórica: una organización con actividad real no se borra en cascada, se deshabilita (`enabled = false`). |
  | `catalog_controls → evaluation_responses / evidence / improvement_plans / audit_observations` | Sin acción | Un control del catálogo no se borra si ya fue usado en una evaluación real, para no perder trazabilidad histórica. |

- **Restricciones de dominio (`CHECK`):** por ejemplo `evaluations.global_maturity` y `maturity_results.target_level` están acotados por `CHECK` a los niveles válidos del marco (1–5), y `maturity_results.current_level` admite 0 (nivel "no iniciado", desde `V16`).
- **Restricciones de unicidad (`UNIQUE`):** `organizations.rut` (opcional desde `V17`), `users.email`, `catalog_versions.version`, `catalog_requirements(version_id, code)` (desde `V15`).

### 4.4 Normalización y la única desnormalización deliberada

El esquema está en **Tercera Forma Normal (3FN)** en la totalidad de sus tablas transaccionales, incluyendo el cumplimiento de BCNF (la única clave candidata de cada tabla es su `id`, sin determinantes funcionales adicionales). Dos decisiones de diseño lo evidencian de forma concreta:

- **`user_roles`** existe específicamente para evitar una columna multivaluada (`users.roles VARCHAR[]`), permitiendo `JOIN`, índices y `ON DELETE CASCADE` normales sobre cada rol individual — la prueba de que la 1FN (valores atómicos) se tomó como restricción real de diseño y no como formalidad.
- **`maturity_results`** es la **única excepción intencional** a 3FN estricta: guarda copias desnormalizadas de `function_name`/`category_name`/`subcategory_name` (que ya existen en las tablas de catálogo) además de sus `_id` de referencia (sin FK real). La razón: `maturity_results` es un **snapshot histórico**, no una vista del catálogo vigente — si sólo guardara los `_id` y el catálogo cambiara después (una subcategoría se renombra, una versión se desactiva), los resultados de evaluaciones **pasadas** mostrarían el nombre *actual* en vez del nombre vigente al momento de la evaluación, rompiendo la trazabilidad que el módulo de auditoría necesita. El costo de la redundancia es bajo (tres columnas de texto por fila) y no genera riesgo de inconsistencia porque esas filas se recalculan por completo (`DELETE` + `INSERT`) en cada recálculo, nunca se actualizan en el lugar.

### 4.5 Estrategia de separación de datos entre organizaciones (multi-tenancy)

A nivel de modelo de datos, cada tabla con información propia de una organización lleva una columna de tenant (`organizations.id` referenciado como `organization_id` en `evaluations`, o propagado transitivamente a través de la evaluación dueña en `evidence`, `evaluation_responses`, `improvement_plans`, `audit_observations`). No existe un esquema por organización ni bases separadas: es un **modelo de fila compartida con columna discriminante** (*shared schema, tenant column*), el patrón estándar cuando el número de tenants es moderado y se necesita hacer consultas y reportes que crucen o agreguen organizaciones desde `PRISMA_ADMIN`.

La aplicación del filtro por tenant en cada consulta **no se resuelve en el modelo de datos sino en la capa de aplicación** (`CurrentUserService.assertOrganizationAccess`), decisión que se justifica y detalla en el anexo de Ciberseguridad, sección 4 — incluyendo el trade-off explícito frente a Row-Level Security de PostgreSQL y las pruebas previstas para validar que el aislamiento se cumple.

### 4.6 Justificación puntual de `JSONB`

El único uso de `JSONB` en todo el esquema es `audit.audit_logs.payload`, y es deliberado, no un atajo para evitar modelar columnas:

- El payload de auditoría varía según la acción registrada (crear una evaluación, cambiar un rol, eliminar una evidencia generan payloads con campos distintos entre sí). Modelarlo como columnas fijas obligaría a una tabla con decenas de columnas mayormente nulas, o a perder información específica de cada tipo de evento.
- Se declara `jsonb` (formato binario indexable) y no `text`/`json` (texto plano): permite usar los operadores nativos de Postgres (`->`, `->>`, `@>`) e indexar por clave interna con un índice GIN si en el futuro se necesita consultar la bitácora por contenido del payload, sin tener que traer y parsear cada fila en la aplicación.
- Es la **única** tabla del esquema que usa `JSONB`; el resto del modelo, incluyendo el catálogo (la parte con mayor profundidad jerárquica), se mantiene en columnas escalares normales — reforzando que no se usó como reemplazo general de un diseño relacional, sino para el único caso donde el dominio es genuinamente de forma variable.

---

## 5. Atributos de calidad de la arquitectura de datos

### 5.1 Escalabilidad

- **Vertical, hoy:** el motor corre como proceso único (`StatefulSet` de una réplica en el manifiesto de Kubernetes evaluado, PVC de 50 GiB), dimensionado para la carga real esperada del proyecto (decenas de organizaciones, no un volumen que exija particionado).
- **Horizontal, camino ya despejado:** toda la capa de aplicación (`frontend`, `backend-core`, `backend-ai`) es *stateless* y ya escala horizontalmente de forma independiente del motor de datos (`backend-core` con HPA 2→8 réplicas por CPU/memoria en el análisis de viabilidad de Kubernetes) — el cuello de botella de escalabilidad del sistema es explícitamente la base de datos, no la aplicación, lo cual es la situación esperable y manejable para este volumen de uso.
- **Índices dirigidos por el patrón de consulta real:** por ejemplo, `idx_audit_tenant_ts` sobre `(tenant_id, created_at)` en `audit_logs` está pensado específicamente para el patrón de consulta real (auditoría de una organización en un rango de fechas), no agregado de forma genérica.
- **Camino de evolución documentado, no ejecutado prematuramente:** réplicas de lectura (*read replicas*) para separar el tráfico de reportes/consultas del tráfico transaccional quedan identificadas como el siguiente paso si la medición real de carga lo justifica, evitando la complejidad operativa de una topología replicada para un volumen que hoy no la necesita.

### 5.2 Mantenibilidad

- **Migraciones versionadas con Flyway** (`V1` a `V17`): cada cambio de esquema es un archivo `.sql` numerado, aplicado automáticamente al arrancar `backend-core`, con historial completo y reproducible en cualquier entorno (local, CI con Testcontainers, staging, producción) — nunca un cambio de esquema aplicado a mano contra una base viva.
- **Esquema normalizado (sección 4.4):** al no duplicar datos salvo el caso justificado y documentado, un cambio de negocio (por ejemplo, renombrar una subcategoría del catálogo) se escribe en un único lugar y se propaga por `JOIN`, sin scripts de sincronización entre copias.
- **Documentación del modelo como artefacto versionado junto al código:** el ER, la normalización y el mapeo objeto-relacional (`docs/diagramas/modelo-entidad-relacion.md`, `normalizacion-bd.md`, `mapeo-jpa.md`) se generan y actualizan junto con cada migración, evitando que la documentación del modelo diverja del esquema real — riesgo habitual cuando el diagrama se dibuja una vez al inicio y no se vuelve a tocar.
- **Convenciones de nombrado consistentes** (`snake_case`, sufijo `_id` para FK, `created_at`/`updated_at` en las tablas que lo requieren) que reducen la carga cognitiva de incorporar a alguien nuevo al esquema.

### 5.3 Disponibilidad

- **Backups automatizados y con retención definida:** `make backup` (equivalente a un `pg_dump` comprimido) diario, retención de 30 días, con `make restore` documentado y probado — no sólo un script que existe, sino un procedimiento de recuperación verificado.
- **RTO/RPO definidos:** objetivo de una hora para restablecer el servicio (RTO) y una pérdida máxima admisible de 24 horas de datos (RPO), respaldados por la frecuencia diaria del backup.
- **Health checks activos:** en el análisis de viabilidad de Kubernetes, el `StatefulSet` de `postgres` expone *liveness*/*readiness* propios, de forma que un orquestador puede detectar y reaccionar ante una caída del proceso sin intervención manual.
- **Zona de estado acotada y explícita:** de los ocho servicios que componen el stack, sólo cinco tienen estado real (`postgres`, `keycloak-db`, `minio`, `chroma`, `ollama`); el resto es *stateless* y se recupera automáticamente. Esto acota dónde hay que poner el esfuerzo de disponibilidad: en esos cinco componentes, no en toda la plataforma por igual.
- **Punto abierto, reconocido explícitamente:** hoy no hay replicación *streaming* de PostgreSQL activa (una única instancia con PVC persistente); la alta disponibilidad del motor en sí mismo (no sólo del backup) es la evolución identificada para cuando el proyecto pase de ambiente académico a un despliegue con exigencia de continuidad operativa real, evitando declarar una capacidad de HA que hoy no está desplegada.

### 5.4 Performance

- **Índices sobre los patrones de acceso reales**, no genéricos: además del ya mencionado `idx_audit_tenant_ts`, cada FK que participa en un filtro frecuente (por ejemplo, `evaluation_id` en `evaluation_responses`/`evidence`/`maturity_results`) está indexada implícitamente por la propia restricción de clave foránea de PostgreSQL.
- **Cálculo de madurez materializado, no recalculado en cada lectura:** `evaluations.global_maturity` y las filas de `maturity_results` son un caché de agregado explícito — el cálculo (potencialmente costoso, recorre todas las respuestas de una evaluación) se ejecuta una vez por cambio relevante (`EvaluationService.calculateMaturity`) y se guarda, en vez de recalcularse en cada consulta de resultados. Es una decisión de performance documentada como tal, no un olvido de 3FN (ver sección 4.4).
- **Caché con Redis** para las consultas de lectura de mayor frecuencia (catálogo vigente, resultados agregados de dashboard), reduciendo presión sobre PostgreSQL en el camino de lectura más transitado.
- **JPA con fetch explícito (`FetchType.LAZY`) por relación**, evitando el problema clásico de N+1 consultas al recorrer jerarquías profundas (por ejemplo, catálogo función → categoría → subcategoría → requisito → control), cargando cada nivel sólo cuando efectivamente se necesita.

---

## 6. Conclusión

El motor elegido es **PostgreSQL, un único motor relacional**, no una combinación de dos paradigmas de almacenamiento; la frase ambigua del documento original queda corregida en la sección 2. La elección se justifica por la naturaleza fuertemente relacional del dominio (integridad referencial real, consultas ad-hoc, transacciones ACID) frente a las alternativas NoSQL evaluadas y descartadas por el mismo motivo. El modelo de datos está normalizado en 3FN con una única desnormalización deliberada y documentada, tiene claves y restricciones definidas caso por caso según la semántica de negocio (no por defecto), y la separación entre organizaciones se resuelve con una columna de tenant en el modelo más un control centralizado en la capa de aplicación, con su trade-off frente a RLS explicitado en el anexo de Ciberseguridad. Los cuatro atributos de calidad pedidos por la pauta (Escalabilidad, Mantenibilidad, Disponibilidad, Performance) quedan desarrollados con mecanismos concretos ya implementados donde corresponde, y con los puntos aún abiertos (réplicas de lectura, alta disponibilidad del motor) reconocidos explícitamente como evolución futura y no presentados como resueltos.
