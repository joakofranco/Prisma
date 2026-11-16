# Plan de cambios — Madurez alineada a la planilla de Agesic + rediseño de perfiles


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
