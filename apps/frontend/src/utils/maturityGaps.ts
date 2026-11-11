import type { ImprovementPlan, MaturityResult } from '@/types';

export function gapRows(results: MaturityResult[]): MaturityResult[] {
  return results.filter((r) => r.nextLevel !== null && (r.missingControls ?? []).length > 0);
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
