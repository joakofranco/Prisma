import { computed, type Ref } from 'vue';
import type { MaturityResult, MaturityFunction } from '@/types';

/**
 * Promedios de madurez por función y por categoría, calculados como **media aritmética plana**
 * de los `currentLevel` (0-4) de las subcategorías que cuelgan de cada nivel:
 *
 *  - categoría  = media de sus subcategorías
 *  - función    = media de TODAS sus subcategorías (no la media de las medias de sus categorías)
 *  - general    = media de todas las subcategorías de la versión del catálogo
 *
 * El denominador es **todas las subcategorías del catálogo** (no sólo las presentes en
 * `results`): una subcategoría que el perfil no cubre cuenta como nivel 0 -- igual que la
 * planilla oficial de Agesic. Si el árbol del catálogo todavía no cargó, se cae al conjunto
 * de subcategorías presentes en `results`.
 *
 * El `currentLevel` por subcategoría lo calcula el backend (`EvaluationService.calculateMaturity`).
 * El orden y las siglas salen del árbol (`catalogFunctions`, ya ordenado por `sortOrder`).
 */
export interface FunctionSummary {
  id: string;
  code: string;
  name: string;
  level: number;
  target: number;
  count: number;
}

export interface CategorySummary {
  id: string;
  code: string;
  name: string;
  functionId: string;
  functionCode: string;
  functionName: string;
  level: number;
  target: number;
  count: number;
}

interface Agg {
  currentSum: number;
  targetSum: number;
  present: number;
  name: string;
}

function mean(sum: number, count: number): number {
  return count > 0 ? sum / count : Number.NaN;
}

interface OrderedCategory {
  id: string;
  code: string;
  subcatCount: number;
}
interface OrderedFunction {
  id: string;
  code: string;
  name: string;
  categories: OrderedCategory[];
  subcatCount: number;
}

export function useMaturitySummary(
  results: Ref<MaturityResult[]>,
  catalogFunctions: Ref<MaturityFunction[]>,
) {
  const byCategoryAgg = computed(() => {
    const map = new Map<string, Agg>();
    for (const r of results.value) {
      const a = map.get(r.categoryId) ?? {
        currentSum: 0,
        targetSum: 0,
        present: 0,
        name: r.categoryName,
      };
      a.currentSum += r.currentLevel;
      a.targetSum += r.targetLevel;
      a.present += 1;
      map.set(r.categoryId, a);
    }
    return map;
  });

  const byFunctionAgg = computed(() => {
    const map = new Map<string, Agg>();
    for (const r of results.value) {
      const a = map.get(r.functionId) ?? {
        currentSum: 0,
        targetSum: 0,
        present: 0,
        name: r.functionName,
      };
      a.currentSum += r.currentLevel;
      a.targetSum += r.targetLevel;
      a.present += 1;
      map.set(r.functionId, a);
    }
    return map;
  });

  /** Orden, siglas y CANTIDAD de subcategorías por nodo — del árbol del catálogo. */
  const orderedFunctions = computed<OrderedFunction[]>(() => {
    if (catalogFunctions.value.length > 0) {
      return catalogFunctions.value.map((f) => {
        const categories = (f.categories ?? []).map((c) => ({
          id: c.id,
          code: c.code ?? '',
          subcatCount: (c.subcategories ?? []).length,
        }));
        return {
          id: f.id,
          code: f.code ?? '',
          name: f.name,
          categories,
          subcatCount: categories.reduce((n, c) => n + c.subcatCount, 0),
        };
      });
    }
    // Fallback sin árbol: reconstruir del propio `results` (denominador = subcats presentes).
    const fns: OrderedFunction[] = [];
    const seenC = new Set<string>();
    for (const r of results.value) {
      let fn = fns.find((x) => x.id === r.functionId);
      if (!fn) {
        fn = { id: r.functionId, code: '', name: r.functionName, categories: [], subcatCount: 0 };
        fns.push(fn);
      }
      fn.subcatCount += 1;
      const ckey = `${r.functionId}::${r.categoryId}`;
      if (!seenC.has(ckey)) {
        fn.categories.push({ id: r.categoryId, code: '', subcatCount: 0 });
        seenC.add(ckey);
      }
      const cat = fn.categories.find((c) => c.id === r.categoryId);
      if (cat) cat.subcatCount += 1;
    }
    return fns;
  });

  const byFunction = computed<FunctionSummary[]>(() =>
    orderedFunctions.value
      .map((f) => {
        const a = byFunctionAgg.value.get(f.id);
        if (!a) return null;
        return {
          id: f.id,
          code: f.code,
          name: f.name || a.name,
          level: mean(a.currentSum, f.subcatCount || a.present),
          target: mean(a.targetSum, a.present),
          count: f.subcatCount || a.present,
        };
      })
      .filter((x): x is FunctionSummary => x !== null),
  );

  const byCategory = computed<CategorySummary[]>(() => {
    const rows: CategorySummary[] = [];
    for (const f of orderedFunctions.value) {
      for (const c of f.categories) {
        const a = byCategoryAgg.value.get(c.id);
        if (!a) continue;
        rows.push({
          id: c.id,
          code: c.code,
          name: a.name,
          functionId: f.id,
          functionCode: f.code,
          functionName: f.name,
          level: mean(a.currentSum, c.subcatCount || a.present),
          target: mean(a.targetSum, a.present),
          count: c.subcatCount || a.present,
        });
      }
    }
    return rows;
  });

  /** Denominador general: todas las subcategorías del árbol; si no cargó, las presentes. */
  const totalSubcategories = computed(() => {
    const fromTree = orderedFunctions.value.reduce((n, f) => n + f.subcatCount, 0);
    return fromTree > 0 ? fromTree : results.value.length;
  });

  const general = computed(() => {
    if (results.value.length === 0) return Number.NaN;
    const sum = results.value.reduce((s, r) => s + r.currentLevel, 0);
    return mean(sum, totalSubcategories.value);
  });

  /** Un punto por categoría para el radar (label = sigla si existe, si no el nombre). */
  const categoryRadarItems = computed(() =>
    byCategory.value.map((c) => ({
      name: c.code || c.name,
      current: Math.round(c.level * 100) / 100,
      target: Math.round(c.target * 100) / 100,
    })),
  );

  return { general, byFunction, byCategory, categoryRadarItems };
}
