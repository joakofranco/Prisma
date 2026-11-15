import { describe, it, expect } from 'vitest';
import { ALL_FUNCTIONS, filterByFunction, functionOptions } from '../../src/utils/resultFilters';
import type { MaturityResult } from '../../src/types';

const result = (functionId: string, functionName: string, sub: string): MaturityResult => ({
  functionId,
  functionName,
  categoryId: 'c',
  categoryName: 'C',
  subcategoryId: sub,
  subcategoryName: sub,
  currentLevel: 1,
  targetLevel: 2,
  gap: 1,
});

const results = [
  result('gv', 'Gobernar', 's1'),
  result('gv', 'Gobernar', 's2'),
  result('id', 'Identificar', 's3'),
];

describe('resultFilters (RF-VIZ-05)', () => {
  it('functionOptions ofrece «Todas» y cada función una sola vez, en orden', () => {
    expect(functionOptions(results)).toEqual([
      { value: ALL_FUNCTIONS, label: 'Todas las funciones' },
      { value: 'gv', label: 'Gobernar' },
      { value: 'id', label: 'Identificar' },
    ]);
  });

  it('sin resultados solo queda la opción «Todas»', () => {
    expect(functionOptions([])).toHaveLength(1);
  });

  it('filterByFunction deja solo las subcategorías de la función elegida', () => {
    expect(filterByFunction(results, 'gv').map((r) => r.subcategoryId)).toEqual(['s1', 's2']);
    expect(filterByFunction(results, 'id').map((r) => r.subcategoryId)).toEqual(['s3']);
  });

  it('sin función elegida devuelve todo y con una inexistente no devuelve nada', () => {
    expect(filterByFunction(results, ALL_FUNCTIONS)).toHaveLength(3);
    expect(filterByFunction(results, 'zz')).toEqual([]);
  });
});
