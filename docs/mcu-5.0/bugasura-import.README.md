# `bugasura-import.csv` — backlog de PRISMA para Bugasura

Archivo listo para importar en Bugasura (mismas columnas que `PlanillaBugasura.csv`, que
queda como plantilla de referencia). **103 filas.**

Generado por `scripts/bugasura/build_import.py` (`make bugasura-build`).

## Qué contiene

| Tipo | Cant. | Origen | Estado |
|---|---|---|---|
| **Epic** | 15 | las 15 áreas de `docs/HistoriasDeUsuario.md` | `Completed` |
| **Story** | 68 | cada `HU-XXX-NN` de `docs/HistoriasDeUsuario.md` (sistema construido) | `Completed` |
| **Task** | 16 | el refactor pendiente de `docs/PLAN-CAMBIOS-EQUIPO.md` (pkg0–pkg3) + 1 mejora de seguridad | `New` |
| **Bug** | 4 | deuda técnica documentada en `docs/{Testing,API,RUNBOOK,SECURITY}.md` | `New` |

## Columnas

- **Title** — Epic: nombre del área. Story: `HU-XXX-NN — <título>`. Task/Bug: descripción corta.
- **Details** — Story: criterios de aceptación (` · `) + `Implementado en: <archivos/endpoints>`.
  Task/Bug: qué hay que hacer / cuál es el problema. Todo en **una sola línea**.
- **Overview** — Story: el "Como … quiero … para …". Epic/Task/Bug: el propósito / la necesidad.
- **Priority** — `P1` núcleo (auth, cálculo de madurez, transiciones de estado, aislamiento
  multi-tenant, estructura del catálogo) · `P2` features de negocio · `P3` CRUD estándar,
  dashboard, UX, config, observabilidad · `P4` pulido.
- **Type** — `Epic` / `Story` / `Task` / `Bug`.
- **Status** — `Completed` (construido) / `New` (pendiente).
- **Assignees**, **Estimation** — **vacías** a propósito; las completa el equipo en Bugasura.
- **Tags** — una etiqueta por fila: el área (`Autenticación`, `Evaluaciones`, `Catálogo`, …)
  o, para pendientes/bugs, `Build` / `Testing` / `Documentación` / `Seguridad` / `Frontend` /
  `Backend`.

> Bugasura no linkea Epic↔Story por el CSV; la relación queda expresada por el `Tags` (misma
> etiqueta) y el orden (primero los 15 Epics, después las Stories agrupadas por área, después
> Tasks y Bugs). Si querés la jerarquía real, hay que asociar los items en Bugasura después
> de importar.

## Regenerar

```
make bugasura-build          # python scripts/bugasura/build_import.py
```

El script re-parsea `docs/HistoriasDeUsuario.md` (Epics + Stories) y toma las listas
`PENDING` / `BUGS` hardcodeadas en el propio script (editarlas ahí cuando cambie el estado
del refactor o aparezca/se cierre deuda técnica). Valida los conteos (15 / 68) y que ninguna
celda tenga saltos de línea antes de escribir.

## Sugerencia de import

Probar primero con 2–3 filas (una de cada tipo) para confirmar que Bugasura acepta
`Assignees`/`Estimation` vacías y los valores de `Type`/`Status`/`Priority` de tu instancia,
y recién después cargar las 103.
