<template>
  <section
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5"
    aria-labelledby="maturity-gaps-title"
    data-test="gap-section"
  >
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 id="maturity-gaps-title" class="text-base font-semibold text-slate-900 dark:text-white">
          Brechas por subcategoría
        </h2>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
          Agrupadas por función, categoría y subcategoría: nivel actual, próximo nivel objetivo y
          los controles del perfil que faltan para alcanzarlo.
        </p>
      </div>
      <div class="flex flex-col items-end gap-1 shrink-0">
        <p class="text-xs text-slate-500 dark:text-slate-400" data-test="gap-summary">
          {{ rows.length }} con brecha · {{ noGap }} sin brecha
        </p>
        <button
          v-if="rows.length > 0"
          type="button"
          class="text-xs font-medium text-blue-700 hover:underline dark:text-blue-400"
          data-test="gap-toggle-all"
          @click="toggleAll"
        >
          {{ allExpanded ? 'Contraer todas' : 'Expandir todas' }}
        </button>
      </div>
    </div>

    <p
      v-if="results.length === 0"
      class="mt-4 text-sm text-slate-500 dark:text-slate-400"
      data-test="gap-empty"
    >
      Todavía no hay resultados de madurez: las brechas aparecen cuando se calcula la evaluación.
    </p>
    <p
      v-else-if="rows.length === 0"
      class="mt-4 text-sm text-emerald-700 dark:text-emerald-400"
      data-test="gap-none"
    >
      No hay controles del perfil pendientes para subir de nivel.
    </p>

    <div v-else class="mt-4 space-y-3" data-test="gap-list">
      <div
        v-for="fn in tree"
        :key="fn.id"
        class="rounded-lg border border-slate-200 dark:border-slate-700"
        data-test="gap-function"
      >
        <button
          type="button"
          class="w-full flex items-center gap-2 px-3 py-2.5 text-left bg-slate-50 dark:bg-slate-900 rounded-lg"
          :aria-expanded="isExpanded(fnKey(fn.id))"
          :aria-controls="`gap-panel-${fnKey(fn.id)}`"
          data-test="gap-function-toggle"
          @click="toggle(fnKey(fn.id))"
        >
          <ChevronDownIcon :class="chevron(fnKey(fn.id))" aria-hidden="true" />
          <span class="flex-1 text-sm font-semibold text-slate-900 dark:text-white">
            {{ fn.name }}
          </span>
          <span class="text-xs text-slate-500 dark:text-slate-400" data-test="gap-function-count">
            {{ countLabel(fn.subcategoryCount, 'subcategoría', 'subcategorías') }} ·
            {{ countLabel(fn.controlCount, 'control', 'controles') }}
          </span>
        </button>

        <div
          v-show="isExpanded(fnKey(fn.id))"
          :id="`gap-panel-${fnKey(fn.id)}`"
          class="px-3 pb-3 pt-1 space-y-2"
        >
          <div
            v-for="category in fn.categories"
            :key="category.id"
            class="pl-3 border-l-2 border-slate-200 dark:border-slate-700"
            data-test="gap-category"
          >
            <button
              type="button"
              class="w-full flex items-center gap-2 py-2 text-left"
              :aria-expanded="isExpanded(catKey(category.id))"
              :aria-controls="`gap-panel-${catKey(category.id)}`"
              data-test="gap-category-toggle"
              @click="toggle(catKey(category.id))"
            >
              <ChevronDownIcon :class="chevron(catKey(category.id))" aria-hidden="true" />
              <span class="flex-1 text-sm font-medium text-slate-800 dark:text-slate-100">
                {{ category.name }}
              </span>
              <span class="text-xs text-slate-500 dark:text-slate-400">
                {{ countLabel(category.rows.length, 'subcategoría', 'subcategorías') }} ·
                {{ countLabel(category.controlCount, 'control', 'controles') }}
              </span>
            </button>

            <div
              v-show="isExpanded(catKey(category.id))"
              :id="`gap-panel-${catKey(category.id)}`"
              class="divide-y divide-slate-100 dark:divide-slate-700"
            >
              <div
                v-for="row in category.rows"
                :key="row.subcategoryId"
                class="py-3"
                data-test="gap-row"
              >
                <button
                  type="button"
                  class="w-full flex items-center justify-between gap-3 mb-2 flex-wrap text-left"
                  :aria-expanded="isExpanded(subKey(row.subcategoryId))"
                  :aria-controls="`gap-controls-${row.subcategoryId}`"
                  data-test="gap-toggle"
                  @click="toggle(subKey(row.subcategoryId))"
                >
                  <span
                    class="flex items-center gap-2 text-sm font-medium text-slate-900 dark:text-white"
                  >
                    <ChevronDownIcon
                      :class="chevron(subKey(row.subcategoryId))"
                      aria-hidden="true"
                    />
                    {{ row.subcategoryName }}
                  </span>
                  <span class="text-xs shrink-0" data-test="gap-levels">
                    <b class="maturity-text" :style="getMaturityTextStyle(row.currentLevel)">
                      Nivel {{ row.currentLevel }}
                    </b>
                    <span class="text-slate-500 dark:text-slate-400"> → </span>
                    <b class="maturity-text" :style="getMaturityTextStyle(row.nextLevel ?? 0)">
                      Nivel {{ row.nextLevel }} · {{ levelName(row.nextLevel ?? 0) }}
                    </b>
                  </span>
                </button>

                <div
                  class="relative h-2 w-full rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden"
                  aria-hidden="true"
                >
                  <div
                    class="absolute h-2 rounded-full"
                    :style="{
                      width: `${(row.currentLevel / 4) * 100}%`,
                      backgroundColor: getMaturityColor(row.currentLevel),
                    }"
                  />
                  <div
                    class="absolute h-2 border-y border-r border-dashed border-blue-500 bg-blue-500/20"
                    :style="{
                      left: `${(row.currentLevel / 4) * 100}%`,
                      width: `${(((row.nextLevel ?? row.currentLevel) - row.currentLevel) / 4) * 100}%`,
                    }"
                  />
                </div>

                <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">
                  {{ missingLabel(row.missingControls.length, row.nextLevel ?? 0)
                  }}{{ isExpanded(subKey(row.subcategoryId)) ? ':' : '' }}
                </p>
                <ul
                  v-show="isExpanded(subKey(row.subcategoryId))"
                  :id="`gap-controls-${row.subcategoryId}`"
                  class="mt-1 divide-y divide-slate-100 dark:divide-slate-700"
                >
                  <li
                    v-for="control in row.missingControls"
                    :key="control.id"
                    class="flex items-start gap-3 py-2"
                    data-test="gap-control"
                  >
                    <span
                      :class="[
                        'mt-0.5 shrink-0 text-[10px] font-semibold px-1.5 py-0.5 rounded',
                        control.status === 'NON_COMPLIANT'
                          ? 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'
                          : 'bg-slate-200 text-slate-700 dark:bg-slate-700 dark:text-slate-300',
                      ]"
                    >
                      {{ statusLabel(control.status) }}
                    </span>
                    <div class="flex-1 min-w-0">
                      <p class="text-xs font-mono text-slate-500 dark:text-slate-400">
                        {{ control.code }} · Nivel {{ control.targetLevel }}
                      </p>
                      <p class="text-sm text-slate-800 dark:text-slate-100">
                        {{ control.description }}
                      </p>
                    </div>
                    <span
                      v-if="plannedControlIds.has(control.id)"
                      class="shrink-0 text-xs text-emerald-700 dark:text-emerald-400"
                      data-test="gap-planned"
                    >
                      Acción creada
                    </span>
                    <button
                      v-else
                      type="button"
                      class="shrink-0 text-xs px-2.5 py-1 rounded-lg border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700"
                      data-test="gap-create"
                      @click="emit('create-action', toDraft(row, control))"
                    >
                      + Crear acción
                    </button>
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import { ChevronDownIcon } from '@heroicons/vue/24/outline';
  import type { ImprovementPlan, MaturityResult, MissingControl } from '@/types';
  import { getMaturityColor, getMaturityTextStyle } from '@/utils/helpers';
  import { MATURITY_LEVELS } from '@/utils/constants';
  import {
    gapRows,
    gapTree,
    levelPriority,
    missingLabel,
    statusLabel,
    suggestedAction,
    withoutGapCount,
  } from '@/utils/maturityGaps';

  export interface GapActionDraft {
    controlId: string;
    action: string;
    priority: ImprovementPlan['priority'];
  }

  const props = defineProps<{
    results: MaturityResult[];
    plannedControlIds: Set<string>;
  }>();

  const emit = defineEmits<{ 'create-action': [draft: GapActionDraft] }>();

  const rows = computed(() => gapRows(props.results));
  const tree = computed(() => gapTree(rows.value));
  const noGap = computed(() => withoutGapCount(props.results));

  const expanded = ref<Set<string>>(new Set());

  const fnKey = (id: string) => `f:${id}`;
  const catKey = (id: string) => `c:${id}`;
  const subKey = (id: string) => `s:${id}`;

  const allKeys = computed(() => {
    const keys: string[] = [];
    for (const fn of tree.value) {
      keys.push(fnKey(fn.id));
      for (const category of fn.categories) {
        keys.push(catKey(category.id));
        for (const row of category.rows) keys.push(subKey(row.subcategoryId));
      }
    }
    return keys;
  });

  const allExpanded = computed(
    () => allKeys.value.length > 0 && allKeys.value.every((k) => expanded.value.has(k)),
  );

  function isExpanded(key: string) {
    return expanded.value.has(key);
  }

  function toggle(key: string) {
    const next = new Set(expanded.value);
    if (!next.delete(key)) next.add(key);
    expanded.value = next;
  }

  function toggleAll() {
    expanded.value = allExpanded.value ? new Set() : new Set(allKeys.value);
  }

  function chevron(key: string) {
    return [
      'w-4 h-4 shrink-0 text-slate-500 transition-transform',
      isExpanded(key) ? 'rotate-180' : '',
    ];
  }

  function countLabel(count: number, singular: string, plural: string) {
    return `${count} ${count === 1 ? singular : plural}`;
  }

  function levelName(level: number) {
    return MATURITY_LEVELS[level]?.label ?? '';
  }

  function toDraft(row: MaturityResult, control: MissingControl): GapActionDraft {
    return {
      controlId: control.id,
      action: suggestedAction(row.subcategoryName, control.targetLevel, control.description),
      priority: levelPriority(control.targetLevel),
    };
  }
</script>
