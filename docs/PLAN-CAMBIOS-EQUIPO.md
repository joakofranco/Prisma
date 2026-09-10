# Plan de cambios — Madurez alineada a la planilla de Agesic + rediseño de perfiles

> **Cómo usar este documento.** Un **paquete 0** chico (config del `pom.xml`) que va
> primero, y **3 paquetes de trabajo** de tamaño parejo (~700–850 LoC cada uno). Cada
> integrante toma un paquete, le pasa **su sección** (Contexto general + su Paquete +
> "Archivos compartidos") a su propio Claude Code y la implementa en una rama
> `feat/pkgN-...`. Cada sección es autocontenida.
>
> Existe una implementación de referencia completa en el árbol de trabajo actual
> (sin commitear) por si querés diffear en vez de reimplementar. **Nada se pushea
> automáticamente** — cada paquete lo subís vos por separado.

---

## 1. Contexto general (leer sí o sí)

### El problema

La pestaña de resultados de una evaluación no reproduce los números de la **planilla
oficial de Agesic** (`Perfil comunitario BASICO vFinal_3.xlsx` → `Graficos.png`),
aunque el catálogo y el perfil sean correctos. Además, **cargar un perfil comunitario
desde la web es impracticable**: el selector actual renderiza un checkbox por cada
aparición de cada control en el árbol (>1900 filas), sin "seleccionar todo" ni
búsqueda, y la sesión de Keycloak expira antes de terminar.

### El análisis (verificado por SQL + parseo de los .xlsx/.pdf de Agesic)

1. **El catálogo `5.0` del seed (V9) es correcto**: coincide 100 % con la planilla
   oficial en texto de controles, nivel objetivo y mapeo requisito↔subcategoría.
   El único error era el JSON `5.1` que se importó a mano (le faltaba `GV.RR-04`).
   La "Guía de implementación" PDF es una revisión **anterior** (≈64 controles con
   texto distinto) → **no** se usa como fuente.
2. **PRISMA calcula la madurez distinto que Agesic.** `EvaluationService.calculateMaturity`
   mete cada control en **todas** las subcategorías a las que mapea su requisito
   (**1921** pares control↔subcategoría). Agesic usa una **asignación curada**
   (**1013** pares, subconjunto de la anterior) y calcula la madurez de cada
   subcategoría **sólo sobre los controles ubicados bajo ella**.
3. **El promedio global** de PRISMA se hacía sobre las subcategorías "con alguna
   respuesta"; Agesic promedia sobre **todas** las subcategorías de la versión
   (0 en las que el perfil no cubre).
4. **El perfil Básico** = los 165 controles marcados en **verde** en la planilla
   (columnas J/M/P/R de la hoja "Perfil BASICO"). 7 de esos 165 están verdes de
   forma inconsistente entre apariciones → 3 subcategorías quedan 1 nivel arriba en
   PRISMA. Se documenta y se acepta (igualarlo requeriría un perfil por
   `(subcategoría, control)`).

### El resultado esperado

Con el mapeo curado control↔subcategoría + el promedio sobre todas las subcategorías +
el modelo alineado a la planilla (§1.7 Decisión 2), PRISMA reproduce la planilla nueva
en **103 / 103 subcategorías** para los 3 perfiles (Básico 1.165, Estándar 1.689,
Avanzado 2.029). Lo prueba `MaturityValidationIT` (baseline = `Planilla MCU 5.0 Básico.xlsx`,
sin `KNOWN_DIFFS`).

### Alcance NO tocado

- **Generación de reportes** (`ReportService`, Excel/PDF) y `ReportsView.vue`. El
  cambio de `globalMaturity` (denominador nuevo) cambia números que el reporte lee,
  **no su código**. Si algún paquete necesita tocar `ReportService`, **frenar y
  consultar**.

---

## 2. Convenciones

- **Nada se pushea automáticamente.** Cada paquete se sube como su propia rama/PR, por
  vos, cuando decidas. Este documento y el árbol de trabajo sólo dejan los archivos listos.
- Rama por paquete: `feat/pkg0-jdk25`, `feat/pkg1-perfiles`, `feat/pkg2-resultados`,
  `feat/pkg3-calculo`.
- Backend: `cd apps/backend-core && mvn -q test -Dtest=<Clase>`. Formato: `mvn spotless:apply`.
  Los IT (`@SpringBootTest` + Testcontainers) requieren Docker y **pkg0** (config de
  surefire/failsafe para JDK 25) aplicado.
