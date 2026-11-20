<template>
  <section
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5 space-y-4"
    aria-labelledby="maturity-trend-title"
    data-testid="maturity-trend"
  >
    <div class="flex flex-wrap items-end gap-4">
      <div class="flex-1 min-w-[16rem]">
        <h3 id="maturity-trend-title" class="text-sm font-semibold text-slate-900 dark:text-white">
          Tendencia de la madurez
        </h3>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
          <template v-if="trend">{{ trend.organizationName }} · </template
          >{{
            view === 'progress'
              ? 'cómo se van cumpliendo los controles a lo largo del tiempo'
              : 'una marca por evaluación'
          }}
        </p>
      </div>
      <div
        class="inline-flex overflow-hidden rounded-lg border border-slate-300 dark:border-slate-600"
        role="group"
        aria-label="Vista"
      >
        <button
          v-for="v in views"
          :key="v.value"
          type="button"
          :aria-pressed="view === v.value"
          :data-testid="`trend-view-${v.value}`"
          :class="[
            'h-9 px-3.5 text-sm transition-colors',
            view === v.value
              ? 'bg-blue-900 text-white font-semibold dark:bg-blue-600'
              : 'bg-white text-slate-700 hover:bg-slate-100 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700',
          ]"
          @click="view = v.value"
        >
          {{ v.label }}
        </button>
      </div>
      <div v-if="isPlatformAdmin" class="w-full sm:w-72">
        <BaseSelect
          v-model="organizationId"
          label="Organización"
          placeholder="Elegí una organización"
          :options="organizationOptions"
          data-testid="trend-organization"
        />
      </div>
    </div>

    <p v-if="loading" class="text-sm text-slate-500 dark:text-slate-400 py-10 text-center">
      Cargando la tendencia…
    </p>
    <BaseAlert v-else-if="error" variant="danger">{{ error }}</BaseAlert>
    <p
      v-else-if="isPlatformAdmin && !organizationId"
      class="text-sm text-slate-500 dark:text-slate-400 py-10 text-center"
    >
      Elegí una organización para ver su tendencia.
    </p>
    <div
      v-else-if="trend && trend.points.length === 0"
      class="py-10 text-center space-y-1"
      data-testid="trend-empty"
    >
      <p class="text-sm font-semibold text-slate-900 dark:text-white">
        Todavía no hay evaluaciones con respuestas
      </p>
      <p class="text-sm text-slate-500 dark:text-slate-400">
        Cuando la organización empiece a responder su primera evaluación, acá vas a ver cómo se van
        cumpliendo los controles.
      </p>
    </div>

    <EvaluationProgressSection v-else-if="trend && view === 'progress'" :points="trend.points" />

    <template v-else-if="trend">
      <!-- RF-VIZ-06 (PRI-320): atajo al comparativo entre dos evaluaciones de esta organización. -->
      <router-link
        :to="{ path: '/evaluations/compare', query: { organizationId: trend.organizationId } }"
        class="inline-flex text-sm font-semibold text-blue-700 hover:underline dark:text-blue-300"
        data-testid="trend-compare-link"
        >Comparar dos evaluaciones →</router-link
      >
      <div
        class="flex flex-wrap items-end gap-6 rounded-lg border border-slate-200 bg-slate-50 px-4 py-3 dark:border-slate-700 dark:bg-slate-900/40"
      >
        <div>
          <p class="text-xs font-semibold text-slate-700 dark:text-slate-300 mb-1.5">Mostrar</p>
          <div
            class="inline-flex overflow-hidden rounded-lg border border-slate-300 dark:border-slate-600"
            role="group"
            aria-label="Qué mostrar"
          >
            <button
              v-for="m in metrics"
              :key="m.value"
              type="button"
              :aria-pressed="metric === m.value"
              :data-testid="`trend-metric-${m.value}`"
              :class="[
                'h-9 px-3.5 text-sm transition-colors',
                metric === m.value
                  ? 'bg-blue-900 text-white font-semibold dark:bg-blue-600'
                  : 'bg-white text-slate-700 hover:bg-slate-100 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700',
              ]"
              @click="metric = m.value"
            >
              {{ m.label }}
            </button>
          </div>
        </div>
        <fieldset>
          <legend class="text-xs font-semibold text-slate-700 dark:text-slate-300 mb-1.5">
            Perfiles
          </legend>
          <div class="flex flex-wrap gap-2">
            <label
              v-for="o in options"
              :key="o.key"
              class="inline-flex h-9 items-center gap-2 rounded-full border border-slate-300 bg-white px-3 text-sm text-slate-900 dark:border-slate-600 dark:bg-slate-800 dark:text-white"
            >
              <input
                v-model="selected"
                type="checkbox"
                :value="o.key"
                :data-testid="`trend-profile-${o.label}`"
                class="rounded border-slate-300 text-blue-600 focus:ring-blue-500"
              />
              <span
                class="inline-block h-3 w-3"
                :class="o.pointStyle === 'circle' ? 'rounded-full' : ''"
                :style="{
                  backgroundColor: o.color,
                  transform: o.pointStyle === 'rectRot' ? 'rotate(45deg) scale(0.85)' : undefined,
                  clipPath:
                    o.pointStyle === 'triangle' ? 'polygon(50% 0, 100% 100%, 0 100%)' : undefined,
                }"
                aria-hidden="true"
              ></span>
              {{ o.label }}
              <span class="text-xs text-slate-500 dark:text-slate-400">({{ o.count }})</span>
            </label>
          </div>
        </fieldset>
      </div>

      <p
        v-if="selected.length === 0"
        class="text-sm text-slate-500 dark:text-slate-400 py-10 text-center"
      >
        Marcá al menos un perfil para ver su tendencia.
      </p>
      <template v-else>
        <MaturityTrendLineChart
          :labels="chart.labels"
          :series="chart.series"
          :metric="metric"
          @select="openEvaluation"
        />

        <div class="overflow-x-auto">
          <table class="w-full text-sm" data-testid="trend-summary">
            <caption class="sr-only">
              Resumen por perfil
            </caption>
            <thead>
              <tr
                class="bg-slate-100 text-left text-slate-700 dark:bg-slate-700 dark:text-slate-200"
              >
                <th scope="col" class="px-3 py-2 font-semibold">Perfil</th>
                <th scope="col" class="px-3 py-2 font-semibold">Evaluaciones</th>
                <th scope="col" class="px-3 py-2 font-semibold">Primera</th>
                <th scope="col" class="px-3 py-2 font-semibold">Última</th>
                <th scope="col" class="px-3 py-2 font-semibold">Variación</th>
                <th scope="col" class="px-3 py-2 font-semibold">
                  Controles cumplidos en la última
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="s in rows"
                :key="s.key"
                class="border-b border-slate-200 text-slate-900 dark:border-slate-700 dark:text-slate-100"
              >
                <th scope="row" class="px-3 py-2 text-left font-medium">{{ s.label }}</th>
                <td class="px-3 py-2">{{ s.count }}</td>
                <template v-if="s.delta === null">
                  <td colspan="3" class="px-3 py-2 text-slate-600 dark:text-slate-300">
                    Una sola evaluación ({{ formatMeasure(measure(s.last), metric) }} ·
                    {{ pointDate(s.last) }}): la tendencia aparece desde la segunda.
                  </td>
                </template>
                <template v-else>
                  <td class="px-3 py-2">
                    {{ formatMeasure(measure(s.first), metric) }} ·
                    {{ pointDate(s.first) }}
                  </td>
                  <td class="px-3 py-2">
                    {{ formatMeasure(measure(s.last), metric) }} ·
                    {{ pointDate(s.last) }}
                  </td>
                  <td
                    class="px-3 py-2 font-semibold"
                    :class="
                      s.delta > 0
                        ? 'text-emerald-700 dark:text-emerald-400'
                        : s.delta < 0
                          ? 'text-red-700 dark:text-red-400'
                          : 'text-slate-600 dark:text-slate-300'
                    "
                  >
                    {{ formatDelta(s.delta, metric) }}
                  </td>
                </template>
                <td class="px-3 py-2">
                  {{ s.last.compliantControls }} de {{ s.last.totalControls }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { useRouter } from 'vue-router';
  import { dashboardService } from '@/services/resources';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import {
    buildSeries,
    formatDelta,
    formatMeasure,
    profileOptions,
    summaries,
    type TrendMetric,
  } from '@/utils/maturityTrend';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import MaturityTrendLineChart from '@/components/charts/MaturityTrendLineChart.vue';
  import EvaluationProgressSection from '@/components/dashboard/EvaluationProgressSection.vue';
  import type { MaturityTrend, MaturityTrendPoint } from '@/types';

  // RF-VIZ-07 (PRI-321): tendencia de UNA organización. El responsable ve la suya (el backend la
  // fuerza); el administrador elige cuál con el selector. Una línea por perfil marcado.
  const props = defineProps<{ isPlatformAdmin: boolean }>();

  const router = useRouter();
  const orgStore = useOrganizationsStore();

  const metrics: { value: TrendMetric; label: string }[] = [
    { value: 'compliance', label: 'Controles cumplidos (%)' },
    { value: 'maturity', label: 'Nivel de madurez' },
  ];

  // «Avance de la evaluación» por defecto: no hay que esperar a que una evaluación se apruebe.
  type View = 'progress' | 'between';
  const views: { value: View; label: string }[] = [
    { value: 'progress', label: 'Avance de la evaluación' },
    { value: 'between', label: 'Entre evaluaciones' },
  ];
  const view = ref<View>('progress');

  const trend = ref<MaturityTrend | null>(null);
  const loading = ref(false);
  const error = ref('');
  const metric = ref<TrendMetric>('compliance');
  const selected = ref<string[]>([]);
  const organizationId = ref('');

  const organizationOptions = computed(() =>
    orgStore.organizations
      .filter((o) => o.enabled)
      .map((o) => ({ value: o.id, label: o.name }))
      .sort((a, b) => a.label.localeCompare(b.label, 'es')),
  );

  const options = computed(() => profileOptions(trend.value?.points ?? []));
  const chart = computed(() =>
    buildSeries(trend.value?.points ?? [], options.value, selected.value),
  );
  const rows = computed(() =>
    summaries(trend.value?.points ?? [], options.value, selected.value, metric.value),
  );

  const pointDate = (p: MaturityTrendPoint) =>
    p.provisional ? 'hoy, provisorio' : formatDate(p.date);

  const measure = (p: MaturityTrendPoint) =>
    metric.value === 'compliance' ? p.compliancePercent : p.maturityAverage;

  // Al sincronizar el selector con la organización que eligió el backend no hay que volver a
  // pedir la tendencia (ya llegó).
  let syncingOrganization = false;

  /**
   * El administrador sin organización elegida pide la tendencia sin organización: el backend
   * devuelve la de actividad más reciente y el selector se pone en ella. Si no hay ninguna con
   * actividad, queda el pedido de elegir una.
   */
  async function load(initial = false) {
    const pickedByBackend = props.isPlatformAdmin && !organizationId.value;
    if (pickedByBackend && !initial) {
      trend.value = null;
      return;
    }
    loading.value = true;
    error.value = '';
    try {
      const { data } = await dashboardService.getMaturityTrend(
        props.isPlatformAdmin && organizationId.value ? organizationId.value : undefined,
      );
      // Sin organización (instalación sin actividad): queda el pedido de elegir una.
      if (data.organizationId === null) {
        trend.value = null;
        return;
      }
      trend.value = data;
      // Al cambiar de organización arrancan marcados todos sus perfiles.
      selected.value = profileOptions(data.points).map((o) => o.key);
      if (pickedByBackend) {
        syncingOrganization = true;
        organizationId.value = data.organizationId;
      }
    } catch (err) {
      trend.value = null;
      if (!pickedByBackend) {
        error.value = getErrorMessage(err, 'No se pudo cargar la tendencia de la madurez');
      }
    } finally {
      loading.value = false;
    }
  }

  function openEvaluation(evaluationId: string) {
    router.push(`/evaluations/${evaluationId}`);
  }

  watch(organizationId, () => {
    if (syncingOrganization) {
      syncingOrganization = false;
      return;
    }
    load();
  });

  onMounted(async () => {
    if (!props.isPlatformAdmin) {
      await load();
      return;
    }
    if (orgStore.organizations.length === 0) {
      try {
        await orgStore.fetchOrganizations(1, 100);
      } catch {
        // Sin listado no hay selector útil: el mensaje «Elegí una organización» queda visible.
      }
    }
    await load(true);
  });
</script>
