import { describe, it, expect } from 'vitest';
import { ref } from 'vue';
import { useMaturitySummary } from '../../src/composables/useMaturitySummary';
import type { MaturityResult, MaturityFunction, MaturityLevel } from '../../src/types';

function sub(
  fn: [string, string],
  cat: [string, string],
  subId: string,
  current: number,
): MaturityResult {
  return {
    functionId: fn[0],
    functionName: fn[1],
    categoryId: cat[0],
    categoryName: cat[1],
    subcategoryId: subId,
    subcategoryName: subId,
    currentLevel: current,
    targetLevel: 4 as MaturityLevel,
    gap: Math.max(0, 4 - current),
  };
}

describe('useMaturitySummary', () => {
  // RC con 2 categorías: RC.CO (1 subcat = 1.00) y RC.RP (3 subcats: 3, 0, 1.5-imposible... usamos enteros)
  // Objetivo del test: función = media PLANA de subcategorías, NO media de las medias de categoría.
  const results = ref<MaturityResult[]>([
    sub(['f-rc', 'Recuperar'], ['c-co', 'Comunicación'], 'RC.CO-01', 1),
    sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-01', 3),
    sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-02', 0),
    sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-03', 2),
    sub(['f-gv', 'Gobernar'], ['c-oc', 'Contexto'], 'GV.OC-01', 0),
    sub(['f-gv', 'Gobernar'], ['c-oc', 'Contexto'], 'GV.OC-02', 1),
  ]);

  const tree = ref<MaturityFunction[]>([
    {
      id: 'f-gv',
      code: 'GV',
      name: 'Gobernar',
      description: '',
      categories: [
        { id: 'c-oc', code: 'GV.OC', name: 'Contexto', description: '', subcategories: [] },
      ],
    },
    {
      id: 'f-rc',
      code: 'RC',
      name: 'Recuperar',
      description: '',
      categories: [
        { id: 'c-co', code: 'RC.CO', name: 'Comunicación', description: '', subcategories: [] },
        { id: 'c-rp', code: 'RC.RP', name: 'Ejecución', description: '', subcategories: [] },
      ],
    },
  ]);

  it('categoría = media de sus subcategorías', () => {
    const { byCategory } = useMaturitySummary(results, tree);
    const co = byCategory.value.find((c) => c.code === 'RC.CO')!;
    const rp = byCategory.value.find((c) => c.code === 'RC.RP')!;
    expect(co.level).toBeCloseTo(1); // [1]
    expect(rp.level).toBeCloseTo((3 + 0 + 2) / 3); // 1.666...
  });

  it('función = media PLANA de TODAS sus subcategorías, no media de las medias', () => {
    const { byFunction } = useMaturitySummary(results, tree);
    const rc = byFunction.value.find((f) => f.code === 'RC')!;
    // media plana de [1,3,0,2] = 1.5  (media de medias sería (1 + 1.666)/2 = 1.333)
    expect(rc.level).toBeCloseTo(1.5);
    expect(rc.level).not.toBeCloseTo((1 + (3 + 0 + 2) / 3) / 2);
  });

  it('general = media plana de todas las subcategorías', () => {
    const { general } = useMaturitySummary(results, tree);
    expect(general.value).toBeCloseTo((1 + 3 + 0 + 2 + 0 + 1) / 6); // 1.1666...
  });

  it('respeta el orden del árbol del catálogo (GV antes que RC)', () => {
    const { byFunction } = useMaturitySummary(results, tree);
    expect(byFunction.value.map((f) => f.code)).toEqual(['GV', 'RC']);
  });

  it('sin árbol: cae al orden de aparición y sin siglas', () => {
    const { byFunction } = useMaturitySummary(results, ref([]));
    expect(byFunction.value.map((f) => f.name)).toEqual(['Recuperar', 'Gobernar']);
    expect(byFunction.value[0].code).toBe('');
  });

  it('sin resultados: general NaN, tablas vacías', () => {
    const { general, byFunction } = useMaturitySummary(ref([]), tree);
    expect(Number.isNaN(general.value)).toBe(true);
    expect(byFunction.value).toEqual([]);
  });

  it('denominador = TODAS las subcategorías del árbol; las no cubiertas cuentan 0', () => {
    // RC.RP tiene 4 subcategorías en el árbol, pero results sólo trae 3 (RC.RP-01/02/03).
    const treeWithSubs = ref<MaturityFunction[]>([
      {
        id: 'f-rc',
        code: 'RC',
        name: 'Recuperar',
        description: '',
        categories: [
          {
            id: 'c-co',
            code: 'RC.CO',
            name: 'Comunicación',
            description: '',
            subcategories: [
              { id: 's1', code: 'RC.CO-01', name: '', description: '', requirements: [] },
            ],
          },
          {
            id: 'c-rp',
            code: 'RC.RP',
            name: 'Ejecución',
            description: '',
            subcategories: [1, 2, 3, 4].map((n) => ({
              id: `rp${n}`,
              code: `RC.RP-0${n}`,
              name: '',
              description: '',
              requirements: [],
            })),
          },
        ],
      },
    ]);
    const rcResults = ref<MaturityResult[]>([
      sub(['f-rc', 'Recuperar'], ['c-co', 'Comunicación'], 'RC.CO-01', 1),
      sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-01', 3),
      sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-02', 0),
      sub(['f-rc', 'Recuperar'], ['c-rp', 'Ejecución'], 'RC.RP-03', 2),
    ]);
    const { general, byFunction, byCategory } = useMaturitySummary(rcResults, treeWithSubs);
    const rp = byCategory.value.find((c) => c.code === 'RC.RP')!;
    expect(rp.count).toBe(4);
    expect(rp.level).toBeCloseTo((3 + 0 + 2 + 0) / 4); // 1.25, la 4a subcategoría cuenta 0
    expect(byFunction.value[0].level).toBeCloseTo((1 + 3 + 0 + 2 + 0) / 5); // 1.2 sobre 5 subcats
    expect(general.value).toBeCloseTo((1 + 3 + 0 + 2) / 5); // 1.2, denominador 5
  });
});