- Frontend: `cd apps/frontend`, luego `npm run type-check`, `npm run lint`, `npm run test:unit`.
- **Orden de integración**: `pkg0` primero (desbloquea los IT). `pkg3` (backend) y `pkg1`
  (endpoint + FE perfiles) en paralelo; `pkg2` (FE resultados) mergea después de `pkg3`.
- El generador `scripts/mcu50/build.py` emite **`V19__mcu50_align_agesic_2025.sql`**
  (migración, se commitea, inmutable tras el merge) y `scripts/mcu50/expected_basico.json`
  (recurso de test, se commitea). Las 3 planillas fuente viven en `docs/mcu-5.0/`.

---

## 2.b Paquete 0 — `pom.xml` para JDK 25 (va primero, ~15 LoC, 1 commit)

Toda la stack ya corre en Java 25 (Docker `eclipse-temurin-25`, CI, `JAVA_HOME` local); sólo
falta config de test. Sin esto los `@SpringBootTest` + Testcontainers revientan al forkear la
VM (self-attach de ByteBuddy removido en JDK 25).

En `apps/backend-core/pom.xml`, agregar a **`maven-surefire-plugin`** y **`maven-failsafe-plugin`**:

```xml
<configuration>
  <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Djdk.attach.allowAttachSelf=true -Xshare:off</argLine>
</configuration>
```

`@{argLine}` = expansión tardía para no pisar el `-javaagent` de JaCoCo. Opcional:
`<java.version>21` → `25`. **Aceptación:** `mvn -q test -Dtest=PrismaApplicationIT` pasa local.

---

## 3. Paquete 1 — Carga de perfiles comunitarios

**Owner:** 1 dev · **~700 LoC · ~3 commits**
**Depende de:** nada (el endpoint que necesita el frontend lo hace este mismo paquete).
**Comparte archivos con pkg3:** `CatalogService.java`, `CatalogController.java`,
`Dto.java` → ver §6.

### 1.1 Backend — endpoint de lista plana de controles

Nuevo `GET /api/catalog/{version}/controls` → `List<CatalogControlFlatDto>`.
Sin `@PreAuthorize` extra (lectura de catálogo, igual que `getByVersion`).
404 si la versión no existe.

- **`Dto.java`** — nuevo record:
  ```java
  public record CatalogControlFlatDto(
      UUID id, String code, String description, Integer targetLevel,
      String requirementCode, String requirementDescription, String domain) {}
  ```
  Y agregar `@Size(max = 5000, message = "Un perfil no puede tener más de 5000 controles")`
  a `CreateCommunityProfileDto.controlIds`.
- **`CatalogController.java`** — un `@GetMapping("/{version}/controls")` que delega en
  `service.listControlsFlat(version)`.
- **`CatalogService.java`** — nuevo método `listControlsFlat(String version)`:
  1. 404 si `versionRepo.findByVersion(version)` vacío.
  2. `controlRepo.findByVersionOrdered(version)` (ya devuelve la lista canónica).
  3. Mapear a DTO: `requirementCode = control.getRequirement().getCode()`,
     `domain` = prefijo alfabético del `requirementCode` (`"AD.2"` → `"AD"`).
  4. **Ordenar** por `(domain, número de requisito, número de control)` con
     comparación **numérica** (`AD.2-2` antes de `AD.2-10`). Helper: convertir cada
     código a una clave donde cada grupo de dígitos va con padding
     (`"AD|000002|000010"`).

### 1.2 Backend — tests

- `CatalogServiceTest`: `listControlsFlat` ordena `AD.*` antes de `CA.*` y `AD.2-2`
  antes de `AD.2-10`; deriva `domain`/`requirementCode`; 404 si la versión no existe.
  (El mock `CatalogService` gana un `@Mock CatalogControlRepository controlRepo` en el
  constructor — coordinar con pkg3 que también toca ese constructor, §6.)
- `ControllersMockMvcTest`: `GET /api/catalog/{v}/controls` → 200 + shape.

### 1.3 Frontend — selector nuevo

**Nuevo `apps/frontend/src/components/profiles/CommunityProfileControlPicker.vue`**:
- Props: `controls: CatalogControlFlat[]`, `modelValue: string[]` (UUIDs), `loading?`.
- Emite `update:modelValue`.
- **Agrupado por `domain`**, dominios ordenados alfabéticamente, cada uno una
  sección **colapsable** (colapsada por defecto) con encabezado
  `SIGLA · Nombre — seleccionados / total`.
- Por dominio: **checkbox tri-estado** (`checked` / `indeterminate.prop` /
  unchecked) que marca/desmarca todos los controles **visibles** de ese dominio.
