import { describe, it, expect } from 'vitest';
import {
  defaultPair,
  deltaClass,
  filterSubcategories,
  formatDelta,
  formatLevel,
  groupByFunction,
  targetLabel,
} from '@/utils/comparison';
import type { SubcategoryDelta } from '@/types';

const row = (
  code: string,
  functionId: string,
  levelA: number | null,
  levelB: number | null,
  targetLevel: number | null = 3,
): SubcategoryDelta => ({
  subcategoryId: code,
  code,
  name: `Nombre ${code}`,
  functionId,
  functionName: functionId === 'gv' ? 'Gobernar' : 'Responder',
  categoryName: 'Cat',
  levelA,
  levelB,
  delta: levelA !== null && levelB !== null ? levelB - levelA : null,
  targetLevel,
});

const rows = [
  row('GV.OC-01', 'gv', 1, 3),
  row('GV.PO-01', 'gv', null, 1),
  row('GV.RM-02', 'gv', 2, 2),
  row('RS.MA-01', 'rs', 2, 1),
];

describe('comparativo (PRI-320)', () => {
  it('por defecto compara la segunda más reciente (A) con la más reciente (B)', () => {
    const c = (id: string) => ({
      id,
      name: id,
      profileName: 'E',
      approvedAt: null,
      globalMaturity: 1,
    });

    expect(defaultPair([c('2026'), c('2025'), c('2024')])).toEqual({ a: '2025', b: '2026' });
    expect(defaultPair([c('2026')])).toEqual({ a: '', b: '2026' });
  });

  it('filtra por cambio y por función', () => {
    expect(filterSubcategories(rows, 'all')).toHaveLength(4);
    expect(filterSubcategories(rows, 'changed').map((r) => r.code)).toEqual([
      'GV.OC-01',
      'GV.PO-01',
      'RS.MA-01',
    ]);
    expect(filterSubcategories(rows, 'up').map((r) => r.code)).toEqual(['GV.OC-01']);
    expect(filterSubcategories(rows, 'down').map((r) => r.code)).toEqual(['RS.MA-01']);
    expect(filterSubcategories(rows, 'all', 'rs').map((r) => r.code)).toEqual(['RS.MA-01']);
  });

  it('agrupa por función en el orden recibido', () => {
    const groups = groupByFunction(rows);

    expect(groups.map((g) => [g.functionName, g.rows.length])).toEqual([
      ['Gobernar', 3],
      ['Responder', 1],
    ]);
  });

  it('formatea niveles y cambios en español', () => {
    expect(formatLevel(1.5)).toBe('1,50');
    expect(formatLevel(2, 0)).toBe('2');
    expect(formatLevel(null)).toBe('—');
    expect(formatDelta(0.53)).toBe('▲ +0,53');
    expect(formatDelta(-1, 0)).toBe('▼ −1');
    expect(formatDelta(0)).toBe('= sin cambios');
    expect(formatDelta(null)).toBe('—');
  });

  it('colorea según suba o baje', () => {
    expect(deltaClass(1)).toContain('emerald');
    expect(deltaClass(-1)).toContain('red');
    expect(deltaClass(0)).toContain('slate');
    expect(deltaClass(null)).toContain('slate');
  });

  it('dice cuánto falta para el objetivo', () => {
    expect(targetLabel(rows[0]!)).toBe('3 · alcanzado');
    expect(targetLabel(rows[3]!)).toBe('3 · falta 2');
    expect(targetLabel(row('X', 'gv', 1, null))).toBe('3 · falta 2');
    expect(targetLabel(row('X', 'gv', 1, 1, null))).toBe('—');
  });
});
