import type { MaturityResult } from '@/types';

/** Valor del selector que no filtra nada. */
export const ALL_FUNCTIONS = '';

/** Funciones presentes en los resultados, en el orden en que aparecen (el del catálogo). */
export function functionOptions(results: MaturityResult[]): { value: string; label: string }[] {
  const seen = new Map<string, string>();
  for (const r of results) {
    if (!seen.has(r.functionId)) seen.set(r.functionId, r.functionName);
  }
  return [
    { value: ALL_FUNCTIONS, label: 'Todas las funciones' },
    ...Array.from(seen, ([value, label]) => ({ value, label })),
  ];
}

/** Deja solo las subcategorías de la función elegida; sin función elegida devuelve todo. */
export function filterByFunction(results: MaturityResult[], functionId: string): MaturityResult[] {
  if (!functionId) return results;
  return results.filter((r) => r.functionId === functionId);
}