- Barra superior: **buscador** (código + descripción), **filtro por nivel objetivo**
  (casillas 1–4), botones **Seleccionar todo / Limpiar todo / Seleccionar visibles /
  Limpiar visibles** (respetan buscador + nivel → "todos los ≤ N" en un clic),
  contador global.
- Reusar el patrón `expanded = reactive(new Set<string>())` + `toggle()` de
  `apps/frontend/src/components/catalog/CatalogTreeEditor.vue`.
- Etiquetas de dominio: `MCU_DOMAIN_LABELS` en `apps/frontend/src/utils/constants.ts`
  (`Record<string,string>`, 16 familias MCU: `AD → "Adquisición y Desarrollo"`,
  `CA → "Control de Acceso"`, `GR → "Gestión de Riesgos"`, … fallback al prefijo).

**`apps/frontend/src/views/profiles/CommunityProfilesView.vue`** — reemplazar el
`v-for` anidado del selector por `<CommunityProfileControlPicker>`. `form.controlIds`
sigue siendo `string[]` de UUIDs, el payload no cambia. Agregar en el modal de
creación:
- **Duplicar desde un perfil existente** (`BaseSelect` con los perfiles de la misma
  versión; al elegir → `communityProfilesService.getById` → `form.controlIds`).
- **Exportar CSV** (una columna `code`, desde `form.controlIds` mapeados a código) y
  **Importar** (`.csv`/`.json` → códigos → ids; códigos desconocidos en un
  `BaseAlert`, no rompen; checkbox "sumar a la actual"). Reusar el helper de descarga
  de `apps/frontend/src/components/settings/CatalogImportPanel.vue`.

**`apps/frontend/src/services/resources.ts`** — `catalogService.getControls(version)`
→ `apiClient.get<CatalogControlFlat[]>('/catalog/' + version + '/controls')`.
**`apps/frontend/src/types/index.ts`** — `interface CatalogControlFlat { id; code;
description; targetLevel: MaturityLevel; requirementCode; requirementDescription; domain }`.

### 1.4 Frontend — refresh silencioso de token (transversal, entra acá)

Hoy no hay refresh proactivo: una pantalla larga (armar un perfil) no dispara
requests, el token vence y recién se descubre al guardar (401).
- **`apps/frontend/src/services/auth.ts`**: en el `.then(authenticated => …)` de
  `initKeycloak`, `keycloak.onTokenExpired = () => void keycloak.updateToken(30)`.
  Exportar `startTokenRefresh()` (`setInterval(() => updateToken(70), 60_000)`) y
  `stopTokenRefresh()`.
- **`apps/frontend/src/stores/auth.ts`**: `startTokenRefresh()` en `init()` tras
  autenticar; `stopTokenRefresh()` en `logout()`.
- **`apps/frontend/src/services/api.ts`**: el interceptor 401 tiene un `catch`/`logout`
  muerto (`refreshToken()` nunca lanza, devuelve `false`). Cambiar a: `const ok =
  await authStore.refreshToken(); if (!ok) { await authStore.logout(); return
  Promise.reject(error); }` + guarda `_retried` para no reintentar en loop.

### 1.5 Perfiles comunitarios → los siembra pkg3

Los 3 perfiles (Básico 165 / Estándar 234 / Avanzado 309) ya **no** se generan con un
script de perfiles ni se importan por el selector: se **siembran** en `V19` (pkg3, §1.7 /
§5.6), leyendo `Cumplimiento!D == "Si"` de cada `docs/mcu-5.0/Planilla MCU 5.0 *.xlsx`.
pkg1 sólo consume esos perfiles (selector de la web); no toca scripts de catálogo.

### 1.6 Aceptación pkg1

- `mvn -q test -Dtest=CatalogServiceTest,ControllersMockMvcTest` verde.
- `npm run type-check && npm run lint && npm run test:unit` verde.
- Manual: crear un perfil eligiendo un dominio entero en 1 clic; guardar; reabrir
  (editar) → misma selección (tri-estado correcto). Filtro nivel ≤ 2 + "seleccionar
  visibles" → sólo N1/N2. Exportar → editar CSV → importar → coincide.

### 1.7 El seed 5.0 alineado a las planillas Agesic 2025

Agesic publicó planillas con **formato nuevo** (`docs/mcu-5.0/Planilla MCU 5.0 *.xlsx`):

- Controles **requeridos** por perfil: pestaña **`Cumplimiento`** (fila 8 encabezado),
  requerido = **col `D` == "Si"**. Ya no se marca por color.
