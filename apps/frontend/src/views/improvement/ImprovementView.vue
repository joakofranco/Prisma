<template>
  <div class="space-y-6">
    <div class="flex items-center gap-3">
      <router-link
        :to="`/evaluations/${evaluationId}`"
        class="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 dark:text-slate-400"
        title="Volver a la evaluación"
      >
        <ArrowLeftIcon class="w-5 h-5" />
      </router-link>
      <div class="flex-1">
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Plan de Mejora</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Acciones correctivas basadas en las brechas detectadas
        </p>
      </div>
      <div v-if="canManage" class="flex items-center gap-2">
        <BaseButton
          variant="secondary"
          :loading="loadingSuggestions"
          :disabled="!canEditFully"
          @click="openSuggestModal"
        >
          <SparklesIcon class="w-4 h-4" />
          Generar Sugerencias
        </BaseButton>
        <BaseButton variant="primary" :disabled="!canEditFully" @click="openCreateModal">
          <PlusIcon class="w-4 h-4" />
          Nueva Acción
        </BaseButton>
      </div>
    </div>

    <div
      v-if="isApproved"
      class="px-4 py-3 rounded-lg bg-amber-50 border border-amber-200 text-sm text-amber-900 dark:bg-amber-950 dark:border-amber-800 dark:text-amber-200"
      role="status"
      data-test="approved-banner"
    >
      Evaluación <strong>Aprobada</strong>: solo se puede actualizar el estado de cada acción.
    </div>
    <div
      v-else-if="isClosed"
      class="px-4 py-3 rounded-lg bg-slate-100 border border-slate-200 text-sm text-slate-700 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300"
      role="status"
      data-test="closed-banner"
    >
      El plan de mejora no está activo: la evaluación está en borrador o archivada.
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <template v-else>
      <div class="grid grid-cols-1 sm:grid-cols-4 gap-4">
        <StatsCard
          title="Pendientes"
          :value="plansByStatus('PENDING')"
          :icon="ClockIcon"
          icon-bg-class="bg-slate-50 dark:bg-slate-900"
          icon-color-class="text-slate-600 dark:text-slate-300"
        />
        <StatsCard
          title="En curso"
          :value="plansByStatus('IN_PROGRESS')"
          :icon="PlayIcon"
          icon-bg-class="bg-blue-50 dark:bg-blue-950"
          icon-color-class="text-blue-600 dark:text-blue-400"
        />
        <StatsCard
          title="Realizadas"
          :value="plansByStatus('COMPLETED')"
          :icon="CheckCircleIcon"
          icon-bg-class="bg-emerald-50 dark:bg-emerald-950"
          icon-color-class="text-emerald-700 dark:text-emerald-400"
        />
        <StatsCard
          title="Vencidas"
          :value="plansByStatus('OVERDUE')"
          :icon="ExclamationCircleIcon"
          icon-bg-class="bg-red-50 dark:bg-red-950"
          icon-color-class="text-red-600 dark:text-red-400"
        />
      </div>

      <section
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5"
        aria-labelledby="remediation-progress-title"
      >
        <h2
          id="remediation-progress-title"
          class="text-base font-semibold text-slate-900 dark:text-white"
        >
          Avance de remediación
        </h2>
        <p class="text-xs text-slate-500 dark:text-slate-400 mt-1">
          Porcentaje de acciones completadas. Las pendientes, en progreso y vencidas cuentan como
          abiertas.
        </p>
        <p
          v-if="plans.length === 0"
          class="mt-4 text-sm text-slate-500 dark:text-slate-400"
          data-test="progress-empty"
        >
          Sin acciones registradas: el avance aparece cuando se cree la primera.
        </p>
        <div v-else class="mt-4 space-y-4">
          <ProgressBar
            label="Avance total"
            :value="progress.overall.percent"
            :detail="progressDetail(progress.overall)"
            bar-class="bg-blue-600 dark:bg-blue-500"
            data-test="progress-overall"
          />
          <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
            <ProgressBar
              v-for="row in priorityRows"
              :key="row.key"
              :label="`Prioridad ${row.label}`"
              :value="progress[row.key].percent"
              :detail="progressDetail(progress[row.key])"
              :bar-class="row.barClass"
              :data-test="`progress-${row.key}`"
            />
          </div>
        </div>
      </section>

      <MaturityGapSection
        :results="results"
        :planned-control-ids="plannedControlIds"
        @create-action="openCreateFromGap"
      />

      <div
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden"
      >
        <div
          class="flex items-center justify-between gap-3 px-4 py-3 border-b border-slate-100 dark:border-slate-700"
        >
          <p class="text-sm font-semibold text-slate-900 dark:text-white">
            Acciones del plan
            <span class="font-normal text-slate-500 dark:text-slate-400" data-test="plan-count"
              >({{ plans.length }})</span
            >
          </p>
          <div class="relative">
            <button
              type="button"
              class="inline-flex items-center gap-2 text-sm px-3 py-1.5 rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed"
              :disabled="plans.length === 0 || exporting !== null"
              aria-haspopup="menu"
              :aria-expanded="exportMenuOpen"
              data-test="export-button"
              @click="exportMenuOpen = !exportMenuOpen"
            >
              <ArrowDownTrayIcon class="w-4 h-4" />
              {{ exporting ? 'Generando archivo…' : 'Exportar' }}
              <ChevronDownIcon class="w-3 h-3" />
            </button>
            <div
              v-if="exportMenuOpen"
              class="absolute right-0 mt-1 w-56 z-10 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 shadow-lg py-1 text-sm"
              role="menu"
            >
              <button
                type="button"
                role="menuitem"
                class="w-full text-left px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-700 flex items-center gap-2 text-slate-700 dark:text-slate-200"
                data-test="export-pdf"
                @click="exportPlan('pdf')"
              >
                <span class="text-red-600 dark:text-red-400 font-bold text-xs w-8">PDF</span>
                Documento (.pdf)
              </button>
              <button
                type="button"
                role="menuitem"
                class="w-full text-left px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-700 flex items-center gap-2 text-slate-700 dark:text-slate-200"
                data-test="export-excel"
                @click="exportPlan('excel')"
              >
                <span class="text-emerald-700 dark:text-emerald-400 font-bold text-xs w-8"
                  >XLS</span
                >
                Hoja de cálculo (.xlsx)
              </button>
            </div>
          </div>
        </div>
        <p
          v-if="plans.length === 0"
          class="px-4 pt-2 text-xs text-slate-500 dark:text-slate-400"
          data-test="export-hint"
        >
          Registrá al menos una acción para poder exportar el plan.
        </p>
        <div v-if="plans.length === 0" class="p-12 text-center text-slate-500 dark:text-slate-400">
          No hay planes de mejora registrados
        </div>
        <div v-else class="divide-y divide-slate-100 dark:divide-slate-700">
          <div
            v-for="plan in plans"
            :key="plan.id"
            class="p-4 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
          >
            <div class="flex items-start justify-between gap-4">
              <div class="flex-1">
                <p class="text-sm font-medium text-slate-900 dark:text-white">
                  {{ plan.action }}
                </p>
                <div class="flex items-center gap-3 mt-2 flex-wrap">
                  <span
                    :class="[
                      'text-xs px-2 py-0.5 rounded-full font-medium',
                      priorityClass(plan.priority),
                    ]"
                  >
                    {{ getPriorityLabel(plan.priority) }}
                  </span>
                  <span
                    :class="[
                      'text-xs px-2 py-0.5 rounded-full font-medium',
                      planStatusClass(plan.status),
                    ]"
                  >
                    {{ planStatusLabel(plan.status) }}
                  </span>
                  <span class="text-xs text-slate-500 dark:text-slate-400"
                    >Responsable: {{ plan.responsible }}</span
                  >
                  <span class="text-xs text-slate-500 dark:text-slate-400"
                    >Vence: {{ formatDate(plan.dueDate) }}</span
                  >
                </div>
                <p
                  v-if="plan.comments"
                  class="mt-2 text-xs text-slate-600 dark:text-slate-300 whitespace-pre-line"
                  data-test="plan-comments"
                >
                  <span class="font-medium">Comentarios:</span> {{ plan.comments }}
                </p>
              </div>
              <div v-if="canEditStatus" class="flex items-center gap-1 shrink-0">
                <button
                  type="button"
                  class="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 text-slate-500 hover:text-blue-600 dark:hover:text-blue-300 dark:text-slate-400"
                  title="Editar acción"
                  aria-label="Editar acción"
                  @click="openEditor(plan)"
                >
                  <PencilSquareIcon class="w-4 h-4" />
                </button>
                <BaseSelect
                  :model-value="plan.status"
                  :options="statusOptionsFor(plan.status, plan.dueDate)"
                  :searchable="false"
                  size="sm"
                  aria-label="Estado del plan de mejora"
                  class="w-36"
                  @update:model-value="
                    updatePlanStatus(plan.id, $event as ImprovementPlan['status'])
                  "
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <BaseModal v-model="showCreateModal" title="Nueva Acción de Mejora" size="lg">
      <form class="space-y-4" @submit.prevent="handleCreate">
        <BaseInput
          v-model="newPlan.action"
          label="Acción"
          placeholder="Describa la acción de mejora"
          :error="formErrors.action"
          required
        />
        <BaseInput
          v-model="newPlan.responsible"
          label="Responsable"
          placeholder="Nombre del responsable"
          :error="formErrors.responsible"
          required
        />
        <BaseSelect
          v-model="newPlan.priority"
          label="Prioridad"
          :options="priorityOptions"
          required
        />
        <BaseInput
          v-model="newPlan.dueDate"
          label="Fecha Límite"
          type="date"
          :min="today"
          :error="formErrors.dueDate"
          required
          @update:model-value="dueDateTouched = true"
        />
        <p
          v-if="!dueDateTouched && !formErrors.dueDate"
          class="-mt-3 text-xs text-slate-500 dark:text-slate-400"
          data-test="due-date-hint"
        >
          Sugerida: {{ suggestedMonths }} meses por ser prioridad
          {{ getPriorityLabel(newPlan.priority) }}. Podés cambiarla.
        </p>
        <BaseTextarea
          v-model="newPlan.comments"
          label="Comentarios (opcional)"
          placeholder="Notas sobre la acción: dependencias, recursos, riesgos…"
          :error="formErrors.comments"
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleCreate"> Crear </BaseButton>
      </template>
    </BaseModal>

    <!-- RF-PLN-04: edición de la acción (plazo, responsable, estado...). Con la evaluación aprobada
         solo el estado queda habilitado. -->
    <BaseModal v-model="showEditModal" title="Editar acción" size="lg">
      <form class="space-y-4" data-test="edit-form" @submit.prevent="saveEdit">
        <p
          v-if="isApproved"
          class="px-3 py-2 rounded-lg bg-amber-50 border border-amber-200 text-sm text-amber-900 dark:bg-amber-950 dark:border-amber-800 dark:text-amber-200"
        >
          La evaluación está aprobada: el resto de los campos queda bloqueado.
        </p>
        <BaseTextarea
          v-model="editForm.action"
          label="Acción"
          :rows="2"
          :disabled="isApproved"
          :error="editErrors.action"
          required
        />
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <BaseInput
            v-model="editForm.responsible"
            label="Responsable"
            placeholder="Nombre del responsable"
            :disabled="isApproved"
            :error="editErrors.responsible"
            required
          />
          <BaseSelect
            v-model="editForm.priority"
            label="Prioridad"
            :options="priorityOptions"
            :searchable="false"
            :disabled="isApproved"
          />
          <BaseInput
            v-model="editForm.dueDate"
            label="Fecha límite"
            type="date"
            :min="today"
            :disabled="isApproved"
            :error="editErrors.dueDate"
            required
          />
          <BaseSelect
            v-model="editForm.status"
            label="Estado"
            :options="statusOptionsFor(editForm.status, editForm.dueDate)"
            :searchable="false"
          />
        </div>
        <p
          v-if="overdueReset"
          class="px-3 py-2 rounded-lg bg-blue-50 border border-blue-200 text-sm text-blue-900 dark:bg-blue-950 dark:border-blue-800 dark:text-blue-200"
          data-test="overdue-reset"
        >
          La acción estaba <strong>Vencida</strong>. Con la nueva fecha pasa a
          <strong>{{ planStatusLabel(editForm.status) }}</strong
          >.
        </p>
        <BaseTextarea
          v-model="editForm.comments"
          label="Comentarios (opcional)"
          placeholder="Vacío para borrar el comentario"
          :rows="3"
          :disabled="isApproved"
          :error="editErrors.comments"
        />
        <p v-if="editError" class="text-sm text-red-600 dark:text-red-400" role="alert">
          {{ editError }}
        </p>
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showEditModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="savingEdit" @click="saveEdit">
          {{ isApproved ? 'Guardar estado' : 'Guardar cambios' }}
        </BaseButton>
      </template>
    </BaseModal>

    <BaseModal v-model="showSuggestModal" title="Sugerencias de Mejora" size="lg">
      <div v-if="loadingSuggestions" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
      </div>
      <div
        v-else-if="suggestions.length === 0"
        class="text-center py-8 text-slate-500 dark:text-slate-400"
      >
        <SparklesIcon class="w-8 h-8 mx-auto mb-2 text-slate-300" />
        <p>No hay brechas pendientes para sugerir. Complete la evaluación o revise el catálogo.</p>
      </div>
      <div v-else class="max-h-[60vh] overflow-y-auto space-y-3 -mr-2 pr-2">
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Generadas a partir de las respuestas por debajo del nivel objetivo, ordenadas por
          prioridad. Revise, edite y seleccione las que quiera crear como acciones del plan.
        </p>
        <div
          v-for="s in suggestions"
          :key="s.controlId"
          class="border border-slate-200 dark:border-slate-700 rounded-lg p-3"
          :class="
            s.selected ? 'bg-white dark:bg-slate-800' : 'bg-slate-50 dark:bg-slate-900 opacity-60'
          "
        >
          <div class="flex items-start gap-3">
            <input
              v-model="s.selected"
              type="checkbox"
              class="mt-1 w-4 h-4 rounded border-slate-300 dark:border-slate-500 text-blue-600 focus:ring-blue-500 dark:text-blue-400"
            />
            <div class="flex-1 space-y-2">
              <div class="flex items-center justify-between flex-wrap gap-2">
                <span class="text-xs text-slate-500 dark:text-slate-400">
                  {{ s.functionName }} › {{ s.categoryName }} › {{ s.subcategoryName }} ({{
                    s.controlCode
                  }})
                </span>
                <span class="text-xs font-medium text-slate-600 dark:text-slate-300">
                  Nivel {{ s.targetLevel }}
                </span>
              </div>
              <BaseTextarea v-model="s.action" :rows="2" />

              <button
                type="button"
                class="inline-flex items-center gap-1 text-xs font-medium text-indigo-600 hover:text-indigo-700 disabled:opacity-50 dark:text-indigo-400 dark:hover:text-indigo-300"
                :disabled="loadingTipsFor === s.controlId"
                @click="loadTips(s)"
              >
                <SparklesIcon class="w-3.5 h-3.5" />
                {{
                  loadingTipsFor === s.controlId
                    ? 'Generando sugerencia…'
                    : tipsByControl[s.controlId]
                      ? 'Regenerar sugerencia con IA'
                      : '¿Cómo puedo solucionar esto? (sugerencia con IA)'
                }}
              </button>
              <div
                v-if="tipsByControl[s.controlId]"
                class="p-3 rounded-lg bg-indigo-50 border border-indigo-100 space-y-1.5 dark:bg-indigo-950"
              >
                <p
                  v-if="tipsByControl[s.controlId].summary"
                  class="text-xs font-medium text-indigo-900 dark:text-indigo-400"
                >
                  {{ tipsByControl[s.controlId].summary }}
                </p>
                <ul class="text-xs text-indigo-800 list-disc pl-4 space-y-0.5 dark:text-indigo-400">
                  <li v-for="(tip, i) in tipsByControl[s.controlId].tips" :key="i">
                    {{ tip }}
                  </li>
                </ul>
              </div>

              <div class="grid grid-cols-1 sm:grid-cols-3 gap-2">
                <BaseInput v-model="s.responsible" placeholder="Responsable" />
                <BaseSelect v-model="s.priority" :options="priorityOptions" />
                <input
                  v-model="s.suggestedDueDate"
                  type="date"
                  :min="today"
                  aria-label="Fecha límite"
                  class="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 dark:border-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <BaseButton variant="secondary" @click="showSuggestModal = false"> Cerrar </BaseButton>
        <BaseButton
          v-if="suggestions.length > 0"
          variant="primary"
          :loading="creatingSuggestions"
          :disabled="selectedCount === 0"
          @click="acceptSuggestions"
        >
          Crear seleccionadas ({{ selectedCount }})
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, reactive, onMounted, watch } from 'vue';
  import { useRoute } from 'vue-router';
  import { format } from 'date-fns';
  import {
    improvementService,
    evaluationsService,
    planDeadlinesService,
    reportsService,
  } from '@/services/resources';
  import { DEFAULT_PLAN_DEADLINES, defaultDueDate, monthsFor } from '@/utils/planDeadlines';
  import { useAuthStore } from '@/stores/auth';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { blobErrorMessage, saveBlob } from '@/utils/files';
  import { useNotification } from '@/composables/useUtils';
  import StatsCard from '@/components/common/StatsCard.vue';
  import ProgressBar from '@/components/common/ProgressBar.vue';
  import { planProgress, type Progress } from '@/utils/planProgress';
  import BaseButton from '@/components/common/BaseButton.vue';
  import MaturityGapSection, {
    type GapActionDraft,
  } from '@/components/improvement/MaturityGapSection.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import {
    ArrowLeftIcon,
    ArrowDownTrayIcon,
    ChevronDownIcon,
    PlusIcon,
    PencilSquareIcon,
    SparklesIcon,
    ClockIcon,
    PlayIcon,
    CheckCircleIcon,
    ExclamationCircleIcon,
  } from '@heroicons/vue/24/outline';
  import type {
    ImprovementPlan,
    PlanDeadlines,
    MaturityResult,
    SuggestedImprovement,
    RemediationTips,
  } from '@/types';

  interface EditableSuggestion extends SuggestedImprovement {
    selected: boolean;
    responsible: string;
  }

  const route = useRoute();
  const notification = useNotification();
  const authStore = useAuthStore();

  const evaluationId = route.params.evaluationId as string;

  // Fecha local de hoy (AAAA-MM-DD): no se aceptan fechas límite anteriores (RF-PLN-04).
  const today = format(new Date(), 'yyyy-MM-dd');

  // Mismos roles que el @PreAuthorize de crear/editar en ImprovementPlanController: el resto ve el
  // plan en solo lectura (antes veía los botones y recibía un 403 sin aviso).
  const canManage = computed(() => authStore.hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE'));

  // Estado de la evaluación: en DRAFT / ARCHIVED el plan no está activo; en APPROVED solo se cambia
  // el estado de cada acción (mismas reglas que ImprovementPlanService). Si no se pudo cargar, la
  // vista no bloquea nada y el backend sigue validando.
  const evaluationStatus = ref<string | null>(null);
  const isApproved = computed(() => evaluationStatus.value === 'APPROVED');
  const isClosed = computed(
    () => evaluationStatus.value === 'DRAFT' || evaluationStatus.value === 'ARCHIVED',
  );
  const canEditStatus = computed(() => canManage.value && !isClosed.value);
  const canEditFully = computed(() => canEditStatus.value && !isApproved.value);

  async function fetchEvaluationStatus() {
    try {
      const { data } = await evaluationsService.getById(evaluationId);
      evaluationStatus.value = data.status;
    } catch (err) {
      console.error(err);
    }
  }
  const loading = ref(true);
  const saving = ref(false);
  const showCreateModal = ref(false);
  const plans = ref<ImprovementPlan[]>([]);
  const results = ref<MaturityResult[]>([]);
  const plannedControlIds = computed(
    () => new Set(plans.value.map((p) => p.controlId).filter(Boolean)),
  );
  const draftControlId = ref<string | null>(null);

  const showSuggestModal = ref(false);
  const loadingSuggestions = ref(false);
  const creatingSuggestions = ref(false);
  const suggestions = ref<EditableSuggestion[]>([]);
  const selectedCount = computed(() => suggestions.value.filter((s) => s.selected).length);

  // Sugerencia de remediación (LLM) por control, pedida on-demand -- nunca se generan para toda
  // la lista de brechas junta (sería lento y cargaría a Ollama con generaciones que el usuario ni
  // llegó a pedir). Se cachea por controlId dentro de esta sesión del modal.
  const tipsByControl = reactive<Record<string, RemediationTips>>({});
  const loadingTipsFor = ref<string | null>(null);

  async function loadTips(s: EditableSuggestion) {
    loadingTipsFor.value = s.controlId;
    try {
      const { data } = await improvementService.getRemediationTips({
        controlCode: s.controlCode,
        description: s.action,
        functionName: s.functionName,
        categoryName: s.categoryName,
        subcategoryName: s.subcategoryName,
        currentLevel: s.currentLevel,
        targetLevel: s.targetLevel,
      });
      tipsByControl[s.controlId] = data;
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo generar la sugerencia'));
    } finally {
      loadingTipsFor.value = null;
    }
  }

  const newPlan = reactive({
    action: '',
    responsible: '',
    priority: 'MEDIUM' as ImprovementPlan['priority'],
    dueDate: '',
    comments: '',
  });

  // RF-PLN-03: la fecha límite se precarga con el plazo por defecto de la prioridad y se recalcula
  // al cambiarla, mientras el usuario no la haya editado a mano. Si no se pudieron leer los plazos
  // configurados, se usan los del requerimiento (el backend aplica los vigentes igual).
  const planDeadlines = ref<PlanDeadlines>({ ...DEFAULT_PLAN_DEADLINES });
  const dueDateTouched = ref(false);
  const suggestedMonths = computed(() => monthsFor(newPlan.priority, planDeadlines.value));

  async function fetchPlanDeadlines() {
    try {
      const { data } = await planDeadlinesService.get();
      planDeadlines.value = data;
    } catch (err) {
      console.error(err);
    }
  }

  function openCreateModal() {
    dueDateTouched.value = false;
    newPlan.dueDate = defaultDueDate(newPlan.priority, planDeadlines.value);
    showCreateModal.value = true;
  }

  watch(
    () => newPlan.priority,
    (priority) => {
      if (!dueDateTouched.value) {
        newPlan.dueDate = defaultDueDate(priority, planDeadlines.value);
      }
    },
  );

  const formErrors = reactive({
    action: '',
    responsible: '',
    dueDate: '',
    comments: '',
  });

  // El mismo tope que valida el backend (@Size en los DTO del plan de mejora).
  const MAX_COMMENTS = 2000;
  const commentsTooLong = `Los comentarios no pueden superar los ${MAX_COMMENTS} caracteres`;

  // Estados que el usuario puede elegir (RF-PLN-04). «Vencida» la asigna el sistema: aparece solo
  // como valor actual, deshabilitada. Con la fecha ya vencida no se puede volver a Pendiente / En
  // curso (la próxima carga la marcaría vencida otra vez): queda Realizado o cambiar la fecha.
  function statusOptionsFor(current: string, dueDate: string | null | undefined) {
    const expired = !!dueDate && dueDate < today;
    const options: { value: string; label: string; disabled?: boolean }[] = [
      { value: 'PENDING', label: 'Pendiente', disabled: expired && current !== 'PENDING' },
      { value: 'IN_PROGRESS', label: 'En curso', disabled: expired && current !== 'IN_PROGRESS' },
      { value: 'COMPLETED', label: 'Realizado' },
    ];
    if (current === 'OVERDUE') {
      options.push({ value: 'OVERDUE', label: 'Vencida', disabled: true });
    }
    return options;
  }

  const priorityOptions = [
    { value: 'HIGH', label: 'Alta' },
    { value: 'MEDIUM', label: 'Media' },
    { value: 'LOW', label: 'Baja' },
  ];

  // Avance de remediación (RF-PLN-06): se recalcula solo cuando cambia la lista de planes, así que se
  // actualiza al crear, editar o completar una acción sin recargar la página.
  const progress = computed(() => planProgress(plans.value));
  const priorityRows = [
    { key: 'HIGH', label: 'Alta', barClass: 'bg-red-600 dark:bg-red-500' },
    { key: 'MEDIUM', label: 'Media', barClass: 'bg-amber-600 dark:bg-amber-500' },
    { key: 'LOW', label: 'Baja', barClass: 'bg-emerald-600 dark:bg-emerald-500' },
  ] as const;

  function progressDetail(p: Progress) {
    return p.total === 0 ? '' : `${p.closed} de ${p.total} cerradas`;
  }

  function plansByStatus(status: string) {
    return plans.value.filter((p) => p.status === status).length;
  }

  function getPriorityLabel(p: string) {
    return { HIGH: 'Alta', MEDIUM: 'Media', LOW: 'Baja' }[p] || p;
  }

  function priorityClass(p: string) {
    return (
      {
        HIGH: 'bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300',
        MEDIUM: 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300',
        LOW: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300',
      }[p] || 'bg-slate-50 dark:bg-slate-900 text-slate-700 dark:text-slate-200'
    );
  }

  function planStatusLabel(s: string) {
    return (
      {
        PENDING: 'Pendiente',
        IN_PROGRESS: 'En curso',
        COMPLETED: 'Realizado',
        OVERDUE: 'Vencida',
      }[s] || s
    );
  }

  function planStatusClass(s: string) {
    return (
      {
        PENDING: 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
        IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300',
        COMPLETED: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
        OVERDUE: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300',
      }[s] || 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300'
    );
  }

  async function fetchPlans() {
    loading.value = true;
    try {
      const { data } = await improvementService.getByEvaluation(evaluationId);
      plans.value = data;
    } catch (err) {
      console.error(err);
    } finally {
      loading.value = false;
    }
  }

  const exportMenuOpen = ref(false);
  const exporting = ref<'pdf' | 'excel' | null>(null);

  async function exportPlan(format: 'pdf' | 'excel') {
    exportMenuOpen.value = false;
    exporting.value = format;
    try {
      if (format === 'pdf') {
        const { data } = await reportsService.exportImprovementPlanPDF(evaluationId);
        saveBlob(new Blob([data], { type: 'application/pdf' }), `plan-mejora-${evaluationId}.pdf`);
      } else {
        const { data } = await reportsService.exportImprovementPlanExcel(evaluationId);
        saveBlob(
          new Blob([data], {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
          `plan-mejora-${evaluationId}.xlsx`,
        );
      }
      notification.success(`Plan de mejora exportado a ${format === 'pdf' ? 'PDF' : 'Excel'}`);
    } catch (err) {
      notification.error(await blobErrorMessage(err, 'No se pudo generar el archivo'));
    } finally {
      exporting.value = null;
    }
  }

  async function fetchResults() {
    try {
      const { data } = await evaluationsService.getResults(evaluationId);
      results.value = data;
    } catch (err) {
      console.error(err);
      results.value = [];
    }
  }

  function openCreateFromGap(draft: GapActionDraft) {
    newPlan.action = draft.action;
    newPlan.priority = draft.priority;
    draftControlId.value = draft.controlId;
    showCreateModal.value = true;
  }

  watch(showCreateModal, (open) => {
    if (!open) draftControlId.value = null;
  });

  async function handleCreate() {
    Object.keys(formErrors).forEach((k) => (formErrors[k as keyof typeof formErrors] = ''));
    if (!newPlan.action) {
      formErrors.action = 'La acción es requerida';
      return;
    }
    if (!newPlan.responsible) {
      formErrors.responsible = 'El responsable es requerido';
      return;
    }
    if (!newPlan.dueDate) {
      formErrors.dueDate = 'La fecha es requerida';
      return;
    }
    if (newPlan.dueDate < today) {
      formErrors.dueDate = PAST_DATE;
      return;
    }
    if (newPlan.comments.length > MAX_COMMENTS) {
      formErrors.comments = commentsTooLong;
      return;
    }

    saving.value = true;
    try {
      await improvementService.create({
        evaluationId,
        ...newPlan,
        ...(draftControlId.value ? { controlId: draftControlId.value } : {}),
        status: 'PENDING',
      });
      notification.success('Acción de mejora creada');
      showCreateModal.value = false;
      newPlan.action = '';
      newPlan.responsible = '';
      newPlan.priority = 'MEDIUM';
      newPlan.dueDate = '';
      newPlan.comments = '';
      await fetchPlans();
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al crear la acción'));
    } finally {
      saving.value = false;
    }
  }

  async function updatePlanStatus(id: string, status: ImprovementPlan['status']) {
    try {
      await improvementService.update(id, { status });
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo cambiar el estado'));
    }
    // Se recarga también si falló: el selector vuelve a mostrar el estado real.
    await fetchPlans();
  }

  // RF-PLN-04 (y RF-PLN-02 para los comentarios): modal «Editar acción». Solo viajan los campos
  // que cambiaron; un comentario vacío viaja como '' y borra el comentario (null = sin cambios).
  const PAST_DATE = 'La fecha límite no puede ser anterior a hoy';
  const showEditModal = ref(false);
  const editPlan = ref<ImprovementPlan | null>(null);
  const editForm = reactive({
    action: '',
    responsible: '',
    priority: 'MEDIUM' as ImprovementPlan['priority'],
    dueDate: '',
    status: 'PENDING' as ImprovementPlan['status'],
    comments: '',
  });
  const editErrors = reactive({ action: '', responsible: '', dueDate: '', comments: '' });
  const editError = ref('');
  const savingEdit = ref(false);

  function openEditor(plan: ImprovementPlan) {
    editPlan.value = plan;
    editForm.action = plan.action ?? '';
    editForm.responsible = plan.responsible ?? '';
    editForm.priority = plan.priority;
    editForm.dueDate = plan.dueDate ?? '';
    editForm.status = plan.status;
    editForm.comments = plan.comments ?? '';
    Object.keys(editErrors).forEach((k) => (editErrors[k as keyof typeof editErrors] = ''));
    editError.value = '';
    showEditModal.value = true;
  }

  // Una acción vencida a la que se le pone una fecha vigente vuelve a Pendiente (el usuario puede
  // elegir En curso antes de guardar); si la fecha vuelve a la original, recupera «Vencida».
  watch(
    () => editForm.dueDate,
    (dueDate) => {
      if (editPlan.value?.status !== 'OVERDUE') return;
      if (dueDate !== editPlan.value.dueDate && dueDate >= today) {
        if (editForm.status === 'OVERDUE') editForm.status = 'PENDING';
      } else if (editForm.status === 'PENDING') {
        editForm.status = 'OVERDUE';
      }
    },
  );

  const overdueReset = computed(
    () =>
      editPlan.value?.status === 'OVERDUE' &&
      editForm.dueDate !== editPlan.value.dueDate &&
      editForm.status !== 'OVERDUE',
  );

  function editChanges(plan: ImprovementPlan): Partial<ImprovementPlan> {
    const changes: Partial<ImprovementPlan> = {};
    if (editForm.status !== plan.status) changes.status = editForm.status;
    if (isApproved.value) return changes;
    if (editForm.action.trim() !== (plan.action ?? '')) changes.action = editForm.action.trim();
    if (editForm.responsible.trim() !== (plan.responsible ?? '')) {
      changes.responsible = editForm.responsible.trim();
    }
    if (editForm.priority !== plan.priority) changes.priority = editForm.priority;
    if (editForm.dueDate !== (plan.dueDate ?? '')) changes.dueDate = editForm.dueDate;
    if (editForm.comments !== (plan.comments ?? '')) changes.comments = editForm.comments;
    return changes;
  }

  function validateEdit(plan: ImprovementPlan): boolean {
    Object.keys(editErrors).forEach((k) => (editErrors[k as keyof typeof editErrors] = ''));
    if (isApproved.value) return true;
    if (!editForm.action.trim()) editErrors.action = 'La acción es requerida';
    if (!editForm.responsible.trim()) editErrors.responsible = 'El responsable es requerido';
    if (!editForm.dueDate) editErrors.dueDate = 'La fecha es requerida';
    else if (editForm.dueDate !== plan.dueDate && editForm.dueDate < today) {
      editErrors.dueDate = PAST_DATE;
    }
    if (editForm.comments.length > MAX_COMMENTS) editErrors.comments = commentsTooLong;
    return !Object.values(editErrors).some(Boolean);
  }

  async function saveEdit() {
    const plan = editPlan.value;
    if (!plan || !validateEdit(plan)) return;
    const changes = editChanges(plan);
    if (Object.keys(changes).length === 0) {
      showEditModal.value = false;
      return;
    }
    savingEdit.value = true;
    editError.value = '';
    try {
      await improvementService.update(plan.id, changes);
      notification.success('Acción actualizada');
      showEditModal.value = false;
      await fetchPlans();
    } catch (err) {
      editError.value = getErrorMessage(err, 'No se pudo guardar la acción');
    } finally {
      savingEdit.value = false;
    }
  }

  async function openSuggestModal() {
    showSuggestModal.value = true;
    loadingSuggestions.value = true;
    Object.keys(tipsByControl).forEach((key) => delete tipsByControl[key]);
    try {
      const { data } = await improvementService.getSuggestions(evaluationId);
      suggestions.value = data.map((s) => ({ ...s, selected: true, responsible: '' }));
    } catch (err) {
      notification.error(getErrorMessage(err, 'Error al generar sugerencias'));
      showSuggestModal.value = false;
    } finally {
      loadingSuggestions.value = false;
    }
  }

  async function acceptSuggestions() {
    const selected = suggestions.value.filter((s) => s.selected);
    if (selected.length === 0) return;
    if (selected.some((s) => s.suggestedDueDate && s.suggestedDueDate < today)) {
      notification.error(PAST_DATE);
      return;
    }

    creatingSuggestions.value = true;
    try {
      const results = await Promise.allSettled(
        selected.map((s) =>
          improvementService.create({
            evaluationId,
            controlId: s.controlId,
            action: s.action,
            responsible: s.responsible,
            priority: s.priority,
            dueDate: s.suggestedDueDate,
          }),
        ),
      );
      const failed = results.filter((r) => r.status === 'rejected').length;
      const created = results.length - failed;
      if (created > 0) {
        notification.success(`${created} acción(es) de mejora creada(s)`);
      }
      if (failed > 0) {
        notification.error(`${failed} sugerencia(s) no se pudieron crear`);
      }
      showSuggestModal.value = false;
      suggestions.value = [];
      await fetchPlans();
    } finally {
      creatingSuggestions.value = false;
    }
  }

  onMounted(() => {
    fetchPlans();
    fetchEvaluationStatus();
    fetchPlanDeadlines();
    fetchResults();
  });
</script>
