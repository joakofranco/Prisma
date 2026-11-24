// RF-VIZ-06 (PRI-320): utilidades de la pantalla «Comparar evaluaciones». Funciones puras.
import type { ComparisonCandidate, SubcategoryDelta } from '@/types';

export type DeltaFilter = 'all' | 'changed' | 'up' | 'down';

/** A = la segunda más reciente, B = la más reciente (los candidatos llegan del más nuevo al más viejo). */
export function defaultPair(candidates: ComparisonCandidate[]): { a: string; b: string } {
  return { a: candidates[1]?.id ?? '', b: candidates[0]?.id ?? '' };
}

/** Subcategorías según el filtro y, si se eligió, la función. «Cambios» incluye «solo en A/B». */
export function filterSubcategories(
  rows: SubcategoryDelta[],
  filter: DeltaFilter,
  functionId: string | null = null,
): SubcategoryDelta[] {
  return rows.filter((r) => {
    if (functionId && r.functionId !== functionId) return false;
    if (filter === 'changed') return r.delta === null || r.delta !== 0;
    if (filter === 'up') return r.delta !== null && r.delta > 0;
    if (filter === 'down') return r.delta !== null && r.delta < 0;
    return true;
  });
}

/** Agrupa por función respetando el orden en que llegan (el del catálogo). */
export function groupByFunction(
  rows: SubcategoryDelta[],
): { functionId: string; functionName: string; rows: SubcategoryDelta[] }[] {
  const groups: { functionId: string; functionName: string; rows: SubcategoryDelta[] }[] = [];
  for (const r of rows) {
    let g = groups.find((x) => x.functionId === r.functionId);
    if (!g) {
      g = { functionId: r.functionId, functionName: r.functionName, rows: [] };
      groups.push(g);
    }
    g.rows.push(r);
  }
  return groups;
}

const es = (v: number, digits: number) =>
  v.toLocaleString('es-UY', { minimumFractionDigits: digits, maximumFractionDigits: digits });

/** «1,48» (promedios) o «2» (niveles). */
export function formatLevel(v: number | null, decimals = 2): string {
  return v === null ? '—' : es(v, decimals);
}

/** «▲ +0,53», «▼ −1», «= sin cambios»; null → «—». */
export function formatDelta(delta: number | null, decimals = 2): string {
  if (delta === null) return '—';
  if (delta === 0) return '= sin cambios';
  const n = es(Math.abs(delta), decimals);
  return delta > 0 ? `▲ +${n}` : `▼ −${n}`;
}

/** Color del cambio: verde si sube, rojo si baja, gris si no cambia. */
export function deltaClass(delta: number | null): string {
  if (delta === null || delta === 0) return 'text-slate-600 dark:text-slate-300';
  return delta > 0 ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-700 dark:text-red-400';
}

/** «3 · alcanzado» o «3 · falta 1» según el nivel más reciente disponible. */
export function targetLabel(row: SubcategoryDelta): string {
  if (row.targetLevel === null) return '—';
  const reached = row.levelB ?? row.levelA ?? 0;
  const missing = row.targetLevel - reached;
  return `${row.targetLevel} · ${missing <= 0 ? 'alcanzado' : `falta ${missing}`}`;
}