- Mapeo subcategoría↔control↔nivel: pestaña **`Madurez Subcategoría`** (`D`=ID subcategoría,
  `H..K`=controles por Nivel 1..4). `L` = nivel N más alto donde **todos** los controles de
  `H..K` hasta N figuran "Cumple".

**No hay versión de catálogo separada.** Todo esto es el **seed de la versión `5.0`**, vía
una migración Flyway generada: **`V19__mcu50_align_agesic_2025.sql`**. Contiene:

1. Control nuevo **`OR.5-10`** (req `OR.5`, nivel 4) — la planilla 2025 lo trae y V9 no.
   (`GI.2-10` ya estaba en V9.) → `5.0` pasa a **671 controles**.
2. Los **1013 pares** `catalog_control_subcategories` (mapeo curado; la tabla la crea V18).
3. Membresía de los 3 perfiles sembrados (`e1000001-…-001/002/003`) = los controles `D=Si`
   de cada planilla: **Básico 165 / Estándar 234 / Avanzado 309** (reemplaza la membresía
   por prioridad de subcategoría de V10).

Generador único: **`scripts/mcu50/build.py`** (lee las 3 planillas de `docs/mcu-5.0/`, emite
`V19` + `scripts/mcu50/expected_basico.json`). `V19` es inmutable una vez mergeada.

**Modelo de madurez alineado.** `EvaluationService.calculateMaturity` puntúa la madurez de
una subcategoría sobre **todos** los controles del marco ubicados en ella (un control sin
respuesta "cumple" frena el nivel), no sólo los del perfil — igual que la fórmula `L` de la
planilla. Emite **103** filas `MaturityResult` por evaluación. Con esto PRISMA reproduce las
3 planillas **103/103**: Básico **1.165**, Estándar **1.689**, Avanzado **2.029**.
(Antes discrepaba en `DE.CM-01` y `PR.IR-01`, donde un control no requerido de nivel bajo
frena el nivel.) `MaturityValidationIT` corre contra el catálogo **sembrado** (sin import),
`KNOWN_DIFFS` **vacío**, general `1.165`.

> Residuos de formato heredado en los .xlsx de Agesic, sin impacto (anotar): `G8="Prioridad …
> AVANZADO"` en el archivo "Estándar"; filas `GV.OV-01/02/03` vacías.
> La BD de dev puede tener una versión `5.2` de trabajos previos — `V19` no la toca; es resto
> inofensivo hasta que se resetee el volumen.

---

## 4. Paquete 2 — Resultados de madurez: tablas y gráficos

**Owner:** 1 dev · **~850 LoC · ~3 commits**
**Depende de:** conceptualmente de pkg3 (el promedio "sobre todas las subcategorías"
que hace `useMaturitySummary` es el mismo cambio de denominador que
`calculateMaturity`); pueden desarrollarse en paralelo, los números E2E sólo cuadran
con ambos mergeados.
**Comparte archivos con pkg1:** `types/index.ts` (interfaces distintas → sin
conflicto). Con pkg3: `Makefile` (secciones distintas → §6).

### 4.1 Nueva pestaña dentro de la card "Resultados de Madurez"

Se **conserva** todo lo actual (radar por función + desglose por subcategoría) y se
agrega una pestaña **"Tablas y promedios"** con el formato de `Graficos.png`:
1. **Madurez general** (2 decimales).
2. Tabla **por función**: nombre + sigla + nivel logrado (2 dec).
3. Tabla **por categoría** agrupada por función (subfila-encabezado por función).
4. **Radar por categoría** (21 puntos), una sola serie (sin "objetivo").

Todos los niveles = **media aritmética plana** de los `currentLevel` de las
subcategorías que cuelgan de cada nodo, a 2 decimales. **Denominador = TODAS las
subcategorías de la versión** (una subcategoría sin fila en `results` cuenta 0).
Media plana (la media-de-medias **no** coincide). Para el perfil Básico con la planilla
nueva: general **1.165**, GV 1.11 / ID 0.95 / PR 1.41 / DE 0.91 / RE 1.31 / RC 1.38
(hoja "Resumen" de `Planilla MCU 5.0 Básico.xlsx`; `Graficos.png` es de la versión vieja).

### 4.2 Archivos

