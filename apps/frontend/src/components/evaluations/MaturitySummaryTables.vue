<template>
  <div class="space-y-6">
    <!-- Madurez general -->
    <div
      class="flex flex-wrap items-center gap-4 rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900 px-5 py-4"
    >
      <div>
        <p class="text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
          Madurez general
        </p>
        <p
          class="text-3xl font-bold"
          :style="{ color: getMaturityColor(Math.round(general || 0)) }"
        >
          {{ formatDecimals(general) }}
        </p>
      </div>
      <p class="text-xs text-slate-500 dark:text-slate-400 max-w-md">
        Promedio de todas las subcategorías evaluadas. Los niveles por función y categoría son el
        promedio matemático de sus subcategorías, con 2 decimales.
      </p>
    </div>

    <div v-if="byFunction.length === 0" class="text-sm text-slate-500 dark:text-slate-400">
      No hay resultados para resumir.
    </div>

    <template v-else>
      <!-- Tabla por función -->
      <div class="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-700">
        <table class="min-w-full text-sm">
          <thead class="bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-300">
            <tr>
              <th class="px-4 py-2 text-left font-semibold">Función</th>
              <th class="px-4 py-2 text-left font-semibold w-24">Sigla</th>
              <th class="px-4 py-2 text-right font-semibold w-32">Nivel logrado</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-800">
            <tr v-for="f in byFunction" :key="f.id">
              <td class="px-4 py-2 text-slate-800 dark:text-slate-100">{{ f.name }}</td>
              <td class="px-4 py-2 text-slate-500 dark:text-slate-400">{{ f.code || '—' }}</td>
              <td
                class="px-4 py-2 text-right font-semibold tabular-nums"
                :style="{ color: getMaturityColor(Math.round(f.level)) }"
              >
                {{ formatDecimals(f.level) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Tabla por categoría, agrupada por función -->
      <div class="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-700">
        <table class="min-w-full text-sm">
          <thead class="bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-300">
            <tr>
              <th class="px-4 py-2 text-left font-semibold">Categoría</th>
              <th class="px-4 py-2 text-left font-semibold w-28">Sigla</th>
              <th class="px-4 py-2 text-right font-semibold w-32">Nivel logrado</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-800">
            <template v-for="grp in categoryGroups" :key="grp.functionId">
              <tr class="bg-slate-100/70 dark:bg-slate-800/70">
                <td
                  class="px-4 py-1.5 font-semibold text-slate-700 dark:text-slate-200"
                  colspan="2"
                >
                  {{ grp.functionName }}
                  <span class="text-slate-400 font-normal">({{ grp.functionCode || '—' }})</span>
                </td>
                <td
                  class="px-4 py-1.5 text-right font-semibold tabular-nums"
                  :style="{ color: getMaturityColor(Math.round(grp.level)) }"
                >
                  {{ formatDecimals(grp.level) }}
                </td>
              </tr>
              <tr v-for="c in grp.categories" :key="c.id">
                <td class="px-4 py-2 pl-8 text-slate-800 dark:text-slate-100">{{ c.name }}</td>
                <td class="px-4 py-2 text-slate-500 dark:text-slate-400">{{ c.code || '—' }}</td>
                <td
                  class="px-4 py-2 text-right font-semibold tabular-nums"
                  :style="{ color: getMaturityColor(Math.round(c.level)) }"
                >
                  {{ formatDecimals(c.level) }}
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <!-- Gráfico por categoría -->
      <div>
        <h4 class="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          Madurez por categoría
        </h4>
        <MaturityRadarChart :items="categoryRadarItems" :show-target="false" />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, toRef } from 'vue';
  import type { MaturityResult, MaturityFunction } from '@/types';
  import { useMaturitySummary, type CategorySummary } from '@/composables/useMaturitySummary';
  import { formatDecimals, getMaturityColor } from '@/utils/helpers';
  import MaturityRadarChart from '@/components/charts/MaturityRadarChart.vue';

  const props = defineProps<{
    results: MaturityResult[];
    catalogFunctions: MaturityFunction[];
  }>();

  const { general, byFunction, byCategory, categoryRadarItems } = useMaturitySummary(
    toRef(props, 'results'),
    toRef(props, 'catalogFunctions'),
  );

  const categoryGroups = computed(() => {
    const groups: {
      functionId: string;
      functionCode: string;
      functionName: string;
      level: number;
      categories: CategorySummary[];
    }[] = [];
    const fnLevel = new Map(byFunction.value.map((f) => [f.id, f.level]));
    for (const c of byCategory.value) {
      let g = groups.find((x) => x.functionId === c.functionId);
      if (!g) {
        g = {
          functionId: c.functionId,
          functionCode: c.functionCode,
          functionName: c.functionName,
          level: fnLevel.get(c.functionId) ?? Number.NaN,
          categories: [],
        };
        groups.push(g);
      }
      g.categories.push(c);
    }
    return groups;
  });
</script>
