# `scripts/mcu50/` — seed de MCU 5.0 alineado a Agesic 2025

El catálogo MCU 5.0, su mapeo curado control↔subcategoría y los 3 perfiles comunitarios
(`Básico`/`Estándar`/`Avanzado`) se **siembran vía migraciones Flyway**. No hay import a
demanda ni una versión de catálogo separada.

## `build.py`

Único generador. Lee las 3 planillas oficiales de Agesic (formato 2025) desde
`docs/mcu-5.0/Planilla MCU 5.0 {Básico,Estándar,Avanzado}.xlsx` y emite:

| Salida | Qué es |
|---|---|
| `apps/backend-core/src/main/resources/db/migration/V19__mcu50_align_agesic_2025.sql` | Migración de seed: control `OR.5-10`, los 1013 pares `catalog_control_subcategories` y la membresía de los 3 perfiles (165 / 234 / 309). |
| `scripts/mcu50/expected_basico.json` | Baseline de `MaturityValidationIT` (escenario "los 165 requeridos cumplen" → nivel por subcategoría según la fórmula `L` de la planilla, general 1.165). |

```
make mcu50-build      # corre build.py + copia expected_basico.json a src/test/resources/mcu50/
make mcu50-validate   # mvn test -Dtest=MaturityValidationIT
```

## Cuándo regenerar

**`V19` es inmutable una vez mergeada** (Flyway valida el checksum). Regenerar sólo si
Agesic publica una revisión de las planillas **antes** de mergear; si ya está en `main`,
va una migración nueva (`V20…`), no un cambio a `V19`.

## Formato de las planillas

- pestaña **`Cumplimiento`** — fila 8 encabezado; col `B` = `"COD: texto"`, col `D` = `"Si"`
  si el control es requerido para ese perfil.
- pestaña **`Madurez Subcategoría`** — fila 8 encabezado; col `D` = ID subcategoría; cols
  `H/I/J/K` = controles ubicados en Nivel 1/2/3/4. El placement es idéntico en las 3
  planillas.