- **`apps/frontend/src/composables/useMaturitySummary.ts`** (nuevo). Recibe
  `results: Ref<MaturityResult[]>` + `catalogFunctions: Ref<MaturityFunction[]>`.
  Devuelve computeds:
  - `general` — `sum(results.currentLevel) / (total subcategorías del árbol)`; `NaN`
    si `results` vacío.
  - `byFunction: { id, code, name, level, target, count }[]` — orden del árbol
    (`sortOrder`, = orden NIST GV→ID→PR→DE→RS→RC); `level` = suma de sus
    subcategorías presentes / **cantidad de subcategorías de esa función en el árbol**.
  - `byCategory: { id, code, name, functionId, functionCode, functionName, level,
    target, count }[]` — idem, denominador = subcategorías de la categoría en el árbol.
  - `categoryRadarItems` — `{ name: code||name, current, target }[]`.
  - Fallback si el árbol no cargó: denominador = subcategorías presentes en `results`.
- **`apps/frontend/src/components/evaluations/MaturitySummaryTables.vue`** (nuevo) —
  props `results`, `catalogFunctions`. Renderiza los 4 bloques. `<table>` a mano para
  la tabla por categoría (agrupada). Reusa `getMaturityColor` (`utils/helpers.ts`).
- **`apps/frontend/src/components/charts/MaturityRadarChart.vue`** — agregar prop
  `showTarget?: boolean` (default `true`, retrocompatible); si `false` no dibuja la
  serie "Nivel objetivo".
- **`apps/frontend/src/utils/helpers.ts`** — `formatDecimals(v: number, d = 2):
  string` → `Number.isFinite(v) ? v.toFixed(d) : '—'`.
- **`apps/frontend/src/types/index.ts`** — `code?: string` en `MaturityFunction`,
  `MaturityCategory`, `MaturitySubcategory` (el backend ya lo manda en
  `GET /catalog/{version}`; el tipo no lo declaraba).
- **`apps/frontend/src/views/evaluations/EvaluationDetailView.vue`**:
  - Guardar el árbol que ya se fetchea en `onMounted`
    (`catalogService.getByVersion(version, communityProfileId)`, hoy sólo se usa para
    contar `totalControls`) en `const catalogFunctions = ref<MaturityFunction[]>([])`.
  - Envolver el contenido del `v-else` de la card de resultados en un switcher de
    pestañas (fila de botones + `v-if`, patrón de `CatalogView.vue`):
    **"Desglose"** (lo actual, sin cambios) / **"Tablas y promedios"**
    (`<MaturitySummaryTables :results :catalog-functions>`). Ocultar el toggle
    `groupBy` y el selector de `pageSize` cuando la pestaña no es "Desglose".
- **`apps/frontend/tests/composables/useMaturitySummary.spec.ts`** (nuevo) — media
  plana; función = media de TODAS sus subcategorías (no media de medias); denominador
  = subcategorías del árbol (las no cubiertas cuentan 0); orden del árbol; fallback
  sin árbol; sin resultados → `general` NaN.

### 4.3 Infra — stack mínimo MVP (entra acá para balancear)

- **`docker-compose.mvp.yml`** (nuevo) — override con perfil `mvp` sobre 8 servicios:
  `postgres, redis, keycloak(+db), backend-core, backend-ai, frontend, nginx`.
  `backend-ai` lleva `depends_on: !reset []` (para arrancar sin `ollama`).
  **No** levanta `ollama`, `minio`, observabilidad, elastic, sonarqube, portainer.
- **`Makefile`** — targets `mvp-up | mvp-down | mvp-logs | mvp-ps | mvp-seed`
  (`$(DC) -f docker-compose.yml -f docker-compose.mvp.yml --profile mvp …`) + `.PHONY`.
- **`README.md`** — sección "Arranque mínimo (MVP, sin LLM)".

### 4.4 Aceptación pkg2

- `npm run type-check && npm run lint && npm run test:unit` verde.
- `make mvp-up` levanta 8 contenedores (sin `prisma-ollama`).
- Manual: evaluación con resultados calculados → card "Resultados de Madurez" →
  pestaña "Tablas y promedios" (con pkg3 mergeado: perfil Básico → general **1.165**,
  funciones = hoja "Resumen" de `Planilla MCU 5.0 Básico.xlsx`).

---

## 5. Paquete 3 — Backend: regla de cálculo de madurez + seed 5.0 alineado

**Owner:** 1 dev · **~800 LoC · ~3 commits** (`V19` es SQL generada, no cuenta como trabajo).
**Depende de:** pkg0 (para correr los IT).
**Comparte archivos con pkg1:** `CatalogService.java`, `CatalogController.java` → §6.
Con pkg2: `Makefile` (sección `mcu50-*` distinta de la `mvp-*`).

