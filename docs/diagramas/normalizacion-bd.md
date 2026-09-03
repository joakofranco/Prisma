# 📐 Normalización de la Base de Datos — PRISMA

> Análisis de forma normal de cada tabla del schema `prisma` (y `audit.audit_logs`), sobre el
> esquema real definido en las migraciones Flyway (`V1` a `V12`). Ver el modelo completo en
> [`modelo-entidad-relacion.md`](modelo-entidad-relacion.md).

## Resumen

El esquema está en **Tercera Forma Normal (3FN)** en la totalidad de sus tablas transaccionales,
con **una única desnormalización deliberada y documentada**: `maturity_results`, que se trata en
detalle en la sección final. No hay grupos repetitivos, ni dependencias transitivas involuntarias,
ni columnas multivaluadas (`user_roles` existe justamente para evitar una columna
`roles VARCHAR[]` en `users`).

## 1FN — Primera Forma Normal

**Criterio:** valores atómicos, sin columnas repetidas ni multivaluadas, cada fila identificable
por una clave primaria.

Se cumple en todas las tablas:
- Todas las columnas son de tipos escalares (`UUID`, `VARCHAR`, `TEXT`, `INT`, `BOOLEAN`,
  `TIMESTAMPTZ`, `DATE`) o `JSONB` (usado en un único caso, `audit_logs.payload`, para un payload
  de auditoría de forma variable por naturaleza — no para evitar modelar columnas).
- **`user_roles`** es la prueba de que se evitó una columna multivaluada: en vez de
  `users.roles VARCHAR[]`, cada rol de un usuario es una fila propia
  (`PRIMARY KEY (user_id, role)`), permitiendo `JOIN`, índices y `ON DELETE CASCADE` normales.
- **`community_profile_controls`** cumple el mismo rol para la relación N:M
  `community_profiles` ↔ `catalog_controls`: no hay una columna `control_ids` serializada.
- Todas las tablas tienen `id UUID PRIMARY KEY` (o clave compuesta explícita en las tablas
  puente: `user_roles`, `community_profile_controls`), nunca una fila sin identificador único.

## 2FN — Segunda Forma Normal

**Criterio:** cumple 1FN y **ningún atributo no-clave depende de solo una parte de una clave
primaria compuesta** (relevante solo en las tablas con PK compuesta).

- **`user_roles (user_id, role)`**: no tiene atributos no-clave — la tabla completa es la clave.
  Cumple trivialmente.
- **`community_profile_controls (profile_id, control_id)`**: mismo caso, sin atributos
  adicionales.
- El resto de las tablas usa `id UUID` como clave primaria simple (no compuesta), así que 2FN se
  reduce a 1FN + toda columna depende de la clave completa — cumplido en todos los casos: por
  ejemplo, en `catalog_controls`, `code`/`description`/`target_level`/`sort_order` dependen de
  `id`, no de `requirement_id` (que es una FK, no parte de la PK).

## 3FN — Tercera Forma Normal

**Criterio:** cumple 2FN y **ningún atributo no-clave depende transitivamente de otro atributo
no-clave** (todo atributo depende únicamente de la clave primaria).

Revisión tabla por tabla de las que tienen más atributos:

| Tabla | Atributos no-clave | ¿Dependen solo de la PK? |
|---|---|---|
| `organizations` | `name`, `rut`, `sector`, `size`, `enabled`, `responsible_id` | Sí — ninguno se puede derivar de otro atributo no-clave de la misma fila. |
| `users` | `email`, `first_name`, `last_name`, `password_hash`, `keycloak_id`, `tenant_id`, `enabled` | Sí. |
| `evaluations` | `name`, `organization_id`, `catalog_version`, `status`, `global_maturity`, `community_profile_id`, `created_by` | Sí — `global_maturity` es un valor *calculado* por `EvaluationService.calculateMaturity`, pero se **almacena** (no se recalcula al vuelo en cada lectura) por rendimiento: es una decisión de materializar un cálculo costoso, no una dependencia transitiva de otra columna de `evaluations` — depende de datos de OTRA tabla (`maturity_results`), lo cual es un patrón válido de caché de agregado, siempre que (como acá) se recalcule explícitamente en cada cambio relevante en vez de quedar desactualizado. |
| `catalog_controls` | `code`, `description`, `target_level`, `sort_order` | Sí. |
| `evaluation_responses` | `control_id`, `compliant`, `observations`, `responded_by`, `responded_at` | Sí. |
| `evidence` | `evaluation_id`, `control_id`, `file_name`, `file_size`, `file_type`, `storage_key`, `uploaded_by`, `uploaded_at`, `description`, `ai_indexed` | Sí — `file_size`/`file_type` son metadata propia del archivo subido, no derivables de `file_name` ni de otra columna. |

