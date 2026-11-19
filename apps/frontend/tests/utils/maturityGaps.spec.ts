import { describe, it, expect } from 'vitest';
import {
  gapRows,
  gapTree,
  levelPriority,
  missingLabel,
  statusLabel,
  suggestedAction,
  withoutGapCount,
} from '@/utils/maturityGaps';
import type { MaturityResult } from '@/types';

const result = (nextLevel: number | null, missing: number): MaturityResult =>
  ({
    subcategoryId: `s-${nextLevel}-${missing}`,
    nextLevel,
    missingControls: Array.from({ length: missing }, (_, i) => ({
      id: `c${i}`,
      code: `C${i}`,
      description: 'd',
      targetLevel: 2,
      status: 'UNANSWERED',
    })),
  }) as unknown as MaturityResult;

describe('maturityGaps', () => {
  it('gapRows deja solo las subcategorías con próximo nivel y controles faltantes', () => {
    const rows = gapRows([result(2, 1), result(null, 0), result(3, 0)]);

    expect(rows).toHaveLength(1);
    expect(rows[0].nextLevel).toBe(2);
  });

  it('withoutGapCount cuenta las que no tienen brecha', () => {
    expect(withoutGapCount([result(2, 1), result(null, 0), result(null, 0)])).toBe(2);
  });

  it('gapRows tolera resultados sin missingControls', () => {
    const legacy = { subcategoryId: 'x', nextLevel: 2 } as unknown as MaturityResult;

    expect(gapRows([legacy])).toEqual([]);
  });

  it('levelPriority replica la regla del backend', () => {
    expect(levelPriority(0)).toBe('HIGH');
    expect(levelPriority(1)).toBe('HIGH');
    expect(levelPriority(2)).toBe('MEDIUM');
    expect(levelPriority(3)).toBe('LOW');
    expect(levelPriority(4)).toBe('LOW');
  });

  it('missingLabel concuerda singular y plural', () => {
    expect(missingLabel(1, 2)).toBe('Falta 1 control para llegar al nivel 2');
    expect(missingLabel(3, 4)).toBe('Faltan 3 controles para llegar al nivel 4');
  });

  it('statusLabel traduce el estado', () => {
    expect(statusLabel('NON_COMPLIANT')).toBe('No cumple');
    expect(statusLabel('UNANSWERED')).toBe('Sin respuesta');
  });

  it('suggestedAction arma el mismo texto que el backend', () => {
    expect(suggestedAction('PR.AA-05', 2, 'Hacer algo')).toBe(
      'Implementar (PR.AA-05, nivel 2): Hacer algo',
    );
  });
  it('gapTree agrupa por función y categoría conservando el orden y contando los controles', () => {
    const row = (fn: string, cat: string, sub: string, missing: number) =>
      ({
        functionId: fn,
        functionName: `F-${fn}`,
        categoryId: cat,
        categoryName: `C-${cat}`,
        subcategoryId: sub,
        nextLevel: 2,
        missingControls: Array.from({ length: missing }, (_, i) => ({ id: `${sub}-${i}` })),
      }) as unknown as MaturityResult;

    const tree = gapTree([
      row('f1', 'c1', 's1', 2),
      row('f2', 'c2', 's2', 1),
      row('f1', 'c1', 's3', 3),
      row('f1', 'c4', 's4', 1),
    ]);

    expect(tree.map((f) => f.id)).toEqual(['f1', 'f2']);
    expect(tree[0].subcategoryCount).toBe(3);
    expect(tree[0].controlCount).toBe(6);
    expect(tree[0].categories.map((c) => c.name)).toEqual(['C-c1', 'C-c4']);
    expect(tree[0].categories[0].rows.map((r) => r.subcategoryId)).toEqual(['s1', 's3']);
    expect(tree[0].categories[0].controlCount).toBe(5);
    expect(tree[1].categories).toHaveLength(1);
  });
});