### 5.1 Esquema — `V18__control_subcategory_mapping.sql`

```sql
CREATE TABLE prisma.catalog_control_subcategories (
    control_id     UUID NOT NULL REFERENCES prisma.catalog_controls(id)      ON DELETE CASCADE,
    subcategory_id UUID NOT NULL REFERENCES prisma.catalog_subcategories(id) ON DELETE CASCADE,
    PRIMARY KEY (control_id, subcategory_id)
);
CREATE INDEX idx_ccs_subcategory ON prisma.catalog_control_subcategories(subcategory_id);
```
Sólo DDL. Los 1013 pares de MCU 5.0 los puebla `V19` (§5.6); un catálogo importado por JSON
los deriva en `importCatalog` (§5.4).

### 5.2 Entidad — `CatalogControl.java`

`@ManyToMany(fetch = LAZY)` a `CatalogSubcategory` vía `catalog_control_subcategories`
(mismo patrón que `CommunityProfile.controls`), `@Builder.Default private Set<…>
subcategories = new HashSet<>()`. Método:
```java
/** Subcategorías donde ESTE control cuenta para la madurez: el mapeo curado si el catálogo lo
 *  trae; si no, requirement.getSubcategories() (comportamiento previo a V18, para 5.0/5.1). */
public Collection<CatalogSubcategory> effectiveSubcategories() {
  return subcategories.isEmpty() ? requirement.getSubcategories() : subcategories;
}
```
> `@AllArgsConstructor` de `CatalogControl` gana un arg → arreglar `EntityTest`
> (`new CatalogControl(id, null, "CT1", "D", 3, 1, new HashSet<>())`).

### 5.3 Repositorio — `CatalogSubcategoryRepository.java` (nuevo)

```java
@Query("SELECT COUNT(s) FROM CatalogSubcategory s "
     + "JOIN s.category c JOIN c.function_ f JOIN f.version v WHERE v.version = :version")
long countByCatalogVersion(@Param("version") String version);
```

### 5.4 Import — `CatalogService.importCatalog`

El formato del JSON **no cambia**: los controles listados bajo un bloque
`subcategoría → requisito` son los "ubicados" en esa subcategoría. Refactor: extraer
el manejo de requisito+controles a un helper `upsertRequirement(rDto, subcategory,
version, byCode, sortOrder)` que:
- crea el `CatalogRequirement` la primera vez, lo reusa las siguientes (mapa por
  código, scope = todo el import);
- crea los controles que falten (por código);
- **`control.getSubcategories().add(subcategory)`** para cada control listado en el
  bloque (esto es el mapeo curado).
Si el JSON lista el set completo del requisito bajo cada subcategoría, el mapeo
termina siendo el mismo que requisito→subcategoría y el cálculo no cambia.
> `CatalogService` ya recibe `CatalogControlRepository controlRepo` en el constructor
> por el endpoint de pkg1 — **coordinar** (§6): un solo cambio de constructor.

### 5.5 Cálculo — `EvaluationService.calculateMaturity`

Tres cambios (método en `apps/backend-core/.../application/EvaluationService.java`):

1. **Bucketing** — `for (CatalogSubcategory sub : control.effectiveSubcategories())`
   (era `control.getRequirement().getSubcategories()`).
2. **Denominador del promedio global** — promediar `currentLevel` sobre **todas** las
   subcategorías de la versión (`subcategoryRepo.countByCatalogVersion(...)`), 0 en las no
   cubiertas.
3. **Modelo sobre el marco completo (no sobre el perfil)** — **quitar** el filtro
   `controls = controls.stream().filter(profileControlIds::contains)…`. El gate acumulativo
   ahora ve todos los controles del marco ubicados en la subcategoría; un control sin
   respuesta "cumple" (típico de los que el perfil no exige) frena el nivel — igual que la
   fórmula `L` de la planilla nueva. Se emite una fila por cada subcategoría con ≥1 control
   mapeado (para 5.x = 103). Baja `globalMaturity` de toda evaluación con perfil.
   > Constructor de `EvaluationService` gana `CatalogSubcategoryRepository
   > subcategoryRepo` → arreglar `EvaluationServiceTest` (`@Mock` + arg +
   > `when(subcategoryRepo.countByCatalogVersion(any())).thenReturn(1L)` en `setUp`).
   > Arreglar en `PrismaApplicationIT.fullEvaluationLifecyclePersistsMaturityResults`
   > la aserción `globalMaturity >= 1` → `>= 0` (con 1 subcategoría de 103 el
   > promedio redondea a 0; lo relevante es que no sea `null`).

