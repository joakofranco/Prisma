# 🔗 Mapeo JPA / Hibernate — PRISMA

> Documento de referencia del mapeo objeto-relacional real de `backend-core`
> (`apps/backend-core/src/main/java/uy/edu/prisma/domain/entity/`), Spring Boot 3.5 + Hibernate 6
> sobre PostgreSQL 17. Convención general: **todas** las relaciones `@ManyToOne`/`@OneToMany`/
> `@ManyToMany` son `fetch = FetchType.LAZY` explícito (nunca se deja el default de Hibernate,
> que es `EAGER` para `@ManyToOne`/`@OneToOne`) — evita el problema N+1 implícito y fuerza a que
> cada `Service` decida explícitamente qué necesita cargar.
>
> `ddl-auto` está en `none` en producción y `validate` en el perfil de test
> (`application.yml`): el esquema lo gestiona **Flyway**, nunca Hibernate — ver
> [`diagramas/modelo-entidad-relacion.md`](diagramas/modelo-entidad-relacion.md) para el DDL real.

## Convenciones comunes

- **Generación de ID:** `@GeneratedValue(strategy = GenerationType.UUID)` en toda entidad — el
  valor lo genera Hibernate en memoria *antes* del `INSERT` (no `DEFAULT gen_random_uuid()` de
  Postgres resuelto server-side), lo que le permite a Spring Data JPA saber que una entidad es
  "nueva" (`isNew()`) sin necesitar un round-trip a la base. Esto importa en varios servicios: ver
  el comentario en `EvidenceService.upload()` sobre por qué **no** se asigna manualmente un UUID
  al `storageKey` antes del primer `save()`.
- **Auditoría de tiempos:** las entidades mutables tienen `createdAt`/`updatedAt`
  (`OffsetDateTime`, `@Builder.Default = OffsetDateTime.now()`) y un método `@PreUpdate` que
  refresca `updatedAt` en cada `UPDATE`. Las entidades append-only (`AuditLog`,
  `EvaluationResponse`, `ImprovementPlan`, `AuditObservation`, `MaturityResult`) solo tienen
  timestamp de creación.
- **Enums:** siempre `@Enumerated(EnumType.STRING)` — nunca `ORDINAL` (evita que reordenar una
  constante del enum en el código corrompa datos ya guardados como número).
- **Lombok:** `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` en todas las
  entidades — JPA exige el constructor sin argumentos (proxies/reflection), el resto es para
  poder construirlas con `Entity.builder()...build()` en servicios y tests.

## Índice de entidades

