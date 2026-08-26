<template>
  <div class="space-y-6">
    <div class="flex items-center gap-3">
      <router-link
        to="/evaluations"
        class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400"
        title="Volver a evaluaciones"
      >
        <ArrowLeftIcon class="w-5 h-5" />
      </router-link>
      <div class="flex-1">
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">
          {{ evaluation?.name || 'Evaluación' }}
        </h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          {{ evaluation?.organizationName }} — Catálogo {{ evaluation?.catalogVersion }}
          <span v-if="evaluation?.communityProfileName">
            — Perfil: {{ evaluation.communityProfileName }}</span
          >
        </p>
      </div>
      <div class="flex items-center gap-2">
        <span
          v-if="evaluation"
          class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium"
          :style="{
            backgroundColor: getStatusBgColor(evaluation.status),
            color: getStatusColor(evaluation.status),
          }"
        >
          {{ getStatusLabel(evaluation.status) }}
        </span>
      </div>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else-if="evaluation">
      <EvaluationLifecycleStepper :status="evaluation.status" />

      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 space-y-4">
        <div>
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white mb-1">Acciones de Flujo</h3>
          <p class="text-xs text-slate-500 dark:text-slate-400">
            Transiciones disponibles para tu rol en el estado actual, y accesos a responder,
            evidencias y auditoría -- desactivados cuando no corresponden al estado actual.
          </p>
        </div>

        <div v-if="statusActions.length > 0" class="flex flex-wrap gap-x-6 gap-y-3">
          <div v-for="action in statusActions" :key="action.status" class="flex flex-col gap-1.5">
            <BaseButton :variant="action.variant" @click="changeStatus(action.status)">
              {{ action.label }}
            </BaseButton>
            <span class="text-xs text-slate-400 max-w-[220px]">{{ action.hint }}</span>
          </div>
        </div>

        <div
          class="flex flex-wrap gap-x-6 gap-y-3"
          :class="statusActions.length > 0 ? 'pt-3 border-t border-slate-100' : ''"
        >
          <div v-for="action in flowActions" :key="action.key" class="flex flex-col gap-1.5">
            <BaseButton
              variant="secondary"
              :disabled="action.disabled"
              @click="!action.disabled && router.push(action.to)"
            >
              <component :is="action.icon" class="w-4 h-4" />
              {{ action.label }}
            </BaseButton>
            <span class="text-xs text-slate-400 max-w-[220px]">{{ action.hint }}</span>
          </div>
        </div>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <StatsCard
          title="Nivel Global"
          :value="evaluation.globalMaturity != null ? `${evaluation.globalMaturity}/4` : '—'"
          :icon="ChartBarIcon"
          icon-bg-class="bg-blue-50"
          icon-color-class="text-blue-600"
        />
        <StatsCard
          title="Progreso"
          :value="`${progressPercent}%`"
          :icon="ClipboardDocumentCheckIcon"
          icon-bg-class="bg-emerald-50"
          icon-color-class="text-emerald-600"
        />
        <StatsCard
          title="Respuestas"
          :value="responses.length"
          :icon="DocumentTextIcon"
          icon-bg-class="bg-amber-50"
          icon-color-class="text-amber-600"
        />
      </div>

      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
        <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">Resultados de Madurez</h3>
          <div class="flex flex-wrap items-center gap-3">
            <div
              v-if="results.length > 0"
              class="flex items-center rounded-lg border border-slate-200 dark:border-slate-700 p-0.5 text-xs font-medium"
            >
              <button
                type="button"
                class="px-2.5 py-1 rounded-md transition-colors"
                :class="
                  groupBy === 'function'
                    ? 'bg-blue-600 text-white'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700'
                "
                @click="groupBy = 'function'"
              >
                Por función
              </button>
              <button
                type="button"
                class="px-2.5 py-1 rounded-md transition-colors"
                :class="
                  groupBy === 'level'
                    ? 'bg-blue-600 text-white'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700'
                "
                @click="groupBy = 'level'"
              >
                Por nivel de madurez
              </button>
            </div>
            <label
              v-if="results.length > 0"
              class="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"
            >
              Mostrar por página
              <select
                v-model.number="pageSize"
                class="rounded-lg border border-slate-300 dark:border-slate-600 py-1 pl-2 pr-6 text-xs text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500"
              >
                <option v-for="size in pageSizeOptions" :key="size" :value="size">
                  {{ size }}
                </option>
              </select>
            </label>
            <div class="flex gap-2">
              <BaseButton
                v-if="canCalculate"
                variant="primary"
                :loading="calculating"
                @click="calculateMaturity"
              >
                <CalculatorIcon class="w-4 h-4" />
                {{ results.length ? 'Recalcular Madurez' : 'Calcular Madurez' }}
              </BaseButton>
              <router-link
                v-if="evaluation.status === 'APPROVED'"
                to="/reports"
                class="text-sm text-blue-600 hover:text-blue-700"
              >
                <ArrowDownTrayIcon class="w-4 h-4 inline" /> Reportes
              </router-link>
              <router-link
                v-if="!['DRAFT', 'ARCHIVED'].includes(evaluation.status)"
                :to="`/improvement/${evaluation.id}`"
                class="text-sm text-emerald-600 hover:text-emerald-700"
              >
                <ArrowTrendingUpIcon class="w-4 h-4 inline" /> Plan de Mejora
              </router-link>
            </div>
          </div>
        </div>

        <div v-if="results.length === 0" class="text-center py-8 text-slate-500 dark:text-slate-400">
          <p>
            No hay resultados disponibles aún. Complete las respuestas para calcular la madurez.
          </p>
        </div>

        <div v-else class="space-y-4">
          <MaturityRadarChart :items="radarItems" />

          <div
            v-for="group in paginatedGroups"
            :key="group.key"
            class="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden"
          >
            <button
              type="button"
              class="w-full flex items-center justify-between gap-3 px-4 py-2.5 bg-slate-50 dark:bg-slate-900 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors text-left"
              @click="toggleGroup(group.key)"
            >
              <span class="text-sm font-semibold text-slate-800 dark:text-slate-100">
                {{ group.title }}
                <span class="text-slate-400 font-normal">({{ group.total }})</span>
              </span>
              <ChevronDownIcon
                :class="[
                  'w-4 h-4 text-slate-400 transition-transform shrink-0',
                  expandedGroups.has(group.key) ? 'rotate-180' : '',
                ]"
              />
            </button>

            <div v-if="expandedGroups.has(group.key)" class="p-4 space-y-4">
              <div
                v-for="result in group.pageItems"
                :key="result.subcategoryId"
                class="p-4 bg-slate-50 dark:bg-slate-900 rounded-lg"
              >
                <div class="flex items-center justify-between mb-2">
                  <span class="text-sm font-medium text-slate-900 dark:text-white"
                    >{{ result.functionName }} > {{ result.categoryName }} >
                    {{ result.subcategoryName }}</span
                  >
                  <span
                    class="text-sm font-semibold"
                    :style="{ color: getMaturityColor(result.currentLevel) }"
                  >
                    {{ result.currentLevel }}/4 (Objetivo: {{ result.targetLevel }})
                  </span>
                </div>
                <div class="w-full bg-slate-200 rounded-full h-2">
                  <div
                    class="h-2 rounded-full"
                    :style="{
                      width: `${(result.currentLevel / 4) * 100}%`,
                      backgroundColor: getMaturityColor(result.currentLevel),
                    }"
                  />
                </div>
                <div v-if="result.gap > 0" class="mt-1 text-xs text-red-600">
                  Brecha: {{ result.gap }} nivel(es)
                </div>
              </div>

              <div
                v-if="group.totalPages > 1"
                class="flex items-center justify-between pt-2 border-t border-slate-200 dark:border-slate-700 text-xs text-slate-500 dark:text-slate-400"
              >
                <span>
                  Mostrando {{ (group.page - 1) * pageSize + 1 }}–{{
                    Math.min(group.page * pageSize, group.total)
                  }}
                  de {{ group.total }}
                </span>
                <div class="flex items-center gap-2">
                  <button
                    type="button"
                    :disabled="group.page === 1"
                    class="px-2.5 py-1 rounded-md border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    @click="setGroupPage(group.key, group.page - 1)"
                  >
                    Anterior
                  </button>
                  <span>Página {{ group.page }} de {{ group.totalPages }}</span>
                  <button
                    type="button"
                    :disabled="group.page === group.totalPages"
                    class="px-2.5 py-1 rounded-md border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed"
                    @click="setGroupPage(group.key, group.page + 1)"
                  >
                    Siguiente
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRoute, useRouter } from 'vue-router';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { useAuthStore } from '@/stores/auth';
  import { catalogService } from '@/services/resources';
  import {
    getStatusLabel,
    getStatusColor,
    getStatusBgColor,
    getMaturityColor,
    getErrorMessage,
  } from '@/utils/helpers';
  import StatsCard from '@/components/common/StatsCard.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import MaturityRadarChart from '@/components/charts/MaturityRadarChart.vue';
  import EvaluationLifecycleStepper from '@/components/evaluations/EvaluationLifecycleStepper.vue';
  import {
    ArrowLeftIcon,
    ChartBarIcon,
    ChevronDownIcon,
    ClipboardDocumentCheckIcon,
    DocumentTextIcon,
    PencilSquareIcon,
    PaperClipIcon,
    ShieldCheckIcon,
    ArrowTrendingUpIcon,
    ArrowDownTrayIcon,
    CalculatorIcon,
  } from '@heroicons/vue/24/outline';
  import type { MaturityCatalog, MaturityResult } from '@/types';

  const route = useRoute();
  const router = useRouter();
  const store = useEvaluationsStore();
  const authStore = useAuthStore();

  // Ver el comentario en OrganizationsView.vue: hay que usar storeToRefs, no desestructurar el
  // store directamente, o esta vista deja de reaccionar cuando el store carga respuestas/resultados.
  const { loading, responses, results } = storeToRefs(store);
  const evaluation = computed(() => store.currentEvaluation);
  const calculating = ref(false);
  const totalControls = ref(0);

  const progressPercent = computed(() => {
    if (!evaluation.value || totalControls.value === 0) return 0;
    return Math.min(100, Math.round((responses.value.length / totalControls.value) * 100));
  });

  const canRespondRole = computed(() => authStore.hasAnyRole('PRISMA_ADMIN', 'INTERNAL_EVALUATOR'));
  const canAuditRole = computed(() => authStore.hasAnyRole('PRISMA_ADMIN', 'AUDITOR'));

  const canRespond = computed(() => {
    if (!evaluation.value) return false;
    return (
      canRespondRole.value && ['DRAFT', 'IN_PROGRESS', 'RETURNED'].includes(evaluation.value.status)
    );
  });

  const canCalculate = computed(() => {
    if (!evaluation.value) return false;
    return canRespond.value && responses.value.length > 0;
  });

  interface FlowAction {
    key: string;
    label: string;
    to: string;
    icon: typeof PencilSquareIcon;
    disabled: boolean;
    hint: string;
  }

  // Responder/Evidencias/Auditoría siempre se muestran si el rol corresponde (antes se ocultaban
  // directamente cuando el estado no correspondía) -- ahora quedan visibles pero deshabilitados,
  // para que se entienda que la acción existe y por qué no está disponible en este momento.
  const flowActions = computed<FlowAction[]>(() => {
    if (!evaluation.value) return [];
    const s = evaluation.value.status;
    const list: FlowAction[] = [];

    if (canRespondRole.value) {
      list.push({
        key: 'respond',
        label: 'Responder',
        to: `/evaluations/${evaluation.value.id}/respond`,
        icon: PencilSquareIcon,
        disabled: !['DRAFT', 'IN_PROGRESS', 'RETURNED'].includes(s),
        hint: 'Cargar o corregir las respuestas de los controles.',
      });
    }

    list.push({
      key: 'evidence',
      label: 'Evidencias',
      to: `/evidence/${evaluation.value.id}`,
      icon: PaperClipIcon,
      disabled: s === 'ARCHIVED',
      hint: 'Documentación de respaldo de los controles.',
    });

    // Antes no había ningún link a /audit/:id en toda la app -- la ruta existía y estaba
    // habilitada para AUDITOR, pero no había forma de llegar ahí desde la UI.
    if (canAuditRole.value) {
      list.push({
        key: 'audit',
        label: 'Auditoría',
        to: `/audit/${evaluation.value.id}`,
        icon: ShieldCheckIcon,
        disabled: s === 'ARCHIVED',
        hint: 'Revisar y dejar observaciones sobre la evaluación.',
      });
    }

    return list;
  });

  // El radar necesita un punto por función: agrega los resultados por subcategoría
  // (nivel granular que calcula el backend) promediando nivel actual y objetivo.
  const radarItems = computed(() => {
    const byFunction = new Map<string, { name: string; current: number[]; target: number[] }>();
    for (const r of results.value) {
      const entry = byFunction.get(r.functionId) ?? {
        name: r.functionName,
        current: [],
        target: [],
      };
      entry.current.push(r.currentLevel);
      entry.target.push(r.targetLevel);
      byFunction.set(r.functionId, entry);
    }
    const avg = (nums: number[]) => nums.reduce((a, b) => a + b, 0) / nums.length;
    return Array.from(byFunction.values()).map((f) => ({
      name: f.name,
      current: Math.round(avg(f.current) * 10) / 10,
      target: Math.round(avg(f.target) * 10) / 10,
    }));
  });

  // Resultados agrupados en secciones colapsables -- por función (jerarquía del catálogo) o por
  // nivel de madurez alcanzado, para poder enfocarse en un grupo sin scrollear las ~100
  // subcategorías del catálogo real. Empiezan todas COLAPSADAS (Set vacío = nada expandido); el
  // usuario expande la(s) que le interesan.
  const groupBy = ref<'function' | 'level'>('function');
  const expandedGroups = ref<Set<string>>(new Set());

  function toggleGroup(key: string) {
    const next = new Set(expandedGroups.value);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    expandedGroups.value = next;
  }

  // Paginación de los items DENTRO de cada grupo (no de los grupos en sí: son sólo 5-6 según el
  // agrupador) -- un grupo "por función" puede tener 20+ subcategorías, y "por nivel" puede
  // concentrar la mayoría de las ~103 en un solo nivel al recién empezar una evaluación. La
  // página actual se guarda por clave de grupo (no un único número global), así que cambiar de
  // grupo o de agrupador no arrastra la página de otro. `pageSize` es compartido por todos los
  // grupos a la vez -- no hay necesidad real de que cada uno tenga el suyo propio.
  const pageSizeOptions = [10, 20, 50, 100] as const;
  const pageSize = ref<number>(10);
  const groupPages = ref<Map<string, number>>(new Map());

  function setGroupPage(key: string, page: number) {
    groupPages.value = new Map(groupPages.value).set(key, page);
  }

  interface ResultGroup {
    key: string;
    title: string;
    items: MaturityResult[];
  }

  const resultGroups = computed<ResultGroup[]>(() => {
    if (groupBy.value === 'level') {
      // Ascendente (0 primero): las subcategorías con mayor brecha quedan arriba, coherente con
      // como ImprovementPlanService prioriza los niveles mas bajos como mas urgentes.
      const byLevel = new Map<number, MaturityResult[]>();
      for (const r of results.value) {
        const arr = byLevel.get(r.currentLevel) ?? [];
        arr.push(r);
        byLevel.set(r.currentLevel, arr);
      }
      return Array.from(byLevel.keys())
        .sort((a, b) => a - b)
        .map((level) => ({
          key: `level-${level}`,
          title: `Nivel ${level}`,
          items: byLevel.get(level)!,
        }));
    }

    const order: string[] = [];
    const byFunction = new Map<string, ResultGroup>();
    for (const r of results.value) {
      let group = byFunction.get(r.functionId);
      if (!group) {
        group = { key: `function-${r.functionId}`, title: r.functionName, items: [] };
        byFunction.set(r.functionId, group);
        order.push(r.functionId);
      }
      group.items.push(r);
    }
    return order.map((id) => byFunction.get(id)!);
  });

  interface PaginatedResultGroup extends ResultGroup {
    total: number;
    page: number;
    totalPages: number;
    pageItems: MaturityResult[];
  }

  const paginatedGroups = computed<PaginatedResultGroup[]>(() =>
    resultGroups.value.map((group) => {
      const total = group.items.length;
      const totalPages = Math.max(1, Math.ceil(total / pageSize.value));
      // Si se achica pageSize o el grupo tiene menos items que antes (recalculo de madurez), la
      // página guardada puede quedar fuera de rango -- se acota en vez de resetear a 1, para no
      // perder el lugar si sólo cambió el tamaño de página.
      const page = Math.min(groupPages.value.get(group.key) ?? 1, totalPages);
      const start = (page - 1) * pageSize.value;
      return {
        ...group,
        total,
        page,
        totalPages,
        pageItems: group.items.slice(start, start + pageSize.value),
      };
    }),
  );

  async function calculateMaturity() {
    if (!evaluation.value) return;
    calculating.value = true;
    try {
      await store.calculateMaturity(evaluation.value.id);
      await store.fetchEvaluation(evaluation.value.id);
      await store.fetchResults(evaluation.value.id);
      const notification = (await import('@/composables/useUtils')).useNotification();
      notification.success('Madurez calculada correctamente');
    } catch (err) {
      const notification = (await import('@/composables/useUtils')).useNotification();
      notification.error(getErrorMessage(err, 'Error al calcular la madurez'));
    } finally {
      calculating.value = false;
    }
  }

  const statusActions = computed(() => {
    if (!evaluation.value) return [];
    const actions: {
      label: string;
      status: string;
      variant: 'primary' | 'secondary' | 'success' | 'danger';
      hint: string;
    }[] = [];
    const s = evaluation.value.status;

    if (authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR')) {
      if (s === 'DRAFT')
        actions.push({
          label: 'Iniciar Evaluación',
          status: 'IN_PROGRESS',
          variant: 'primary',
          hint: 'Habilita cargar respuestas de los controles.',
        });
      if (s === 'IN_PROGRESS')
        actions.push({
          label: 'Enviar a Auditoría',
          status: 'READY_FOR_AUDIT',
          variant: 'success',
          hint: 'Queda visible para que un auditor la tome.',
        });
      if (s === 'RETURNED')
        actions.push({
          label: 'Reanudar',
          status: 'IN_PROGRESS',
          variant: 'primary',
          hint: 'Vuelve a edición para corregir las observaciones del auditor.',
        });
    }

    if (authStore.hasAnyRole('PRISMA_ADMIN', 'AUDITOR')) {
      if (s === 'READY_FOR_AUDIT')
        actions.push({
          label: 'Iniciar Auditoría',
          status: 'IN_AUDIT',
          variant: 'primary',
          hint: 'La tomás como auditor para revisarla.',
        });
      if (s === 'IN_AUDIT') {
        actions.push({
          label: 'Aprobar',
          status: 'APPROVED',
          variant: 'success',
          hint: 'Confirma el resultado; habilita plan de mejora y reportes.',
        });
        actions.push({
          label: 'Devolver',
          status: 'RETURNED',
          variant: 'danger',
          hint: 'La regresa al responsable con tus observaciones cargadas.',
        });
      }
    }

    if (authStore.isAdmin) {
      if (s === 'APPROVED')
        actions.push({
          label: 'Archivar',
          status: 'ARCHIVED',
          variant: 'secondary',
          hint: 'La cierra como referencia histórica; deja de admitir cambios.',
        });
    }

    return actions;
  });

  async function changeStatus(status: string) {
    if (!evaluation.value) return;
    try {
      await store.updateStatus(evaluation.value.id, status);
    } catch (err) {
      console.error(err);
    }
  }

  onMounted(async () => {
    const id = route.params.id as string;
    await store.fetchEvaluation(id);
    if (evaluation.value) {
      const version = evaluation.value.catalogVersion || '5.0';
      await Promise.all([
        store.fetchResponses(id),
        store.fetchResults(id),
        catalogService
          .getByVersion(version, evaluation.value.communityProfileId)
          .then(({ data }) => {
            const functions = (data as MaturityCatalog).functions || [];
            // Un Requisito puede pertenecer a varias Subcategorías -- sus Controles aparecen una
            // vez por cada una en el árbol, pero cuentan una sola vez para el total a responder.
            const controlIds = new Set<string>();
            for (const fn of functions) {
              for (const cat of fn.categories) {
                for (const sub of cat.subcategories) {
                  for (const req of sub.requirements) {
                    for (const control of req.controls ?? []) {
                      controlIds.add(control.id);
                    }
                  }
                }
              }
            }
            totalControls.value = controlIds.size;
          })
          .catch(() => {
            totalControls.value = 0;
          }),
      ]);
    }
  });
</script>
