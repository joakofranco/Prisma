<template>
  <div class="space-y-4" data-testid="progress-section">
    <div class="flex flex-wrap items-end gap-4">
      <div class="w-full sm:w-96">
        <BaseSelect
          v-model="evaluationId"
          label="Evaluación"
          placeholder="Elegí una evaluación"
          :options="evaluationOptions"
          data-testid="progress-evaluation"
        />
      </div>
      <div>
        <p class="text-xs font-semibold text-slate-700 dark:text-slate-300 mb-1.5">Escala</p>
        <div
          class="inline-flex overflow-hidden rounded-lg border border-slate-300 dark:border-slate-600"
          role="group"
          aria-label="Escala del tiempo"
        >
          <button
            v-for="g in scales"
            :key="g.value"
            type="button"
            :aria-pressed="scale === g.value"
            :data-testid="`progress-scale-${g.value}`"
            :class="[
              'h-9 px-3 text-sm transition-colors',
              scale === g.value
                ? 'bg-blue-900 text-white font-semibold dark:bg-blue-600'
                : 'bg-white text-slate-700 hover:bg-slate-100 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700',
            ]"
            @click="scale = g.value"
          >
            {{ g.label }}
          </button>
        </div>
      </div>
    </div>

    <p v-if="loading" class="text-sm text-slate-500 dark:text-slate-400 py-10 text-center">
      Cargando el avance…
    </p>
    <BaseAlert v-else-if="error" variant="danger">{{ error }}</BaseAlert>
    <template v-else-if="progress">
      <p
        v-if="progress.periods.length === 0"
        class="text-sm text-slate-500 dark:text-slate-400 py-10 text-center"
        data-testid="progress-empty"
      >
        Esta evaluación todavía no tiene respuestas.
      </p>
      <template v-else>
        <div
          class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
          data-testid="progress-chips"
        >
          <ProgressStatCard
            title="Controles cumplidos"
            :value="progress.compliantControls"
            :suffix="`de ${progress.totalControls}`"
            :icon="CheckCircleIcon"
            icon-bg-class="bg-emerald-50 dark:bg-emerald-950"
            icon-color-class="text-emerald-700 dark:text-emerald-400"
            :percent="percentNow"
            bar-class="bg-green-600"
          >
            {{ formatMeasure(percentNow, 'compliance') }} · el resultado
          </ProgressStatCard>
          <ProgressStatCard
            title="Controles respondidos"
            :value="progress.answeredControls"
            :suffix="`de ${progress.totalControls}`"
            :icon="ClipboardDocumentCheckIcon"
            icon-bg-class="bg-blue-50 dark:bg-blue-950"
            icon-color-class="text-blue-600 dark:text-blue-400"
            :percent="percentAnswered"
            bar-class="bg-blue-600"
          >
            {{ formatMeasure(percentAnswered, 'compliance') }} · el trabajo ·
            {{ progress.totalControls - progress.answeredControls }} sin responder
          </ProgressStatCard>
          <ProgressStatCard
            title="Últimos 30 días"
            :value="deltaLabel"
            :icon="progress.last30DaysDelta < 0 ? ArrowTrendingDownIcon : ArrowTrendingUpIcon"
            :icon-bg-class="
              progress.last30DaysDelta < 0
                ? 'bg-red-50 dark:bg-red-950'
                : 'bg-emerald-50 dark:bg-emerald-950'
            "
            :icon-color-class="
              progress.last30DaysDelta < 0
                ? 'text-red-600 dark:text-red-400'
                : 'text-emerald-700 dark:text-emerald-400'
            "
            :value-class="
              progress.last30DaysDelta > 0
                ? 'text-emerald-700 dark:text-emerald-400'
                : progress.last30DaysDelta < 0
                  ? 'text-red-700 dark:text-red-400'
                  : undefined
            "
          >
            {{ deltaText }}
          </ProgressStatCard>
          <ProgressStatCard
            :title="finalized ? 'Primera respuesta' : 'En curso desde'"
            :value="progress.startedAt ? formatDate(progress.startedAt) : '—'"
            :icon="CalendarDaysIcon"
            icon-bg-class="bg-slate-100 dark:bg-slate-700"
            icon-color-class="text-slate-600 dark:text-slate-300"
          >
            <span
              class="mr-1 inline-block rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-bold text-blue-800 dark:bg-blue-950 dark:text-blue-300"
              >{{ progress.statusLabel }}</span
            >
            perfil {{ progress.profileName }}<template v-if="since"> · {{ since }}</template>
          </ProgressStatCard>
        </div>

        <EvaluationProgressChart :progress="progress" @select="selectWeek" />
        <ul class="flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-600 dark:text-slate-300">
          <li class="inline-flex items-center gap-1.5">
            <span class="inline-block h-0.5 w-5 bg-blue-600" aria-hidden="true"></span>% respondido
            (trabajo)
          </li>
          <li class="inline-flex items-center gap-1.5">
            <span class="inline-block h-0.5 w-5 bg-green-600" aria-hidden="true"></span>% que cumple
            (resultado)
          </li>
          <li class="inline-flex items-center gap-1.5">
            <span
              class="inline-block h-3 w-3 bg-slate-300 dark:bg-slate-600"
              aria-hidden="true"
            ></span
            >Actividad del período (respuestas guardadas y evidencias)
          </li>
        </ul>

        <BaseAlert v-if="!finalized" variant="info">
          Es el avance <strong>declarado por la organización</strong>: mientras la evaluación no
          esté aprobada, todavía no está validado por el auditor. La línea puede bajar si un control
          pasa de «Cumple» a «No cumple».
        </BaseAlert>

        <aside
          v-if="week"
          class="rounded-lg border border-slate-200 bg-slate-50 p-4 space-y-3 dark:border-slate-700 dark:bg-slate-900/40"
          aria-labelledby="progress-week-title"
          data-testid="progress-week"
        >
          <div class="flex items-center gap-2">
            <h4
              id="progress-week-title"
              class="flex-1 text-sm font-semibold text-slate-900 dark:text-white"
            >
              Detalle · {{ periodTitle(week, progress.granularity) }}
            </h4>
            <button
              type="button"
              class="h-8 w-8 rounded-lg border border-slate-300 text-slate-600 hover:bg-white dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-800"
              aria-label="Cerrar detalle del período"
              @click="selectedWeek = null"
            >
              ✕
            </button>
          </div>
          <p v-if="!week.gained.length && !week.lost.length" class="text-sm text-slate-500">
            En este período no cambió ningún control.
          </p>
          <div v-for="group in groups" :key="group.key" class="space-y-1">
            <p :class="['text-xs font-bold tracking-wide', group.className]">
              {{ group.title }} ({{ group.items.length }})
            </p>
            <div class="overflow-x-auto">
              <table class="w-full text-sm">
                <caption class="sr-only">
                  {{
                    group.title
                  }}
                </caption>
                <tbody>
                  <tr
                    v-for="c in visible(group)"
                    :key="`${group.key}-${c.controlId}`"
                    class="border-b border-slate-200 dark:border-slate-700 text-slate-900 dark:text-slate-100"
                  >
                    <th scope="row" class="py-1.5 pr-2 text-left font-semibold whitespace-nowrap">
                      <router-link
                        :to="`/evaluations/${progress.evaluationId}`"
                        class="text-blue-700 hover:underline dark:text-blue-300"
                        >{{ c.code }}</router-link
                      >
                    </th>
                    <td class="py-1.5 pr-2">{{ c.description }}</td>
                    <td class="py-1.5 pr-2 text-slate-600 dark:text-slate-300">
                      {{ c.functionName }}
                    </td>
                    <td class="py-1.5 text-slate-600 dark:text-slate-300 whitespace-nowrap">
                      {{ formatDate(c.changedAt).slice(0, 5)
                      }}<template v-if="c.changedBy"> · {{ c.changedBy }}</template>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <button
              v-if="group.items.length > LIMIT"
              type="button"
              class="text-xs font-semibold text-blue-700 hover:underline dark:text-blue-300"
              :data-testid="`progress-more-${group.key}`"
              @click="expanded[group.key] = !expanded[group.key]"
            >
              {{
                expanded[group.key] ? 'Ver menos' : `Ver todos (${group.items.length - LIMIT} más)`
              }}
            </button>
          </div>
        </aside>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, reactive, ref, watch } from 'vue';
  import { dashboardService } from '@/services/resources';
  import { formatDate, getErrorMessage, getStatusLabel } from '@/utils/helpers';
  import { formatMeasure, periodTitle } from '@/utils/maturityTrend';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import EvaluationProgressChart from '@/components/charts/EvaluationProgressChart.vue';
  import ProgressStatCard from '@/components/dashboard/ProgressStatCard.vue';
  import {
    ArrowTrendingDownIcon,
    ArrowTrendingUpIcon,
    CalendarDaysIcon,
    CheckCircleIcon,
    ClipboardDocumentCheckIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    ControlChange,
    EvaluationProgress,
    MaturityTrendPoint,
    ProgressGranularity,
  } from '@/types';

  // RF-VIZ-07 (PRI-321): avance semana a semana de UNA evaluación de la organización, también
  // mientras está en curso o en auditoría. Las evaluaciones salen de la tendencia ya cargada.
  const props = defineProps<{ points: MaturityTrendPoint[] }>();

  const LIMIT = 5;

  const evaluationId = ref('');
  const progress = ref<EvaluationProgress | null>(null);
  const loading = ref(false);
  const error = ref('');
  const selectedWeek = ref<number | null>(null);

  // Escala del tiempo: «Automática» deja que el backend elija según lo que dura la historia.
  type Scale = 'AUTO' | ProgressGranularity;
  const scales: { value: Scale; label: string }[] = [
    { value: 'AUTO', label: 'Automática' },
    { value: 'DAY', label: 'Por día' },
    { value: 'WEEK', label: 'Por semana' },
    { value: 'MONTH', label: 'Por mes' },
  ];
  const scale = ref<Scale>('AUTO');
  const expanded = reactive<Record<string, boolean>>({ gained: false, lost: false });

  // Más reciente primero; arranca en la última en curso (o la última, si no hay ninguna).
  const ordered = computed(() =>
    [...props.points].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime()),
  );
  const evaluationOptions = computed(() =>
    ordered.value.map((p) => ({
      value: p.evaluationId,
      label: `${p.evaluationName} · ${p.profileName} · ${getStatusLabel(p.status)}`,
    })),
  );

  const finalized = computed(() => {
    const p = props.points.find((x) => x.evaluationId === evaluationId.value);
    return !!p && !p.provisional;
  });
  const percentNow = computed(() =>
    progress.value && progress.value.totalControls
      ? Math.round((progress.value.compliantControls * 1000) / progress.value.totalControls) / 10
      : 0,
  );
  const percentAnswered = computed(() =>
    progress.value && progress.value.totalControls
      ? Math.round((progress.value.answeredControls * 1000) / progress.value.totalControls) / 10
      : 0,
  );
  const deltaLabel = computed(() => {
    const d = progress.value?.last30DaysDelta ?? 0;
    if (d === 0) return 'Sin cambios';
    return d > 0 ? `▲ +${d}` : `▼ −${Math.abs(d)}`;
  });
  // Además de «Cumple» (el resultado), los respondidos del mes (el trabajo): un mes con muchas
  // respuestas y ningún control nuevo cumplido no debe leerse como un mes sin actividad.
  const deltaText = computed(() => {
    const d = progress.value?.last30DaysDelta ?? 0;
    const a = progress.value?.last30DaysAnsweredDelta ?? 0;
    const answered = a > 0 ? ` · +${a} ${a === 1 ? 'respondido' : 'respondidos'}` : '';
    if (d === 0) return `en controles cumplidos durante el último mes${answered}`;
    const n = Math.abs(d) === 1 ? 'control cumplido' : 'controles cumplidos';
    return `${n} ${d > 0 ? 'más' : 'menos'} que hace un mes${answered}`;
  });
  // Cuánto lleva desde la primera respuesta: «12 días», «5 semanas», «6 meses».
  const since = computed(() => {
    if (!progress.value?.startedAt) return '';
    const days = Math.floor(
      (Date.now() - new Date(progress.value.startedAt).getTime()) / 86_400_000,
    );
    if (days < 14) return `${days} ${days === 1 ? 'día' : 'días'}`;
    if (days < 60) return `${Math.floor(days / 7)} semanas`;
    const months = Math.floor(days / 30);
    return `${months} ${months === 1 ? 'mes' : 'meses'}`;
  });

  const week = computed(() =>
    selectedWeek.value !== null && progress.value
      ? (progress.value.periods[selectedWeek.value] ?? null)
      : null,
  );
  const groups = computed(() => {
    if (!week.value) return [];
    return [
      {
        key: 'gained',
        title: 'PASARON A «CUMPLE»',
        className: 'text-emerald-700 dark:text-emerald-400',
        items: week.value.gained,
      },
      {
        key: 'lost',
        title: 'PASARON A «NO CUMPLE»',
        className: 'text-red-700 dark:text-red-400',
        items: week.value.lost,
      },
    ].filter((g) => g.items.length > 0);
  });

  function visible(group: { key: string; items: ControlChange[] }): ControlChange[] {
    return expanded[group.key] ? group.items : group.items.slice(0, LIMIT);
  }

  function selectWeek(index: number) {
    selectedWeek.value = index;
    expanded.gained = false;
    expanded.lost = false;
  }

  async function load(id: string) {
    selectedWeek.value = null;
    if (!id) {
      progress.value = null;
      return;
    }
    loading.value = true;
    error.value = '';
    try {
      const { data } = await dashboardService.getEvaluationProgress(
        id,
        scale.value === 'AUTO' ? undefined : scale.value,
      );
      progress.value = data;
    } catch (err) {
      progress.value = null;
      error.value = getErrorMessage(err, 'No se pudo cargar el avance de la evaluación');
    } finally {
      loading.value = false;
    }
  }

  watch(evaluationId, (id) => load(id));
  watch(scale, () => load(evaluationId.value));

  watch(
    () => props.points,
    (points) => {
      const current = points.find((p) => p.evaluationId === evaluationId.value);
      if (current) return;
      const inProgress = ordered.value.find((p) => p.provisional);
      evaluationId.value = (inProgress ?? ordered.value[0])?.evaluationId ?? '';
    },
    { immediate: true },
  );
</script>