### 5.6 Generador del seed — `scripts/mcu50/build.py` + `V19`

**Un** script Python (`scripts/mcu50/build.py`), sin tocar la app ni la BD. Lee las 3
`docs/mcu-5.0/Planilla MCU 5.0 {Básico,Estándar,Avanzado}.xlsx` y emite:

- **`apps/backend-core/src/main/resources/db/migration/V19__mcu50_align_agesic_2025.sql`**
  (generada, commiteada, **inmutable** tras el merge). Corre después de V9/V10/V15/V18; todo
  por `JOIN … ON code` (no UUIDs literales):
  1. `INSERT` de `OR.5-10` (req `OR.5`, nivel 4, `sort_order = max+1`) en `5.0`, con guarda
     `NOT EXISTS`. `GI.2-10` ya está en V9. → **671** controles.
  2. `INSERT … SELECT` de los **1013** pares `(control_code, subcat_code)` como `VALUES` en
     `catalog_control_subcategories` (`ON CONFLICT DO NOTHING`). El placement es idéntico en
     las 3 planillas → se lee de una.
  3. `DELETE` + `INSERT … SELECT` de la membresía de los 3 perfiles sembrados
     (`e1000001-…-001/002/003`) = los códigos `Cumplimiento!D == "Si"` (165 / 234 / 309);
     `UPDATE` de `description`. Nombres y `catalog_version = '5.0'` sin cambio.
  4. Bloque `DO $$ … $$` que valida los conteos (671 / 1013 / 165 / 234 / 309) y aborta si no.
- **`scripts/mcu50/expected_basico.json`** — baseline del IT: `{ answers: {code: true}
  (165), subcategory: {code: 0..4} (103), function: {GV:1.11,…,RE:1.31,RC:1.38}, general:
  1.165 }`. `subcategory` = fórmula `L` de la pestaña `Madurez Subcategoría` para el
  escenario "los 165 requeridos cumplen".

`Makefile`: `mcu50-build` (`python scripts/mcu50/build.py` + `cp expected_basico.json` a
`src/test/resources/mcu50/`) y `mcu50-validate` (`mvn -q test -Dtest=MaturityValidationIT`).
Agregar a `.PHONY`. **No hay** `mcu50-import` — Flyway siembra todo.

### 5.7 Validación — `MaturityValidationIT.java`

`@SpringBootTest` + Testcontainers (patrón de `PrismaApplicationIT`: `@MockitoBean
CurrentUserService` con `isPrismaAdmin()/isGlobalRole()=true`, `@MockitoBean
KeycloakAdminClient`, `@Transactional`). Corre contra el catálogo **sembrado** (V1..V19), sin
`importCatalog`. Único recurso: `src/test/resources/mcu50/expected_basico.json`. Flujo:

1. `controlIdByCode` = `controlRepo.findByVersionOrdered("5.0")`; `subcatCodeById`.
2. `orgRepo.save(...)` + `evaluationService.create(new CreateEvaluationDto("…", orgId, "5.0",
   BASICO_PROFILE_ID))` con `BASICO_PROFILE_ID = e1000001-…-001` (perfil sembrado).
3. Por cada clave de `answers`: `saveResponse(evalId, new SaveResponseDto(id, true, null))`.
4. `calculateMaturity(evalId)` → `getResults(evalId)`.
5. **Aserciones:**
   - Diferencias por subcategoría contra `expected.subcategory` = **∅**
     (`KNOWN_DIFFS` vacío). Cualquier diferencia → **falla** con el detalle.
   - `expected.subcategory` tiene 103 entradas.
   - `|generalCalculado − 1.165| ≤ 0.02`.
   - Imprime "Madurez por función (PRISMA vs planilla)" y las diferencias.

### 5.9 `PrismaApplicationIT.java`

Con `catalog_control_subcategories` poblado para `5.0` (V19), `effectiveSubcategories()`
devuelve el mapeo curado también para evaluaciones **sin perfil**:
- `TOTAL_CONTROLS` **670 → 671**; actualizar el comentario.
- `fullEvaluationLifecyclePersistsMaturityResults` y `calculateMaturityRecomputesOverPreviousResults`:
  elegir la subcategoría y sus controles con `control.effectiveSubcategories()` (no
  `requirement.getSubcategories()`), que es el criterio real de `calculateMaturity`.
- `findByVersionOrderedReturnsSeedControlsInOrder`: sin cambio (`OR.5-10` entra tras
  `OR.5-9`, no en los extremos).

### 5.8 Aceptación pkg3

