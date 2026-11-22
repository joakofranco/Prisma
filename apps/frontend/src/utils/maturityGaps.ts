import type { ImprovementPlan, MaturityResult } from '@/types';

export function gapRows(results: MaturityResult[]): MaturityResult[] {
  return results.filter((r) => r.nextLevel !== null && (r.missingControls ?? []).length > 0);
}

export interface GapCategoryGroup {
  id: string;
  name: string;
  rows: MaturityResult[];
  controlCount: number;
}

export interface GapFunctionGroup {
  id: string;
  name: string;
  categories: GapCategoryGroup[];
  subcategoryCount: number;
  controlCount: number;
}

export function gapTree(rows: MaturityResult[]): GapFunctionGroup[] {
  const functions = new Map<string, GapFunctionGroup>();
  const categories = new Map<string, GapCategoryGroup>();
  for (const row of rows) {
    let fn = functions.get(row.functionId);
    if (!fn) {
      fn = {
        id: row.functionId,
        name: row.functionName,
        categories: [],
        subcategoryCount: 0,
        controlCount: 0,
      };
      functions.set(row.functionId, fn);
    }
    const key = `${row.functionId}/${row.categoryId}`;
    let category = categories.get(key);
    if (!category) {
      category = { id: key, name: row.categoryName, rows: [], controlCount: 0 };
      categories.set(key, category);
      fn.categories.push(category);
    }
    const controls = (row.missingControls ?? []).length;
    category.rows.push(row);
    category.controlCount += controls;
    fn.subcategoryCount += 1;
    fn.controlCount += controls;
  }
  return [...functions.values()];
}

export function withoutGapCount(results: MaturityResult[]): number {
  return results.length - gapRows(results).length;
}

export function levelPriority(level: number): ImprovementPlan['priority'] {
  if (level <= 1) return 'HIGH';
  return level === 2 ? 'MEDIUM' : 'LOW';
}

export function missingLabel(count: number, nextLevel: number): string {
  const noun = count === 1 ? 'control' : 'controles';
  const verb = count === 1 ? 'Falta' : 'Faltan';
  return `${verb} ${count} ${noun} para llegar al nivel ${nextLevel}`;
}

export function statusLabel(status: 'NON_COMPLIANT' | 'UNANSWERED'): string {
  return status === 'NON_COMPLIANT' ? 'No cumple' : 'Sin respuesta';
}

export function suggestedAction(subcategoryName: string, level: number, description: string) {
  return `Implementar (${subcategoryName}, nivel ${level}): ${description}`;
}