| Entidad | Tabla | Schema |
|---|---|---|
| [`Organization`](#organization) | `organizations` | `prisma` |
| [`User`](#user) | `users` (+ `user_roles`) | `prisma` |
| [`CatalogVersion`](#catalogversion) | `catalog_versions` | `prisma` |
| [`CatalogFunction`](#catalogfunction) | `catalog_functions` | `prisma` |
| [`CatalogCategory`](#catalogcategory) | `catalog_categories` | `prisma` |
| [`CatalogSubcategory`](#catalogsubcategory) | `catalog_subcategories` | `prisma` |
| [`CatalogRequirement`](#catalogrequirement) | `catalog_requirements` | `prisma` |
| [`CatalogRequirementSubcategory`](#catalogrequirementsubcategory) | `catalog_requirement_subcategories` | `prisma` |
| [`CatalogControl`](#catalogcontrol) | `catalog_controls` | `prisma` |
| [`CommunityProfile`](#communityprofile) | `community_profiles` (+ `community_profile_controls`) | `prisma` |
| [`Evaluation`](#evaluation) | `evaluations` | `prisma` |
| [`EvaluationResponse`](#evaluationresponse) | `evaluation_responses` | `prisma` |
| [`MaturityResult`](#maturityresult) | `maturity_results` | `prisma` |
| [`Evidence`](#evidence) | `evidence` | `prisma` |
| [`ImprovementPlan`](#improvementplan) | `improvement_plans` | `prisma` |
| [`AuditObservation`](#auditobservation) | `audit_observations` | `prisma` |
| [`AuditLog`](#auditlog) | `audit_logs` | `audit` |

---

### `Organization`

```java
@Entity @Table(name = "organizations", schema = "prisma")
```

| Campo Java | Columna | Tipo/Anotación |
|---|---|---|
| `id` | `id` | `UUID`, `@Id @GeneratedValue(UUID)` |
| `name` | `name` | `@Column(nullable=false, length=200)` |
| `rut` | `rut` | `@Column(unique=true, length=20)` — **opcional** (nullable). Uruguay usa "RUT" (Colombia y otros usan "NIT"); renombrado desde `nit` en `V17__organization_rut_optional.sql`. `UNIQUE` sigue vigente: Postgres no cuenta `NULL` contra ella, así que varias organizaciones sin RUT conviven sin chocar — el servicio normaliza explícitamente un RUT en blanco (`""`) a `NULL`, porque ahí sí Postgres los trata como valores iguales. |
| `sector` | `sector` | `@Column(length=100)` |
| `size` | `size` | `@Column(length=50)` |
| `responsible` | `responsible_id` | `@ManyToOne(LAZY) @JoinColumn(name="responsible_id")` — usuario responsable, opcional |
| `enabled` | `enabled` | `@Column(nullable=false)`, `@Builder.Default = true` — baja lógica (ver `OrganizationService.delete()`) |
| `createdAt`/`updatedAt` | `created_at`/`updated_at` | `OffsetDateTime`, `@PreUpdate` actualiza `updatedAt` |

**Sin `@OneToMany` hacia `User`/`Evaluation`:** el lado "muchos" de esas relaciones no está
mapeado desde `Organization` (no hay `List<User> users` ni `List<Evaluation> evaluations`) —
se consultan explícitamente por repositorio (`UserRepository`/`EvaluationRepository` filtrando
por `tenant_id`/`organization_id`), evitando cargar colecciones potencialmente enormes al pedir
una organización.

---

### `User`

```java
@Entity @Table(name = "users", schema = "prisma")
```

| Campo Java | Columna | Tipo/Anotación |
|---|---|---|
| `email` | `email` | `@Column(nullable=false, unique=true, length=255)` |
| `firstName`/`lastName` | `first_name`/`last_name` | `@Column(nullable=false, length=100)` |
| `passwordHash` | `password_hash` | `@Column(length=255)`, nullable — respaldo local (Argon2id vía `PasswordHasher`), la fuente de verdad es Keycloak |
| `keycloakId` | `keycloak_id` | `UUID`, nullable = "no provisionado en Keycloak todavía" |
| `tenant` | `tenant_id` | `@ManyToOne(LAZY) @JoinColumn(name="tenant_id")` |
| `enabled` | `enabled` | `@Column(nullable=false)` |
| `roles` | tabla `user_roles` | Ver abajo |

**Colección de roles (`@ElementCollection`, no `@ManyToMany`):**

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "user_roles", schema = "prisma", joinColumns = @JoinColumn(name = "user_id"))
@Column(name = "role")
@Enumerated(EnumType.STRING)
private Set<UserRole> roles = new HashSet<>();
```

`UserRole` no es una entidad propia (no tiene identidad ni atributos propios más allá del nombre
del rol) — es un **tipo de valor** (`@ElementCollection`), correcto para este caso frente a
modelarlo como una entidad `Role` con `@ManyToMany`. Es la única colección `EAGER` de todo el
modelo: se justifica porque los roles se consultan en *cada* request autenticado
(`@PreAuthorize`/`CurrentUserService`), así que cargarlos lazy solo movería el mismo costo a un
`SELECT N+1` garantizado en vez de evitarlo.

---

### `CatalogVersion` / `CatalogFunction` / `CatalogCategory`

Las tres siguen exactamente el mismo patrón de **composición estricta** en cadena
(`CatalogVersion → CatalogFunction → CatalogCategory → CatalogSubcategory`):

```java
@OneToMany(mappedBy = "<padre>", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
@OrderBy("sortOrder ASC")
private List<Hijo> hijos = new ArrayList<>();
```

- `cascade = ALL` + `orphanRemoval = true`: persistir/borrar el padre persiste/borra toda la
  cadena hacia abajo (coherente con el `ON DELETE CASCADE` real en cada FK, ver
  [ER](diagramas/modelo-entidad-relacion.md)) — en la práctica el catálogo no se borra nunca, solo
  se desactiva (`CatalogVersion.active = false`), pero el mapeo soporta el borrado si hiciera
  falta en una migración de datos.
- `@OrderBy("sortOrder ASC")`: Hibernate agrega el `ORDER BY` en el `SELECT` de la colección — el
  orden de presentación del catálogo (que importa: es un checklist secuencial) no depende de que
  cada `Service` se acuerde de ordenar manualmente.
- `@ManyToOne(LAZY) @JoinColumn(name = "<padre>_id")` en el lado "hijo" — `CatalogCategory` usa
  `function_` (con guión bajo) como nombre de campo Java porque `function` colisiona con la
  palabra reservada SQL `function` en algunos contextos de HQL; la columna real sigue llamándose
  `function_id`.

`CatalogRequirement` tiene `description` como `@Column(nullable = false, columnDefinition =
"TEXT")` en vez de tener también un `name` corto — el catálogo real modela los Requisitos como un
texto descriptivo único, sin código de nombre separado.

---

### `CatalogRequirement`

```java
@Entity @Table(name = "catalog_requirements", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `version` | `version_id` | `@ManyToOne(LAZY)`, FK directa a `CatalogVersion` — `code` es único por versión (`UNIQUE(version_id, code)`) |
| `controls` | — | `@OneToMany(mappedBy="requirement", cascade=ALL, orphanRemoval=true)`, composición estricta hacia `CatalogControl` |
| `subcategoryLinks` | — | `@OneToMany(mappedBy="requirement", cascade=ALL, orphanRemoval=true)` hacia `CatalogRequirementSubcategory` — ver abajo |

**Un Requisito puede pertenecer a varias Subcategorías** (p.ej. un mismo Requisito de gestión de
riesgos aplica tanto a la Subcategoría de identificación de activos como a la de continuidad): a
diferencia del resto de la cadena, `CatalogRequirement` NO cuelga de `CatalogSubcategory` por
`@OneToMany` directo — el vínculo pasa por la tabla de asociación explícita
`CatalogRequirementSubcategory`, así que persistir/borrar un Requisito ya no cae en cascada desde
ninguna Subcategoría puntual: cuelga directo de `CatalogVersion` (ver `version` arriba), que es
quien de verdad lo posee.

`getSubcategories()` (en `CatalogRequirement`) y `getRequirements()` (en `CatalogSubcategory`) son
getters de conveniencia que recorren `subcategoryLinks`/`requirementLinks` — no son columnas ni
mapeos JPA propios, solo evitan que cada call site tenga que conocer la tabla intermedia.

---

### `CatalogRequirementSubcategory`

```java
@Entity @Table(name = "catalog_requirement_subcategories", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `requirement` | `requirement_id` | `@ManyToOne(LAZY, cascade={PERSIST,MERGE})` — el único camino por el que un Requisito nuevo se persiste al importar un catálogo (ver `CatalogService.importCatalog`), ya que ya no cuelga de ninguna colección `cascade=ALL` del árbol principal |
| `subcategory` | `subcategory_id` | `@ManyToOne(LAZY)` — llega persistida por el camino normal (`CatalogCategory.subcategories`, `cascade=ALL`), no necesita cascade acá |
| `sortOrder` | `sort_order` | orden del Requisito dentro de esa Subcategoría puntual |

Tabla de asociación explícita (no un `@ManyToMany` liso) para poder llevar `sortOrder` por par
Requisito–Subcategoría, igual que el resto del árbol del catálogo. `UNIQUE(requirement_id,
subcategory_id)` a nivel de base evita enlazar el mismo par dos veces.

---

### `CatalogControl`

```java
@Entity @Table(name = "catalog_controls", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `requirement` | `requirement_id` | `@ManyToOne(LAZY)`, lado "hijo" de la cadena de composición |
| `description` | `description` | `@Column(nullable=false, columnDefinition="TEXT")` |
| `targetLevel` | `target_level` | `Integer`, `CHECK (target_level BETWEEN 1 AND 5)` a nivel DB (el catálogo real MCU 5.0 solo usa 1-4) |

Es el nodo hoja del catálogo y el punto de unión hacia el dominio de evaluación: referenciado por
`EvaluationResponse`, `Evidence`, `ImprovementPlan`, `AuditObservation` y, en `@ManyToMany`, por
`CommunityProfile`.

---

### `CommunityProfile`

```java
@Entity @Table(name = "community_profiles", schema = "prisma")
```

```java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
    name = "community_profile_controls", schema = "prisma",
    joinColumns = @JoinColumn(name = "profile_id"),
    inverseJoinColumns = @JoinColumn(name = "control_id"))
private Set<CatalogControl> controls = new HashSet<>();
```

Única relación `@ManyToMany` real del modelo (todas las demás N:M conceptuales, como
`User`↔`UserRole`, se resolvieron con `@ElementCollection` porque uno de los dos lados no es una
entidad de negocio propia). `Set` en vez de `List`: la tabla puente no tiene columna de orden
(`sort_order`), así que no hay una secuencia que preservar.

---

### `Evaluation`

```java
@Entity @Table(name = "evaluations", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `organization` | `organization_id` | `@ManyToOne(LAZY)`, `nullable=false` — raíz del aislamiento multi-tenant |
| `catalogVersion` | `catalog_version` | `String`, `@Builder.Default = "5.0"` (no es FK a `catalog_versions.version`, es el string de versión) |
| `communityProfile` | `community_profile_id` | `@ManyToOne(LAZY)`, nullable — `null` = catálogo completo |
| `status` | `status` | `@Enumerated(STRING)`, enum `Status` (`DRAFT`…`ARCHIVED`) |
| `globalMaturity` | `global_maturity` | `Integer`, nullable — agregado calculado y **almacenado** (ver [normalización](diagramas/normalizacion-bd.md)) |
| `createdBy` | `created_by` | `@ManyToOne(LAZY)`, nullable |

El enum `Status` es un `enum` anidado (`Evaluation.Status`), no una clase de nivel superior — es
un patrón usado en todas las entidades con estado propio (`AuditObservation.ObservationType`/
`ObservationStatus`, `ImprovementPlan.Priority`/`PlanStatus`) para dejar explícito que ese enum
solo tiene sentido en el contexto de esa entidad.

---

### `EvaluationResponse`

```java
@Entity @Table(name = "evaluation_responses", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `evaluation` | `evaluation_id` | `@ManyToOne(LAZY)`, `nullable=false` |
| `control` | `control_id` | `@ManyToOne(LAZY)`, `nullable=false` |
| `compliant` | `compliant` | `Boolean`, `@Builder.Default = false` — reemplazó a un `level INT` en V11 (ver [normalización](diagramas/normalizacion-bd.md)) |
| `respondedBy` | `responded_by` | `@ManyToOne(LAZY)`, nullable |

Restricción `UNIQUE(evaluation_id, control_id)` a nivel DB (no expresada como anotación JPA —
Hibernate no la valida en memoria, la hace cumplir Postgres): una evaluación tiene **una sola**
respuesta vigente por control; volver a responder es un `UPDATE`, no un nuevo `INSERT`.

---

### `MaturityResult`

```java
@Entity @Table(name = "maturity_results", schema = "prisma")
```

Único caso del modelo con columnas `_id` **sin** `@ManyToOne`/`@JoinColumn` — son `UUID` sueltos
(`functionId`, `categoryId`, `subcategoryId`) sin relación JPA hacia las tablas de catálogo,
acompañados de su copia de texto (`functionName`, `categoryName`, `subcategoryName`) — un
snapshot desnormalizado a propósito, justificado en detalle en
[`normalizacion-bd.md`](diagramas/normalizacion-bd.md#la-excepción-deliberada-maturity_results).
La única relación JPA real de esta entidad es `evaluation` (`@ManyToOne(LAZY)`, `nullable=false`).

`subcategoryName` es `@Column(length = 200)` en la anotación Java pero la columna real es `TEXT`
desde `V12` — Hibernate en modo `validate` no lo rechaza (mismo patrón ya usado en
`CatalogSubcategory.name`, ver más abajo); el `length` en la anotación documenta la intención
original, no es una restricción que Hibernate haga cumplir contra una columna `TEXT`.

---

### `Evidence`

```java
@Entity @Table(name = "evidence", schema = "prisma")
```

| Campo | Columna | Nota |
|---|---|---|
| `control` | `control_id` | `@ManyToOne(LAZY)`, **nullable** — evidencia puede no estar atada a un control puntual |
| `storageKey` | `storage_key` | `@Column(nullable=false, length=500)` — clave del objeto en MinIO, no la URL (las URLs son firmadas y expiran) |
| `aiIndexed` | `ai_indexed` | `boolean` primitivo (no `Boolean`) — único caso del modelo; `@Builder.Default = false` |

---

### `ImprovementPlan`

```java
@Entity @Table(name = "improvement_plans", schema = "prisma")
```

Enums anidados `Priority` (`LOW`/`MEDIUM`/`HIGH`) y `PlanStatus`
(`PENDING`/`IN_PROGRESS`/`COMPLETED`/`OVERDUE`), ambos `@Enumerated(STRING)`. `control` es
`@ManyToOne(LAZY)` nullable (una acción de mejora puede ser general, no atada a un control).

---

### `AuditObservation`

```java
@Entity @Table(name = "audit_observations", schema = "prisma")
```

Enums anidados `ObservationType` (`OBSERVATION`/`NON_CONFORMITY`/`RECOMMENDATION`) y
`ObservationStatus` (`OPEN`/`IN_PROGRESS`/`RESOLVED`/`CLOSED`). Misma forma que `ImprovementPlan`:
`evaluation` obligatorio, `control` y `createdBy` opcionales.

---

### `AuditLog`

```java
@Entity @Table(name = "audit_logs", schema = "audit")
```

El único caso de `@JdbcTypeCode` del modelo:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Lob
@Column(columnDefinition = "jsonb")
private String payload;
```

El campo Java es un `String` plano (JSON serializado a mano por `AuditLogService`, no un objeto
tipado) pero se persiste como `jsonb` real en Postgres (no `text`), vía el tipo JDBC de Hibernate
6 para JSON — permite indexar/consultar el payload con operadores `jsonb` de Postgres si hiciera
falta, sin forzar al código Java a modelar un esquema fijo para algo que por diseño es de forma
variable (el payload de auditoría difiere según la acción). `userId`/`tenantId` son `UUID` sueltos
sin `@ManyToOne` — ver la justificación de por qué esta entidad vive fuera del grafo de relaciones
del dominio en [`normalizacion-bd.md`](diagramas/normalizacion-bd.md).

`resource` (`@Column(nullable=false, length=100)`) es texto libre, no una FK: por convención lleva
la forma `"tipo:id"` (ej. `"user:38dab301-..."`), que `AuditLogService.list()` parsea en memoria
(no en SQL) para resolver el nombre real de la entidad al listar (ver `resolveResourceName`,
consulta en batch por tipo, no N+1). La acción `LOGIN_FAILED` (`LoginFailureAuditSyncService`) es
la única que no sigue esa convención: ahí `resource` es directamente el email/username intentado,
sin id — un intento contra un email que no existe no tiene ninguna fila que referenciar.

## Referencias

- [`diagramas/diagrama-clases.md`](diagramas/diagrama-clases.md) — vista UML de estas mismas
  relaciones.
- [`diagramas/modelo-entidad-relacion.md`](diagramas/modelo-entidad-relacion.md) — el DDL real que
  este mapeo debe respetar (`ddl-auto=validate` en tests lo comprueba en cada build).
- [`diagramas/normalizacion-bd.md`](diagramas/normalizacion-bd.md) — por qué el esquema está en
  3FN salvo la excepción documentada.