No se encontraron dependencias transitivas problemáticas (p.ej. guardar `organization.name` en
`evaluations` sería una violación de 3FN — y no se hace: `evaluations` solo guarda
`organization_id`, el nombre se resuelve con `JOIN` en `EvaluationService`/`toDto`).

## BCNF (Boyce-Codd)

Todas las tablas cumplen BCNF: en cada una, la única clave candidata es `id` (o la PK compuesta en
las tablas puente), y no existen determinantes funcionales adicionales que no sean la clave
primaria misma. No hay claves candidatas superpuestas que requieran descomposición adicional.

## La excepción deliberada: `maturity_results`

`maturity_results` **no está en 3FN estricta** y es la única tabla del esquema donde esto es así a
propósito:

```sql
CREATE TABLE prisma.maturity_results (
    ...
    function_id       UUID NOT NULL,   -- sin FK
    function_name     VARCHAR(200) NOT NULL,   -- copia de catalog_functions.name
    category_id       UUID NOT NULL,   -- sin FK
    category_name     VARCHAR(200) NOT NULL,   -- copia de catalog_categories.name
    subcategory_id    UUID NOT NULL,   -- sin FK
    subcategory_name  TEXT NOT NULL,   -- copia de catalog_subcategories.name
    ...
);
```

`function_name`, `category_name` y `subcategory_name` son **copias desnormalizadas** de columnas
que ya existen en `catalog_functions`/`catalog_categories`/`catalog_subcategories` — en 3FN
estricta, `maturity_results` debería guardar solo los tres `_id` (FK) y resolver los nombres con
`JOIN` cada vez que se muestran resultados.

**Por qué es una desnormalización deliberada y no un error de diseño:**

1. **`maturity_results` es un snapshot histórico, no una vista del catálogo actual.**
   `EvaluationService.calculateMaturity` borra y reinserta por completo los resultados de una
   evaluación cada vez que se recalcula (`matRepo.deleteAllInBatch(...)` + `saveAll(...)`). Si
   guardara solo los `_id` y el catálogo cambiara después (una subcategoría se renombra, o una
   versión del catálogo se desactiva), los resultados ya calculados de evaluaciones **pasadas**
   mostrarían el nombre *actual* del catálogo en vez del nombre vigente al momento de la
   evaluación — rompiendo la trazabilidad histórica que el módulo de auditoría necesita.
2. **Ni siquiera hay FK a las tablas de catálogo** (`function_id`/`category_id`/`subcategory_id`
   son `UUID NOT NULL` sin `REFERENCES`): son deliberadamente solo una referencia informativa, no
   una relación referencial — reforzando que este registro debe sobrevivir intacto aunque el nodo
   del catálogo que le dio origen cambie o incluso se borre en cascada más adelante.
3. El costo de esta redundancia es bajo y acotado: son 3 columnas de texto por fila de
   `maturity_results` (una por evaluación × subcategoría tocada), no una tabla completa
   duplicada, y no hay riesgo de inconsistencia de escritura porque **nunca se actualizan** — se
   escriben una vez por cálculo y no vuelven a tocarse hasta el próximo recálculo completo.

Este es el único punto de desnormalización intencional del esquema; el resto de las tablas
(incluyendo `evaluations.global_maturity`, que también es un valor calculado y almacenado) se
mantienen relacionalmente correctas por FK real y sin duplicar texto de otras tablas.

## Referencias

- Modelo entidad-relación completo: [`modelo-entidad-relacion.md`](modelo-entidad-relacion.md)
- Diagrama de clases (modelo JPA): [`diagrama-clases.md`](diagrama-clases.md)
- Mapeo objeto-relacional: [`../mapeo-jpa.md`](../mapeo-jpa.md)
