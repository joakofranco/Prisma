<template>
  <section
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5"
    aria-labelledby="baseline-title"
    data-test="baseline-section"
  >
    <div class="flex items-start justify-between gap-3 flex-wrap">
      <div>
        <h2 id="baseline-title" class="text-base font-semibold text-slate-900 dark:text-white">
          Comparativa con la línea base
        </h2>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
          La línea base es el valor que tenía cada acción al crearse. El cumplimiento se mide contra
          la fecha límite original.
        </p>
      </div>
      <span
        v-if="summary && summary.deviationPoints !== null"
        :class="[
          'px-2.5 py-1 rounded-full text-xs font-semibold shrink-0',
          summary.deviationPoints < 0
            ? 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300'
            : 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
        ]"
        data-test="baseline-deviation"
      >
        Desvío {{ signedPoints(summary.deviationPoints) }}
      </span>
    </div>

    <p
      v-if="!summary || summary.total === 0"
      class="mt-4 text-sm text-slate-500 dark:text-slate-400"
      data-test="baseline-empty"
    >
      Sin acciones registradas: la comparativa aparece cuando se cree la primera.
    </p>

    <template v-else>
      <div class="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
        <ProgressBar
          label="Avance planificado"
          :value="summary.plannedPercent"
          :detail="`${summary.plannedDue} de ${summary.total} con fecha original cumplida`"
          bar-class="bg-slate-500 dark:bg-slate-400"
          data-test="baseline-planned"
        />
        <ProgressBar
          label="Avance real"
          :value="summary.actualPercent"
          :detail="`${summary.completed} de ${summary.total} realizadas`"
          bar-class="bg-blue-600 dark:bg-blue-500"
          data-test="baseline-actual"
        />
      </div>

      <dl class="mt-4 grid grid-cols-2 md:grid-cols-6 gap-3" data-test="baseline-stats">
        <div
          v-for="stat in stats"
          :key="stat.label"
          class="rounded-lg bg-slate-50 dark:bg-slate-900 p-3"
        >
          <dt class="text-xs text-slate-500 dark:text-slate-400">{{ stat.label }}</dt>
          <dd class="text-lg font-semibold text-slate-900 dark:text-white">{{ stat.value }}</dd>
          <dd v-if="stat.hint" class="text-xs text-slate-500 dark:text-slate-400">
            {{ stat.hint }}
          </dd>
        </div>
      </dl>

      <div class="mt-4 overflow-x-auto">
        <table class="min-w-full text-sm" data-test="baseline-table">
          <caption class="sr-only">
            Desvíos de cada acción respecto de su línea base
          </caption>
          <thead>
            <tr class="text-left text-xs text-slate-500 dark:text-slate-400">
              <th scope="col" class="py-2 pr-3 font-medium">Acción</th>
              <th scope="col" class="py-2 pr-3 font-medium">Fecha original</th>
              <th scope="col" class="py-2 pr-3 font-medium">Fecha actual</th>
              <th scope="col" class="py-2 pr-3 font-medium">Corrimiento</th>
              <th scope="col" class="py-2 pr-3 font-medium">Prioridad</th>
              <th scope="col" class="py-2 pr-3 font-medium">Responsable</th>
              <th scope="col" class="py-2 font-medium">Cumplimiento</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
            <tr v-for="item in comparison!.items" :key="item.id" data-test="baseline-row">
              <td class="py-2 pr-3 text-slate-900 dark:text-white">
                <span v-if="item.controlCode" class="text-xs text-slate-500 dark:text-slate-400">
                  {{ item.controlCode }} ·
                </span>
                {{ item.action }}
              </td>
              <td class="py-2 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                {{ dateLabel(item.baselineDueDate) }}
              </td>
              <td class="py-2 pr-3 whitespace-nowrap text-slate-700 dark:text-slate-200">
                {{ dateLabel(item.dueDate) }}
              </td>
              <td
                class="py-2 pr-3 whitespace-nowrap"
                :class="
                  item.replanned
                    ? 'font-semibold text-slate-900 dark:text-white'
                    : 'text-slate-500 dark:text-slate-400'
                "
                data-test="baseline-shift"
              >
                {{ shiftLabel(item) }}
              </td>
              <td
                class="py-2 pr-3 text-slate-700 dark:text-slate-200"
                data-test="baseline-priority"
              >
                {{ getPriorityLabel(item.priority) }}
                <span
                  v-if="item.priorityChanged"
                  class="block text-xs text-slate-500 dark:text-slate-400"
                >
                  antes: {{ getPriorityLabel(item.baselinePriority) }}
                </span>
              </td>
              <td
                class="py-2 pr-3 text-slate-700 dark:text-slate-200"
                data-test="baseline-responsible"
              >
                {{ item.responsible || '—' }}
                <span
                  v-if="item.responsibleChanged"
                  class="block text-xs text-slate-500 dark:text-slate-400"
                >
                  antes: {{ item.baselineResponsible || '—' }}
                </span>
              </td>
              <td class="py-2 whitespace-nowrap">
                <span
                  :class="[
                    'px-2 py-0.5 rounded-full text-xs font-medium',
                    complianceClass(item.compliance),
                  ]"
                  data-test="baseline-compliance"
                >
                  {{ complianceLabel(item) }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import ProgressBar from '@/components/common/ProgressBar.vue';
  import type { PlanBaselineComparison } from '@/types';
  import { formatDate, getPriorityLabel } from '@/utils/helpers';
  import {
    complianceClass,
    complianceLabel,
    shiftLabel,
    signedDays,
    signedPoints,
  } from '@/utils/planBaseline';

  const props = defineProps<{ comparison: PlanBaselineComparison | null }>();

  const summary = computed(() => props.comparison?.summary ?? null);

  const stats = computed(() => {
    const s = summary.value;
    if (!s) return [];
    return [
      {
        label: 'Replanificadas',
        value: s.replanned,
        hint: s.averageShiftDays === null ? '' : `Promedio ${signedDays(s.averageShiftDays)}`,
      },
      { label: 'En plazo', value: s.onTime, hint: '' },
      { label: 'Tarde', value: s.late, hint: '' },
      { label: 'Atrasadas', value: s.delayed, hint: '' },
      { label: 'Prioridad cambiada', value: s.priorityChanged, hint: '' },
      { label: 'Responsable cambiado', value: s.responsibleChanged, hint: '' },
    ];
  });

  function dateLabel(date: string | null) {
    return date ? formatDate(date) : '—';
  }
</script>
