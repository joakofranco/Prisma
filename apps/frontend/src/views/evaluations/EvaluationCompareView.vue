<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <router-link
          to="/evaluations"
          class="text-sm font-medium text-blue-700 hover:underline dark:text-blue-300"
          >← Volver a Evaluaciones</router-link
        >
        <h1 class="mt-1 text-2xl font-bold text-slate-900 dark:text-white">
          Comparar evaluaciones
        </h1>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
          Qué mejoró y qué empeoró entre dos evaluaciones finalizadas de la misma organización
        </p>
      </div>
      <BaseButton
        variant="primary"
        :disabled="!comparison"
        :loading="downloading"
        data-testid="compare-pdf"
        @click="downloadPdf"
      >
        <ArrowDownTrayIcon class="h-4 w-4" />
        Descargar PDF
      </BaseButton>
    </div>

    <section
      class="rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800"
      aria-label="Elegir evaluaciones"
    >
      <div
        class="grid grid-cols-1 items-end gap-4 lg:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)_minmax(0,0.8fr)]"
      >
        <BaseSelect
          v-model="evaluationA"
          label="Evaluación anterior (A)"
          placeholder="Elegí la evaluación A"
          :options="candidateOptions"
          :disabled="candidates.length < 2"
          data-testid="compare-a"
        />
        <button
          type="button"
          class="h-10 rounded-lg border border-slate-300 px-3 text-slate-700 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-600 dark:text-slate-200 dark:hover:bg-slate-700"
          aria-label="Intercambiar A y B"
          :disabled="!evaluationA || !evaluationB"
          data-testid="compare-swap"
          @click="swap"
        >
          <ArrowsRightLeftIcon class="h-5 w-5" />
        </button>
        <BaseSelect
          v-model="evaluationB"
          label="Evaluación posterior (B)"
          placeholder="Elegí la evaluación B"
          :options="candidateOptions"
          :disabled="candidates.length < 2"
          data-testid="compare-b"
        />
        <BaseSelect
          v-if="choosesOrganization"
          v-model="organizationId"
          label="Organización"
          placeholder="Elegí una organización"
          :options="organizationOptions"
          data-testid="compare-organization"
        />
      </div>
      <p class="mt-3 text-xs text-slate-500 dark:text-slate-400">
        Solo aparecen las evaluaciones finalizadas: aprobadas, o archivadas después de aprobarse.
      </p>
    </section>

    <p v-if="loading" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando…
    </p>
    <BaseAlert v-else-if="error" variant="danger">{{ error }}</BaseAlert>
    <BaseAlert
      v-else-if="candidatesLoaded && candidates.length < 2"
      variant="info"
      data-testid="compare-not-enough"
    >
      Para comparar hacen falta al menos dos evaluaciones finalizadas de la organización
      <template v-if="candidates.length === 1"> (hoy tiene una)</template>.
    </BaseAlert>

    <template v-else-if="comparison">
      <BaseAlert v-if="!comparison.sameProfile" variant="warning" data-testid="compare-profiles">
        Las evaluaciones usan perfiles distintos ({{ comparison.evaluationA.profileName }} y
        {{ comparison.evaluationB.profileName }}): la madurez global no se compara directamente. Las
        subcategorías que están en una sola se marcan «solo en A» o «solo en B».
      </BaseAlert>

      <div
        class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4"
        data-testid="compare-summary"
      >
        <ProgressStatCard
          title="Madurez global"
          :value="`${formatLevel(comparison.evaluationA.maturityAverage)} → ${formatLevel(comparison.evaluationB.maturityAverage)}`"
          :icon="ChartBarIcon"
          icon-bg-class="bg-amber-50 dark:bg-amber-950"
          icon-color-class="text-amber-700 dark:text-amber-400"
        >
          <span :class="['font-semibold', deltaClass(maturityDelta)]">{{
            formatDelta(maturityDelta)
          }}</span>
          · Nivel {{ comparison.evaluationA.globalMaturity ?? '—' }} → Nivel
          {{ comparison.evaluationB.globalMaturity ?? '—' }}
        </ProgressStatCard>
        <ProgressStatCard
          title="Controles cumplidos"
          :value="`${comparison.evaluationA.compliantControls} → ${comparison.evaluationB.compliantControls}`"
          :icon="CheckCircleIcon"
          icon-bg-class="bg-emerald-50 dark:bg-emerald-950"
          icon-color-class="text-emerald-700 dark:text-emerald-400"
        >
          <span :class="['font-semibold', deltaClass(controlsDelta)]">{{
            formatDelta(controlsDelta, 0)
          }}</span>
          · de {{ comparison.evaluationB.totalControls }}
        </ProgressStatCard>
        <ProgressStatCard
          title="Subcategorías que subieron"
          :value="`▲ ${comparison.subcategoriesUp}`"
          value-class="text-emerald-700 dark:text-emerald-400"
          :icon="ArrowTrendingUpIcon"
          icon-bg-class="bg-emerald-50 dark:bg-emerald-950"
          icon-color-class="text-emerald-700 dark:text-emerald-400"
        >
          de {{ comparison.subcategories.length }} subcategorías
        </ProgressStatCard>
        <ProgressStatCard
          title="Subcategorías que bajaron"
          :value="`▼ ${comparison.subcategoriesDown}`"
          :value-class="comparison.subcategoriesDown ? 'text-red-700 dark:text-red-400' : undefined"
          :icon="ArrowTrendingDownIcon"
          icon-bg-class="bg-red-50 dark:bg-red-950"
          icon-color-class="text-red-600 dark:text-red-400"
        >
          {{ comparison.subcategoriesSame }} sin cambios
        </ProgressStatCard>
      </div>

      <p
        class="rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900 dark:border-blue-800 dark:bg-blue-950 dark:text-blue-200"
        data-testid="compare-reading"
      >
        <strong>Lectura:</strong> {{ comparison.reading }}
      </p>

      <section
        class="space-y-3 rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800"
        aria-labelledby="compare-functions-title"
      >
        <div class="flex flex-wrap items-center justify-between gap-2">
          <h2
            id="compare-functions-title"
            class="text-sm font-semibold text-slate-900 dark:text-white"
          >
            Cambio por función
          </h2>
          <p class="text-xs text-slate-500 dark:text-slate-400">
            Escala 0 a 4 · <span class="font-semibold">A</span> gris ·
            <span class="font-semibold text-blue-700 dark:text-blue-300">B</span> azul · clic en una
            función para ver sus subcategorías
          </p>
        </div>
        <ul class="divide-y divide-slate-100 dark:divide-slate-700" data-testid="compare-functions">
          <li v-for="f in comparison.functions" :key="f.functionId">
            <button
              type="button"
              class="grid w-full grid-cols-[minmax(6rem,9rem)_minmax(0,1fr)_3.5rem_3.5rem_6.5rem] items-center gap-3 rounded-lg px-2 py-2 text-left text-sm hover:bg-slate-50 dark:hover:bg-slate-700/50"
              :class="selectedFunction === f.functionId ? 'bg-blue-50 dark:bg-blue-950' : ''"
              :aria-pressed="selectedFunction === f.functionId"
              :data-testid="`compare-function-${f.functionName}`"
              @click="toggleFunction(f.functionId)"
            >
              <span class="font-medium text-slate-900 dark:text-white">{{ f.functionName }}</span>
              <span class="flex flex-col gap-1" aria-hidden="true">
                <span class="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
                  <span
                    class="block h-full rounded-full bg-slate-400"
                    :style="{ width: barWidth(f.levelA) }"
                  ></span>
                </span>
                <span class="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
                  <span
                    class="block h-full rounded-full bg-blue-600"
                    :style="{ width: barWidth(f.levelB) }"
                  ></span>
                </span>
              </span>
              <span class="text-slate-600 dark:text-slate-300">{{ formatLevel(f.levelA) }}</span>
              <span class="text-slate-900 dark:text-white">{{ formatLevel(f.levelB) }}</span>
              <span :class="['font-semibold', deltaClass(f.delta)]">{{
                formatDelta(f.delta)
              }}</span>
            </button>
          </li>
        </ul>
      </section>

      <section
        class="space-y-3 rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800"
        aria-labelledby="compare-sub-title"
      >
        <div class="flex flex-wrap items-center justify-between gap-3">
          <h2 id="compare-sub-title" class="text-sm font-semibold text-slate-900 dark:text-white">
            Cambio por subcategoría
            <template v-if="selectedFunctionName"> · {{ selectedFunctionName }}</template>
          </h2>
          <div
            class="inline-flex overflow-hidden rounded-lg border border-slate-300 dark:border-slate-600"
            role="group"
            aria-label="Filtro de subcategorías"
          >
            <button
              v-for="opt in filters"
              :key="opt.value"
              type="button"
              :aria-pressed="filter === opt.value"
              :data-testid="`compare-filter-${opt.value}`"
              :class="[
                'h-9 px-3 text-sm transition-colors',
                filter === opt.value
                  ? 'bg-blue-900 font-semibold text-white dark:bg-blue-600'
                  : 'bg-white text-slate-700 hover:bg-slate-100 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700',
              ]"
              @click="filter = opt.value"
            >
              {{ opt.label }} ({{ countFor(opt.value) }})
            </button>
          </div>
        </div>
        <p v-if="groups.length === 0" class="py-6 text-center text-sm text-slate-500">
          No hay subcategorías para este filtro.
        </p>
        <div v-else class="overflow-x-auto">
          <table class="w-full min-w-[40rem] text-sm" data-testid="compare-subcategories">
            <caption class="sr-only">
              Nivel de cada subcategoría en A y en B
            </caption>
            <thead>
              <tr
                class="bg-slate-100 text-left text-slate-700 dark:bg-slate-700 dark:text-slate-200"
              >
                <th scope="col" class="px-3 py-2 font-semibold">Subcategoría</th>
                <th scope="col" class="w-16 px-3 py-2 font-semibold">A</th>
                <th scope="col" class="w-16 px-3 py-2 font-semibold">B</th>
                <th scope="col" class="w-32 px-3 py-2 font-semibold">Cambio</th>
                <th scope="col" class="w-32 px-3 py-2 font-semibold">Objetivo</th>
              </tr>
            </thead>
            <tbody v-for="g in groups" :key="g.functionId">
              <tr class="bg-slate-50 dark:bg-slate-900/40">
                <th
                  scope="colgroup"
                  colspan="5"
                  class="px-3 py-2 text-left text-xs font-bold uppercase tracking-wide text-slate-600 dark:text-slate-300"
                >
                  {{ g.functionName }}
                </th>
              </tr>
              <tr
                v-for="r in g.rows"
                :key="r.subcategoryId"
                :class="[
                  'border-b border-slate-100 text-slate-900 dark:border-slate-700 dark:text-slate-100',
                  r.delta !== null && r.delta > 0 ? 'bg-emerald-50/70 dark:bg-emerald-950/40' : '',
                  r.delta !== null && r.delta < 0 ? 'bg-red-50/70 dark:bg-red-950/40' : '',
                ]"
              >
                <th scope="row" class="px-3 py-2 text-left font-normal">
                  <strong v-if="r.code" class="mr-1">{{ r.code }}</strong
                  >{{ r.name }}
                </th>
                <td class="px-3 py-2">{{ formatLevel(r.levelA, 0) }}</td>
                <td class="px-3 py-2">{{ formatLevel(r.levelB, 0) }}</td>
                <td :class="['px-3 py-2 font-semibold', deltaClass(r.delta)]">
                  {{
                    r.delta === null
                      ? r.levelA === null
                        ? 'solo en B'
                        : 'solo en A'
                      : formatDelta(r.delta, 0)
                  }}
                </td>
                <td class="px-3 py-2 text-slate-600 dark:text-slate-300">{{ targetLabel(r) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { comparisonService } from '@/services/resources';
  import { useAuthStore } from '@/stores/auth';
  import { useOrganizationsStore } from '@/stores/organizations';
  import { useNotification } from '@/composables/useUtils';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { blobErrorMessage, saveBlob } from '@/utils/files';
  import {
    defaultPair,
    deltaClass,
    filterSubcategories,
    formatDelta,
    formatLevel,
    groupByFunction,
    targetLabel,
    type DeltaFilter,
  } from '@/utils/comparison';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import ProgressStatCard from '@/components/dashboard/ProgressStatCard.vue';
  import {
    ArrowDownTrayIcon,
    ArrowTrendingDownIcon,
    ArrowTrendingUpIcon,
    ArrowsRightLeftIcon,
    ChartBarIcon,
    CheckCircleIcon,
  } from '@heroicons/vue/24/outline';
  import type { ComparisonCandidate, EvaluationComparison } from '@/types';

  // RF-VIZ-06 (PRI-320): comparativo entre dos evaluaciones finalizadas de la misma organización.
  // El responsable compara las de su organización; el administrador y el auditor eligen la
  // organización (el auditor solo ve las que audita). El backend valida todo (multitenant).
  const route = useRoute();
  const router = useRouter();
  const authStore = useAuthStore();
  const orgStore = useOrganizationsStore();
  const notification = useNotification();

  const choosesOrganization = computed(() => authStore.isAdmin || authStore.isAuditor);
  const organizationId = ref((route.query.organizationId as string) || '');
  const candidates = ref<ComparisonCandidate[]>([]);
  const candidatesLoaded = ref(false);
  const evaluationA = ref((route.query.a as string) || '');
  const evaluationB = ref((route.query.b as string) || '');
  const comparison = ref<EvaluationComparison | null>(null);
  const loading = ref(false);
  const error = ref('');
  const downloading = ref(false);
  const filter = ref<DeltaFilter>('changed');
  const selectedFunction = ref<string | null>(null);

  const filters: { value: DeltaFilter; label: string }[] = [
    { value: 'all', label: 'Todas' },
    { value: 'changed', label: 'Solo cambios' },
    { value: 'up', label: 'Subieron' },
    { value: 'down', label: 'Bajaron' },
  ];

  const organizationOptions = computed(() =>
    orgStore.organizations
      .filter((o) => o.enabled)
      .map((o) => ({ value: o.id, label: o.name }))
      .sort((a, b) => a.label.localeCompare(b.label, 'es')),
  );
  const candidateOptions = computed(() =>
    candidates.value.map((c) => ({
      value: c.id,
      label: `${c.name} · ${c.profileName}${c.approvedAt ? ` · ${formatDate(c.approvedAt)}` : ''}`,
    })),
  );

  const maturityDelta = computed(() =>
    comparison.value
      ? Math.round(
          (comparison.value.evaluationB.maturityAverage -
            comparison.value.evaluationA.maturityAverage) *
            100,
        ) / 100
      : null,
  );
  const controlsDelta = computed(() =>
    comparison.value
      ? comparison.value.evaluationB.compliantControls -
        comparison.value.evaluationA.compliantControls
      : null,
  );
  const selectedFunctionName = computed(
    () =>
      comparison.value?.functions.find((f) => f.functionId === selectedFunction.value)
        ?.functionName ?? '',
  );
  const groups = computed(() =>
    groupByFunction(
      filterSubcategories(
        comparison.value?.subcategories ?? [],
        filter.value,
        selectedFunction.value,
      ),
    ),
  );

  function countFor(value: DeltaFilter): number {
    return filterSubcategories(comparison.value?.subcategories ?? [], value, selectedFunction.value)
      .length;
  }

  function barWidth(level: number | null): string {
    return `${level === null ? 0 : Math.min(100, Math.max(0, (level / 4) * 100))}%`;
  }

  function toggleFunction(id: string) {
    selectedFunction.value = selectedFunction.value === id ? null : id;
  }

  function swap() {
    const a = evaluationA.value;
    evaluationA.value = evaluationB.value;
    evaluationB.value = a;
  }

  async function loadCandidates() {
    candidatesLoaded.value = false;
    comparison.value = null;
    if (choosesOrganization.value && !organizationId.value) {
      candidates.value = [];
      return;
    }
    loading.value = true;
    error.value = '';
    try {
      const { data } = await comparisonService.candidates(
        choosesOrganization.value ? organizationId.value : undefined,
      );
      candidates.value = data;
      const ids = data.map((c) => c.id);
      if (!ids.includes(evaluationA.value) || !ids.includes(evaluationB.value)) {
        const pair = defaultPair(data);
        evaluationA.value = pair.a;
        evaluationB.value = pair.b;
      } else {
        // A y B llegaron por la URL y siguen siendo válidas: el watch no se dispara.
        loading.value = false;
        await loadComparison();
      }
    } catch (err) {
      candidates.value = [];
      error.value = getErrorMessage(err, 'No se pudieron cargar las evaluaciones');
    } finally {
      candidatesLoaded.value = true;
      loading.value = false;
    }
  }

  async function loadComparison() {
    if (!evaluationA.value || !evaluationB.value || evaluationA.value === evaluationB.value) {
      comparison.value = null;
      if (evaluationA.value && evaluationA.value === evaluationB.value) {
        error.value = 'Elegí dos evaluaciones distintas';
      }
      return;
    }
    loading.value = true;
    error.value = '';
    selectedFunction.value = null;
    try {
      const { data } = await comparisonService.compare(evaluationA.value, evaluationB.value);
      comparison.value = data;
      router.replace({
        query: {
          ...(choosesOrganization.value ? { organizationId: organizationId.value } : {}),
          a: evaluationA.value,
          b: evaluationB.value,
        },
      });
    } catch (err) {
      comparison.value = null;
      error.value = getErrorMessage(err, 'No se pudo comparar las evaluaciones');
    } finally {
      loading.value = false;
    }
  }

  async function downloadPdf() {
    if (!comparison.value) return;
    downloading.value = true;
    try {
      const { data } = await comparisonService.downloadPdf(evaluationA.value, evaluationB.value);
      saveBlob(data, 'comparativo-evaluaciones.pdf');
    } catch (err) {
      notification.error(await blobErrorMessage(err, 'No se pudo descargar el PDF'));
    } finally {
      downloading.value = false;
    }
  }

  watch(organizationId, () => loadCandidates());
  watch([evaluationA, evaluationB], () => loadComparison());

  onMounted(async () => {
    if (choosesOrganization.value) {
      if (orgStore.organizations.length === 0) {
        try {
          await orgStore.fetchOrganizations(1, 100);
        } catch {
          // Sin listado no hay selector útil: queda el pedido de elegir organización.
        }
      }
      if (!organizationId.value) {
        organizationId.value = organizationOptions.value[0]?.value ?? '';
        return;
      }
    }
    await loadCandidates();
  });
</script>
