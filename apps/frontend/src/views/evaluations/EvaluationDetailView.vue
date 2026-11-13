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
          <button
            v-if="canChangeProfile"
            type="button"
            class="ml-2 text-xs font-medium text-blue-600 dark:text-blue-400 hover:underline"
            @click="openProfileModal"
          >
            Cambiar perfil
          </button>
        </p>
      </div>
      <div class="flex items-center gap-2">
        <span
          v-if="evaluation"
          class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium badge-themed"
          :style="getStatusBadgeStyle(evaluation.status)"
        >
          {{ getStatusLabel(evaluation.status) }}
        </span>
      </div>
    </div>

    <!-- RF-AUD-08 (PRI-298): tras «Reanudar» una evaluación devuelta, el aviso explica por qué está
    otra vez En curso y lleva a las observaciones del auditor. Solo se ve si hay observaciones. -->
    <ReopenedNotice v-if="showReopenedNotice && evaluation" :evaluation-id="evaluation.id" />

    <BaseModal v-model="showProfileModal" title="Cambiar perfil comunitario" size="md">
      <p class="text-sm text-slate-600 dark:text-slate-300 mb-4">
        El perfil define qué controles se responden en esta evaluación. Solo se puede cambiar
        mientras la evaluación no comenzó (en Borrador y sin respuestas cargadas).
      </p>
      <BaseSelect
        v-model="selectedProfileId"
        label="Perfil comunitario"
        :options="profileOptions"
        :error="profileError"
      />
      <template #footer>
        <BaseButton variant="secondary" @click="showProfileModal = false">Cancelar</BaseButton>
        <BaseButton variant="primary" :loading="savingProfile" @click="saveProfile">
          Guardar
        </BaseButton>
      </template>
    </BaseModal>

    <ConfirmDialog
      v-model="showArchiveConfirm"
      title="Archivar evaluación"
      message="La evaluación se retira de la lista activa y deja de admitir cambios, pero conserva todo su historial (respuestas, evidencias y auditoría). No se puede deshacer."
      confirm-text="Archivar"
      @confirm="changeStatus('ARCHIVED')"
    />
    <ConfirmDialog
      v-model="showIncompleteConfirm"
      title="Hay controles sin responder"
      :message="incompleteMessage"
      confirm-text="Enviar a auditoría igual"
      variant="warning"
      @confirm="changeStatus('READY_FOR_AUDIT')"
    />
    <ConfirmDialog
      v-model="showDeleteConfirm"
      title="Eliminar evaluación"
      message="La evaluación no tiene datos cargados, así que se elimina definitivamente."
      confirm-text="Eliminar"
      variant="danger"
      @confirm="deleteEvaluation"
    />

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else-if="evaluation">
      <!-- Zona 3: ciclo de vida + «Qué sigue». El panel dice de quién es el turno, ofrece las
      acciones del estado actual (antes en la tarjeta «Acciones de Flujo») y deja a la vista las
      evidencias y las observaciones del auditor. -->
      <EvaluationLifecycleStepper :status="evaluation.status">
        <EvaluationActionPanel
          :plan="actionPlan"
          :progress="actionProgress"
          :busy="changingStatus"
          @status="onStatusAction"
          @delete="showDeleteConfirm = true"
        />
      </EvaluationLifecycleStepper>

      <!-- RF-AUD-09: registro de certificación -- solo existe una vez aprobada (sellada), nunca se
      recalcula ni se puede editar desde acá. -->
      <div
        v-if="evaluation.status === 'APPROVED' && evaluation.certificationSeal"
        class="bg-emerald-50 dark:bg-emerald-950 border border-emerald-200 dark:border-emerald-800 rounded-xl p-4 flex items-start gap-3"
      >
        <ShieldCheckIcon class="w-6 h-6 text-emerald-600 dark:text-emerald-400 shrink-0 mt-0.5" />
        <div class="text-sm text-emerald-800 dark:text-emerald-200">
          <p class="font-semibold">Evaluación aprobada y sellada</p>
          <p class="text-emerald-700 dark:text-emerald-300">
            <span v-if="evaluation.approvedByName"
              >Certificada por {{ evaluation.approvedByName }}</span
            >
            <span v-if="evaluation.approvedAt">
              el {{ formatDateTime(evaluation.approvedAt) }}</span
            >
          </p>
          <p class="font-mono text-xs text-emerald-600 dark:text-emerald-400 mt-1 break-all">
            Sello: {{ evaluation.certificationSeal }}
          </p>
        </div>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <StatsCard
          title="Nivel Global"
          :value="evaluation.globalMaturity != null ? `${evaluation.globalMaturity}/4` : '—'"
          :icon="ChartBarIcon"
          icon-bg-class="bg-blue-50 dark:bg-blue-950"
          icon-color-class="text-blue-600 dark:text-blue-400"
        />
        <StatsCard
          title="Progreso"
          :value="`${progressPercent}%`"
          :icon="ClipboardDocumentCheckIcon"
          icon-bg-class="bg-emerald-50 dark:bg-emerald-950"
          icon-color-class="text-emerald-700 dark:text-emerald-400"
        />
        <StatsCard
          title="Respuestas"
          :value="responses.length"
          :icon="DocumentTextIcon"
          icon-bg-class="bg-amber-50 dark:bg-amber-950"
          icon-color-class="text-amber-700 dark:text-amber-400"
        />
      </div>

      <!-- PRI-354: observaciones del auditor, de solo lectura, para la organización (responsable
      y evaluador interno) desde que la evaluación vuelve. Es la forma en que el auditor le explica
      por qué la devuelve. El VIEWER no las ve; el backend lo exige igual. -->
      <AuditObservationsReadOnly
        v-if="showAuditorObservations"
        :evaluation-id="evaluation.id"
        :hide-when-empty="['IN_PROGRESS', 'READY_FOR_AUDIT'].includes(evaluation.status)"
      />

      <!-- RF-AUD-10: historial de ciclos de auditoría (envíos/devoluciones) de esta evaluación --
      cualquiera con acceso a la organización puede verlo, incluido el AUDITOR. -->
      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
      >
        <button
          type="button"
          class="flex items-center justify-between w-full text-left"
          @click="toggleAuditTrail"
        >
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">Ciclos de Auditoría</h3>
          <ChevronDownIcon
            class="w-5 h-5 text-slate-400 transition-transform"
            :class="{ 'rotate-180': showAuditTrail }"
          />
        </button>
        <div v-if="showAuditTrail" class="mt-4 space-y-4">
          <div v-if="loadingAuditTrail" class="text-sm text-slate-500 dark:text-slate-400">
            Cargando…
          </div>
          <div
            v-else-if="auditTrail.length === 0"
            class="text-sm text-slate-500 dark:text-slate-400"
          >
            Sin transiciones registradas todavía.
          </div>
          <div
            v-for="(events, cycleNumber) in auditTrailByCycle"
            v-else
            :key="cycleNumber"
            class="space-y-2"
          >
            <p class="text-xs font-semibold uppercase text-slate-500 dark:text-slate-400">
              {{ Number(cycleNumber) === 0 ? 'Autoevaluación inicial' : `Ciclo ${cycleNumber}` }}
            </p>
            <ul class="space-y-1.5">
              <li
                v-for="event in events"
                :key="event.id"
                class="flex flex-wrap items-center gap-2 text-sm text-slate-700 dark:text-slate-200"
              >
                <span class="font-medium">{{
                  event.status ? getStatusLabel(event.status) : 'Certificación'
                }}</span>
                <span class="text-slate-400 dark:text-slate-500">—</span>
                <span class="text-slate-500 dark:text-slate-400">{{
                  formatDateTime(event.occurredAt)
                }}</span>
                <span v-if="event.performedByName" class="text-slate-500 dark:text-slate-400">
                  ({{ event.performedByName }})
                </span>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
      >
        <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
          <h3 class="text-lg font-semibold text-slate-900 dark:text-white">
            Resultados de Madurez
          </h3>
          <div class="flex flex-wrap items-center gap-3">
            <div
              v-if="results.length > 0 && resultsTab === 'desglose'"
              class="flex items-center rounded-lg border border-slate-200 dark:border-slate-700 p-0.5 text-xs font-medium"
            >
              <button
                type="button"
                class="px-2.5 py-1 rounded-md transition-colors"
                :class="
                  groupBy === 'function'
                    ? 'bg-blue-600 text-white'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'
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
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'
                "
                @click="groupBy = 'level'"
              >
                Por nivel de madurez
              </button>
            </div>
            <div
              v-if="results.length > 0 && resultsTab === 'desglose'"
              class="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"
              data-testid="function-filter"
            >
              <span aria-hidden="true">Función</span>
              <BaseSelect
                v-model="functionFilter"
                :options="functionFilterOptions"
                :searchable="false"
                size="sm"
                aria-label="Filtrar por función"
                class="w-56"
              />
            </div>
            <div
              v-if="results.length > 0 && resultsTab === 'desglose'"
              class="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"
            >
              <span aria-hidden="true">Mostrar por página</span>
              <BaseSelect
                v-model="pageSize"
                :options="pageSizeSelectOptions"
                :searchable="false"
                size="sm"
                aria-label="Mostrar por página"
                class="w-20"
              />
            </div>
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
            </div>
          </div>
        </div>

        <div
          v-if="results.length === 0"
          class="text-center py-8 text-slate-500 dark:text-slate-400"
        >
          <p>
            No hay resultados disponibles aún. Complete las respuestas para calcular la madurez.
          </p>
        </div>

        <div v-else class="space-y-4">
          <div
            class="flex w-max items-center rounded-lg border border-slate-200 dark:border-slate-700 p-0.5 text-xs font-medium"
          >
            <button
              type="button"
              class="px-2.5 py-1 rounded-md transition-colors"
              :class="
                resultsTab === 'desglose'
                  ? 'bg-blue-600 text-white'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'
              "
              @click="resultsTab = 'desglose'"
            >
              Desglose
            </button>
            <button
              type="button"
              class="px-2.5 py-1 rounded-md transition-colors"
              :class="
                resultsTab === 'tablas'
                  ? 'bg-blue-600 text-white'
                  : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'
              "
              @click="resultsTab = 'tablas'"
            >
              Tablas y promedios
            </button>
          </div>

          <MaturitySummaryTables
            v-if="resultsTab === 'tablas'"
            :results="results"
            :catalog-functions="catalogFunctions"
          />

          <template v-else>
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
                  <span class="text-slate-500 font-normal dark:text-slate-400"
                    >({{ group.total }})</span
                  >
                </span>
                <ChevronDownIcon
                  :class="[
                    'w-4 h-4 text-slate-500 transition-transform shrink-0 dark:text-slate-400',
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
                      class="text-sm font-semibold maturity-text"
                      :style="getMaturityTextStyle(result.currentLevel)"
                    >
                      {{ result.currentLevel }}/4 (Objetivo: {{ result.targetLevel }})
                    </span>
                  </div>
                  <div class="w-full bg-slate-200 rounded-full h-2 dark:bg-slate-700">
                    <div
                      class="h-2 rounded-full"
                      :style="{
                        width: `${(result.currentLevel / 4) * 100}%`,
                        backgroundColor: getMaturityColor(result.currentLevel),
                      }"
                    />
                  </div>
                  <div v-if="result.gap > 0" class="mt-1 text-xs text-red-600 dark:text-red-400">
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
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, watch, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useRoute, useRouter } from 'vue-router';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { useAuthStore } from '@/stores/auth';
  import {
    auditService,
    catalogService,
    communityProfilesService,
    evaluationsService,
    evidenceService,
  } from '@/services/resources';
  import {
    getStatusLabel,
    getStatusBadgeStyle,
    getMaturityColor,
    getMaturityTextStyle,
    getErrorMessage,
    formatDateTime,
  } from '@/utils/helpers';
  import StatsCard from '@/components/common/StatsCard.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import { useNotification } from '@/composables/useUtils';
  import { ALL_FUNCTIONS, functionOptions, filterByFunction } from '@/utils/resultFilters';
  import MaturityRadarChart from '@/components/charts/MaturityRadarChart.vue';
  import MaturitySummaryTables from '@/components/evaluations/MaturitySummaryTables.vue';
  import EvaluationLifecycleStepper from '@/components/evaluations/EvaluationLifecycleStepper.vue';
  import AuditObservationsReadOnly from '@/components/evaluations/AuditObservationsReadOnly.vue';
  import ReopenedNotice from '@/components/evaluations/ReopenedNotice.vue';
  import EvaluationActionPanel from '@/components/evaluations/EvaluationActionPanel.vue';
  import { buildActionPlan, type PlanRole } from '@/utils/evaluationActionPlan';
  import {
    ArrowLeftIcon,
    ChartBarIcon,
    ChevronDownIcon,
    ClipboardDocumentCheckIcon,
    DocumentTextIcon,
    ShieldCheckIcon,
    CalculatorIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    MaturityCatalog,
    MaturityResult,
    MaturityFunction,
    EvaluationAuditCycleEvent,
  } from '@/types';

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
  // Ids de los controles que esta evaluación debe responder (catálogo podado al perfil).
  const catalogControlIds = ref<Set<string>>(new Set());
  // Árbol del catálogo (podado al perfil si aplica) -- se fetchea una vez en onMounted; sirve para
  // contar controles Y para el resumen de la pestaña "Tablas y promedios" (siglas + orden oficial).
  const catalogFunctions = ref<MaturityFunction[]>([]);
  const resultsTab = ref<'desglose' | 'tablas'>('desglose');

  // RF-AUD-10: ciclos de auditoría -- se carga perezoso, solo al abrir la sección la primera vez.
  const showAuditTrail = ref(false);
  const loadingAuditTrail = ref(false);
  const auditTrailLoaded = ref(false);
  const auditTrail = ref<EvaluationAuditCycleEvent[]>([]);
  const auditTrailByCycle = computed(() => {
    const grouped: Record<number, EvaluationAuditCycleEvent[]> = {};
    for (const event of auditTrail.value) {
      (grouped[event.cycleNumber] ??= []).push(event);
    }
    return grouped;
  });

  async function toggleAuditTrail() {
    showAuditTrail.value = !showAuditTrail.value;
    if (!showAuditTrail.value || auditTrailLoaded.value || !evaluation.value) return;
    loadingAuditTrail.value = true;
    try {
      const { data } = await evaluationsService.getAuditTrail(evaluation.value.id);
      auditTrail.value = data;
      auditTrailLoaded.value = true;
    } catch (err) {
      console.error(err);
    } finally {
      loadingAuditTrail.value = false;
    }
  }

  const progressPercent = computed(() => {
    if (!evaluation.value || totalControls.value === 0) return 0;
    return Math.min(100, Math.round((responses.value.length / totalControls.value) * 100));
  });

  // Cambio de perfil (C15): solo quien puede crear la evaluación, y solo si todavía no comenzó
  // (Borrador y sin respuestas) -- el backend lo exige igual (EvaluationService.changeProfile).
  const notification = useNotification();
  const showProfileModal = ref(false);
  const savingProfile = ref(false);
  const profileError = ref('');
  const selectedProfileId = ref('');
  const profileOptions = ref<{ value: string; label: string }[]>([]);

  const canChangeProfile = computed(
    () =>
      !!evaluation.value &&
      evaluation.value.status === 'DRAFT' &&
      responses.value.length === 0 &&
      authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE'),
  );

  async function openProfileModal() {
    if (!evaluation.value) return;
    profileError.value = '';
    selectedProfileId.value = evaluation.value.communityProfileId || '';
    try {
      const { data } = await communityProfilesService.getAll(
        evaluation.value.catalogVersion || '5.0',
      );
      profileOptions.value = [
        { value: '', label: 'Catálogo completo (todos los controles)' },
        ...data.map((p) => ({ value: p.id, label: `${p.name} (${p.controlCount} controles)` })),
      ];
      showProfileModal.value = true;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudieron cargar los perfiles'));
    }
  }

  async function saveProfile() {
    if (!evaluation.value) return;
    savingProfile.value = true;
    profileError.value = '';
    try {
      const updated = await store.changeProfile(
        evaluation.value.id,
        selectedProfileId.value || null,
      );
      showProfileModal.value = false;
      notification.success('Perfil actualizado correctamente');
      // El árbol del catálogo viene podado al perfil: hay que volver a pedirlo.
      await loadCatalogTree(updated.catalogVersion || '5.0', updated.communityProfileId);
    } catch (err) {
      profileError.value = getErrorMessage(err, 'No se pudo cambiar el perfil');
    } finally {
      savingProfile.value = false;
    }
  }

  // PRI-354: las observaciones del auditor las lee la organización desde la devolución. PRI-298
  // (RF-AUD-08): también mientras corrige tras «Reanudar» (En curso) y al reenviarla (Lista para
  // auditoría). Siguen ocultas En auditoría, cuando son el borrador del auditor. Mismo criterio que
  // AuditObservationService en el backend.
  const OBSERVATION_VISIBLE_STATUSES = [
    'RETURNED',
    'IN_PROGRESS',
    'READY_FOR_AUDIT',
    'APPROVED',
    'ARCHIVED',
  ];
  const isOrganizationUser = computed(() =>
    authStore.hasAnyRole('ORG_RESPONSIBLE', 'INTERNAL_EVALUATOR'),
  );
  const showAuditorObservations = computed(
    () =>
      !!evaluation.value &&
      OBSERVATION_VISIBLE_STATUSES.includes(evaluation.value.status) &&
      isOrganizationUser.value,
  );
  // El aviso de reapertura: En curso (el componente decide mostrarse solo si hay observaciones,
  // que en ese estado solo existen si una auditoría anterior la devolvió).
  const showReopenedNotice = computed(
    () => evaluation.value?.status === 'IN_PROGRESS' && isOrganizationUser.value,
  );

  const canRespondRole = computed(() => authStore.hasAnyRole('PRISMA_ADMIN', 'INTERNAL_EVALUATOR'));

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
  // Filtro por función (RF-VIZ-05): deja visibles solo las subcategorías de la función elegida.
  // Es del lado del cliente, así que la tabla se actualiza al instante y sin recargar la página.
  const functionFilter = ref<string>(ALL_FUNCTIONS);
  const functionFilterOptions = computed(() => functionOptions(results.value));
  const filteredResults = computed(() => filterByFunction(results.value, functionFilter.value));

  // Si la función elegida ya no está (p. ej. tras recalcular la madurez), se vuelve a «Todas».
  watch(results, (all) => {
    if (functionFilter.value && !all.some((r) => r.functionId === functionFilter.value)) {
      functionFilter.value = ALL_FUNCTIONS;
    }
  });
  // Al elegir una función se abre su sección: llegar a una sección colapsada tras filtrar sería
  // un clic de más.
  watch(functionFilter, (id) => {
    if (id) expandedGroups.value = new Set(expandedGroups.value).add(`function-${id}`);
  });
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
  const pageSizeSelectOptions = pageSizeOptions.map((n) => ({ value: n, label: String(n) }));
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
      for (const r of filteredResults.value) {
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
    for (const r of filteredResults.value) {
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

  // Archivar es definitivo (ARCHIVED es un estado terminal): pide confirmación.
  const showArchiveConfirm = ref(false);

  // Controles del catálogo (o del perfil) sin respuesta. Si el catálogo todavía no cargó no se
  // puede saber, y en ese caso no se advierte.
  const unansweredCount = computed(() => {
    if (catalogControlIds.value.size === 0) return 0;
    const answered = new Set(responses.value.map((r) => r.controlId));
    let missing = 0;
    for (const id of catalogControlIds.value) if (!answered.has(id)) missing++;
    return missing;
  });

  // Enviar a auditoría con controles sin responder: el modelo cuenta cada uno como «no cumple», así
  // que antes de enviar se le avisa a la persona y confirma (o vuelve a responder).
  const showIncompleteConfirm = ref(false);
  const incompleteMessage = computed(() => {
    const n = unansweredCount.value;
    const total = catalogControlIds.value.size;
    const sinRespuesta =
      n === 1
        ? '1 de los ' + total + ' controles no tiene respuesta'
        : `${n} de los ${total} controles no tienen respuesta`;
    return (
      `${sinRespuesta}. Todos los controles sin respuesta se cuentan como «no cumple» al calcular ` +
      'la madurez, así que el nivel resultante puede ser menor al real. ' +
      '¿Querés enviar la evaluación a auditoría en estas condiciones?'
    );
  });

  function onStatusAction(status: string) {
    if (status === 'ARCHIVED') showArchiveConfirm.value = true;
    else if (status === 'READY_FOR_AUDIT' && unansweredCount.value > 0)
      showIncompleteConfirm.value = true;
    else changeStatus(status);
  }

  // Eliminar (C22): solo una evaluación SIN datos y quien puede borrarla; el servidor lo informa.
  const showDeleteConfirm = ref(false);
  const isDeletable = ref(false);
  const canDelete = computed(
    () => isDeletable.value && authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE'),
  );

  async function loadDeletable(id: string) {
    isDeletable.value = false;
    if (!authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')) return;
    try {
      const { data } = await evaluationsService.getDeletable([id]);
      isDeletable.value = data.includes(id);
    } catch {
      // Solo se pierde la ayuda visual: sin el botón de eliminar la pantalla funciona igual.
    }
  }

  async function deleteEvaluation() {
    if (!evaluation.value) return;
    try {
      await store.deleteEvaluation(evaluation.value.id);
      notification.success('Evaluación eliminada correctamente');
      router.push('/evaluations');
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo eliminar la evaluación'));
    }
  }

  const changingStatus = ref(false);
  async function changeStatus(status: string) {
    if (!evaluation.value) return;
    changingStatus.value = true;
    try {
      await store.updateStatus(evaluation.value.id, status);
    } catch (err) {
      console.error(err);
    } finally {
      changingStatus.value = false;
    }
  }

  // Contadores de las tarjetas «Evidencias» y «Observaciones del auditor» del panel. Son ayuda
  // visual: si no se pueden leer, las tarjetas se muestran igual, sin número.
  const evidenceCount = ref<number | null>(null);
  const observationCounts = ref<{ total: number; open: number } | null>(null);

  async function loadEvidenceCount(id: string) {
    try {
      const { data } = await evidenceService.getByEvaluation(id);
      evidenceCount.value = data.length;
    } catch {
      evidenceCount.value = null;
    }
  }

  async function loadObservationCounts(id: string) {
    try {
      const { data } = await auditService.getObservations(id);
      observationCounts.value = {
        total: data.length,
        open: data.filter((o) => o.status === 'OPEN' || o.status === 'IN_PROGRESS').length,
      };
    } catch {
      observationCounts.value = null;
    }
  }

  // Las observaciones solo se piden cuando la organización puede verlas (mismo criterio que el
  // bloque de abajo); si el estado cambia y pasan a corresponder, se piden en ese momento.
  watch(
    showAuditorObservations,
    (visible) => {
      if (visible && evaluation.value) loadObservationCounts(evaluation.value.id);
    },
    { immediate: true },
  );

  // «Qué sigue»: lo que le toca hacer a este usuario en el estado actual (ver evaluationActionPlan).
  const actionPlan = computed(() =>
    buildActionPlan({
      status: evaluation.value!.status,
      evaluationId: evaluation.value!.id,
      hasRole: (...roles: PlanRole[]) => authStore.hasAnyRole(...roles),
      canDelete: canDelete.value,
      showAuditorObservations: showAuditorObservations.value,
      evidenceCount: evidenceCount.value,
      observations: observationCounts.value,
    }),
  );
  const actionProgress = computed(() => ({
    percent: progressPercent.value,
    answered: responses.value.length,
    total: totalControls.value,
  }));

  // Árbol del catálogo (podado al perfil si aplica): sirve para contar los controles a responder.
  function loadCatalogTree(version: string, profileId?: string | null) {
    return catalogService
      .getByVersion(version, profileId ?? undefined)
      .then(({ data }) => {
        const functions = (data as MaturityCatalog).functions || [];
        catalogFunctions.value = functions;
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
        catalogControlIds.value = controlIds;
      })
      .catch(() => {
        totalControls.value = 0;
        catalogControlIds.value = new Set();
        catalogFunctions.value = [];
      });
  }

  onMounted(async () => {
    const id = route.params.id as string;
    await store.fetchEvaluation(id);
    if (evaluation.value) {
      const version = evaluation.value.catalogVersion || '5.0';
      await Promise.all([
        store.fetchResponses(id),
        store.fetchResults(id),
        loadCatalogTree(version, evaluation.value.communityProfileId),
        loadDeletable(id),
        loadEvidenceCount(id),
      ]);
    }
  });
</script>
