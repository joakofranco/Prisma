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
          Para cada subcategoría: nivel actual, próximo nivel objetivo y los controles del perfil
          que faltan para alcanzarlo.
        </p>
      </div>
      <p class="text-xs text-slate-500 dark:text-slate-400 shrink-0" data-test="gap-summary">
        {{ rows.length }} con brecha · {{ noGap }} sin brecha
      </p>
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

    <div v-else class="mt-4 divide-y divide-slate-100 dark:divide-slate-700" data-test="gap-list">
      <div v-for="row in rows" :key="row.subcategoryId" class="py-4" data-test="gap-row">
        <div class="flex items-center justify-between gap-3 mb-2 flex-wrap">
          <span class="text-sm font-medium text-slate-900 dark:text-white">
            {{ row.functionName }} › {{ row.categoryName }} › {{ row.subcategoryName }}
          </span>
          <span class="text-xs shrink-0" data-test="gap-levels">
            <b :style="{ color: getMaturityColor(row.currentLevel) }">
              Nivel {{ row.currentLevel }}
            </b>
            <span class="text-slate-500 dark:text-slate-400"> → </span>
            <b :style="{ color: getMaturityColor(row.nextLevel ?? 0) }">
              Nivel {{ row.nextLevel }} · {{ levelName(row.nextLevel ?? 0) }}
            </b>
          </span>
        </div>

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
          {{ missingLabel(row.missingControls.length, row.nextLevel ?? 0) }}:
        </p>
        <ul class="mt-1 divide-y divide-slate-100 dark:divide-slate-700">
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
              <p class="text-sm text-slate-800 dark:text-slate-100">{{ control.description }}</p>
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
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import type { ImprovementPlan, MaturityResult, MissingControl } from '@/types';
  import { getMaturityColor } from '@/utils/helpers';
  import { MATURITY_LEVELS } from '@/utils/constants';
  import {
    gapRows,
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
  const noGap = computed(() => withoutGapCount(props.results));

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