- `mvn -q test -Dtest=CatalogServiceTest,EvaluationServiceTest,EntityTest,PrismaApplicationIT`
  verde.
- `make mcu50-validate` → `MaturityValidationIT` verde (0 diferencias por subcategoría).
- Regresión: evaluación sobre `5.0` sigue dando los mismos `currentLevel` por
  subcategoría al recalcular (sólo cambia `globalMaturity`).

---

## 6. Archivos compartidos — coordinación

| Archivo | pkg1 agrega | pkg3 agrega | pkg2 agrega | Cómo evitar conflicto |
|---|---|---|---|---|
| `CatalogService.java` | `listControlsFlat()` + helper de orden (métodos nuevos tras `listVersions`) + campo `controlRepo` en ctor | refactor de `importCatalog` (helper `upsertRequirement`, +`control.getSubcategories().add`) | — | **Un solo cambio de constructor** (agregar `CatalogControlRepository controlRepo` una vez). Regiones distintas del archivo. Quien mergea segundo hace rebase trivial. |
| `CatalogController.java` | un `@GetMapping("/{version}/controls")` | — | — | sin conflicto |
| `Dto.java` | `CatalogControlFlatDto` + `@Size` en `CreateCommunityProfileDto` | — | — | sin conflicto |
| `CatalogServiceTest.java` | tests de `listControlsFlat` + `@Mock controlRepo` + arg en ctor | — | — | pkg1 lo deja listo; pkg3 no lo toca |
| `types/index.ts` | `interface CatalogControlFlat` | — | `code?` en `Maturity{Function,Category,Subcategory}` | interfaces distintas |
| `Makefile` | — | targets `mcu50-*` + `.PHONY` | targets `mvp-*` + `.PHONY` | secciones separadas; ambos agregan a `.PHONY` (merge de 3 vías trivial) |
| `resources.ts` | `catalogService.getControls` | — | — | sin conflicto |

Regla general: **cada paquete agrega, no reescribe** las zonas del otro. El
`constructor` de `CatalogService` es el único punto que ambos tocan → pkg1 lo hace
primero (lo necesita para el endpoint) y pkg3 rebasea.

---

## 7. Verificación end-to-end conjunta (tras integrar los 3)

1. `mvn -q test` (backend, suite completa) verde — incluye `MaturityValidationIT` y
   `PrismaApplicationIT` (requiere pkg0 + Docker).
2. `cd apps/frontend && npm run type-check && npm run lint && npm run test:unit` verde.
3. Deploy limpio (`docker compose … up -d --build`) → Flyway corre V1..V19.
   `GET /api/catalog/5.0/controls` → **671**; `GET /api/catalog/5.0` → 6 / 21 / 103;
   perfiles `5.0` = Básico 165 / Estándar 234 / Avanzado 309.
4. En `https://localhost`: crear una evaluación sobre `5.0` + perfil `Básico`, marcar los
   165 requeridos como "cumple", calcular madurez.
   - Pestaña **"Tablas y promedios"**: general **1.165**, funciones = hoja "Resumen"
     de `Planilla MCU 5.0 Básico.xlsx`, 103 subcategorías idénticas.
   - El selector de perfiles: dominio entero en 1 clic, "seleccionar todo" ~1 s.
5. `make mcu50-validate` → verde.

---

## 8. Anexo — fuentes y datos

| Fuente | Ubicación | Para |
|---|---|---|
| `docs/mcu-5.0/Planilla MCU 5.0 {Básico,Estándar,Avanzado}.xlsx` (formato 2025) | repo | fuente de `scripts/mcu50/build.py`: `Cumplimiento!D` = requeridos del perfil; `Madurez Subcategoría` H..K = mapeo control↔subcat↔nivel + fórmula `L`; `Resumen` = madurez por función/general |
| Catálogo `5.0` en la BD (`prisma.catalog_*`) | seed Flyway V9/V10/V15/V18/**V19** | estructura del marco + mapeo curado + perfiles |
| `Graficos.png` | `docs/mcu-5.0/` | referencia visual (versión vieja; los números autoritativos son la hoja "Resumen" de las planillas nuevas) |

**Estado:** PRISMA reproduce las planillas nuevas en **103/103** subcategorías para los 3
perfiles (Básico 1.165, Estándar 1.689, Avanzado 2.029). Los 3 diffs que se toleraban
antes (`DE.AE-03`, `GV.OC-04`, `ID.AM-03`) eran artefactos de `vFinal_3` y desaparecen
con la fuente nueva + el modelo alineado (§1.7).
