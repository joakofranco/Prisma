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
        <span
          v-if="aiActiveCount > 0"
          class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-indigo-50 text-indigo-700 border border-indigo-100 dark:bg-indigo-950 dark:text-indigo-300 dark:border-indigo-900"
          role="status"
          data-testid="ai-tasks"
        >
          <SparklesIcon class="w-3.5 h-3.5" />
          Tareas de IA: {{ aiActiveCount }} en curso
        </span>
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
              <div class="flex items-center gap-1 shrink-0">
                <AiTraceButton :load="() => aiTraceService.getPlanTrace(plan.id)" />
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
        <div
          class="flex flex-wrap items-center gap-3 p-3 rounded-lg bg-indigo-50 border border-indigo-100 dark:bg-indigo-950 dark:border-indigo-900"
          data-testid="batch-bar"
        >
          <BaseButton
            variant="secondary"
            :disabled="selectedForBatch.length === 0 || batchRunning"
            data-testid="generate-batch"
            @click="generateSelected"
          >
            <SparklesIcon class="w-4 h-4" />
            Generar con IA para las seleccionadas ({{ selectedForBatch.length }})
          </BaseButton>
          <span
            v-if="batchProgress.total > 0"
            class="text-xs text-indigo-800 dark:text-indigo-300"
            role="status"
            data-testid="batch-progress"
          >
            {{ batchProgress.done }} de {{ batchProgress.total }} listas<template
              v-if="batchProgress.failed > 0"
              >, {{ batchProgress.failed }} con error</template
            >
          </span>
          <button
            v-if="batchProgress.queued > 0"
            type="button"
            class="text-xs text-slate-600 underline hover:text-slate-800 dark:text-slate-300"
            data-testid="cancel-batch"
            @click="cancelBatch"
          >
            Cancelar lote
          </button>
        </div>
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Generadas a partir de las respuestas por debajo del nivel objetivo, ordenadas por
          prioridad. Revise, edite y seleccione las que quiera crear como acciones del plan.
        </p>
        <div
          v-for="s in suggestions"
          :key="sKey(s)"
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
              <p
                v-if="s.subcategoryNames && s.subcategoryNames.length > 1"
                class="text-xs text-slate-500 dark:text-slate-400"
                data-testid="also-in"
              >
                También cuenta en: {{ s.subcategoryNames.slice(1).join(', ') }}
              </p>
              <BaseTextarea v-model="s.action" :rows="2" />

              <div class="flex items-center gap-3 flex-wrap">
                <button
                  type="button"
                  class="inline-flex items-center gap-1 text-xs font-medium text-indigo-600 hover:text-indigo-700 disabled:opacity-50 dark:text-indigo-400 dark:hover:text-indigo-300"
                  :disabled="isJobActive(s)"
                  data-testid="request-tips"
                  @click="loadTips(s)"
                >
                  <SparklesIcon class="w-3.5 h-3.5" />
                  {{
                    isJobActive(s)
                      ? jobProgressLabel(jobsByKey[sKey(s)])
                      : tipsByKey[sKey(s)]
                        ? 'Regenerar sugerencia con IA'
                        : '¿Cómo puedo solucionar esto? (sugerencia con IA)'
                  }}
                </button>
                <button
                  v-if="jobsByKey[sKey(s)]?.status === 'QUEUED'"
                  type="button"
                  class="text-xs text-slate-500 underline hover:text-slate-700 dark:text-slate-400"
                  data-testid="cancel-tips"
                  @click="cancelJob(s)"
                >
                  Cancelar
                </button>
              </div>
              <p
                v-if="jobsByKey[sKey(s)]?.error"
                class="text-xs text-red-700 dark:text-red-400"
                role="alert"
                data-testid="tips-error"
              >
                {{ jobsByKey[sKey(s)].error }}
              </p>
              <div
                v-if="tipsByKey[sKey(s)]"
                class="p-3 rounded-lg bg-indigo-50 border border-indigo-100 space-y-1.5 dark:bg-indigo-950"
              >
                <p
                  v-if="tipsByKey[sKey(s)].summary"
                  class="text-xs font-medium text-indigo-900 dark:text-indigo-400"
                >
                  {{ tipsByKey[sKey(s)].summary }}
                </p>
                <ul class="text-xs text-indigo-800 list-disc pl-4 space-y-0.5 dark:text-indigo-400">
                  <li v-for="(tip, i) in tipsByKey[sKey(s)].tips" :key="i">
                    {{ tip }}
                  </li>
                </ul>
                <button
                  v-if="tipsByKey[sKey(s)].generated !== false"
                  type="button"
                  class="text-xs font-medium text-indigo-700 underline hover:text-indigo-900 dark:text-indigo-300"
                  data-testid="use-tips"
                  @click="useTips(s)"
                >
                  Usar en la acción
                </button>
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
  import { ref, computed, reactive, onMounted, onBeforeUnmount, watch } from 'vue';
  import { useRoute } from 'vue-router';
  import { format } from 'date-fns';
  import {
    improvementService,
    evaluationsService,
    planDeadlinesService,
    reportsService,
    aiJobsService,
    aiTraceService,
  } from '@/services/resources';
  import { DEFAULT_PLAN_DEADLINES, defaultDueDate, monthsFor } from '@/utils/planDeadlines';
  import { useAuthStore } from '@/stores/auth';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { blobErrorMessage, saveBlob } from '@/utils/files';
  import {
    nextPollDelay,
    isActive,
    jobProgressLabel,
    jobErrorMessage,
    loadStoredJobs,
    saveStoredJobs,
  } from '@/utils/aiJobs';
  import { useNotification } from '@/composables/useUtils';
  import StatsCard from '@/components/common/StatsCard.vue';
  import ProgressBar from '@/components/common/ProgressBar.vue';
  import { planProgress, type Progress } from '@/utils/planProgress';
  import BaseButton from '@/components/common/BaseButton.vue';
  import MaturityGapSection, {
    type GapActionDraft,
  } from '@/components/improvement/MaturityGapSection.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import AiTraceButton from '@/components/ai/AiTraceButton.vue';
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
    AiJob,
    AiJobStatus,
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

  // Sugerencia de remediación (LLM), pedida on-demand -- nunca se generan para toda la lista de
  // brechas junta (sería lento y cargaría a Ollama con generaciones que el usuario ni llegó a
  // pedir). Se indexa por sugerencia (control + subcategoría): un mismo control puede aparecer
  // varias veces, una por subcategoría, y cada tarjeta lleva su propia guía.
  //
  // La generación es un TRABAJO ASÍNCRONO (cola de IA): el pedido responde enseguida con el id del
  // trabajo y acá se lo consulta cada 3-8 s hasta que termina. El servidor guarda la guía y el
  // feedback (usada / editada / rechazada): el frontend ya no lo registra. El id del trabajo se
  // guarda en localStorage para retomarlo si se cierra el modal o se recarga la página.
  const tipsByKey = reactive<Record<string, RemediationTips>>({});
  interface GuideJobState {
    jobId: string;
    status: AiJobStatus;
    queuePosition: number | null;
    error: string | null;
    /** Lote al que pertenece (si se pidió como lote). */
    batchId?: string | null;
  }
  const jobsByKey = reactive<Record<string, GuideJobState>>({});
  // Texto que «Usar en la acción» copió a la acción de cada sugerencia: sirve de referencia para
  // saber, al crearla, si la persona lo editó.
  const usedText = reactive<Record<string, string>>({});
  // Trabajos de IA propios en curso (indicador «Tareas de IA»).
  const aiActiveCount = ref(0);

  const pollTimers = new Map<string, ReturnType<typeof setTimeout>>();

  function sKey(s: SuggestedImprovement) {
    return `${s.controlId}:${s.subcategoryId}`;
  }

  function isJobActive(s: SuggestedImprovement) {
    return isActive(jobsByKey[sKey(s)]?.status);
  }

  function tipsToText(t: RemediationTips) {
    const steps = t.tips.map((tip, i) => `${i + 1}. ${tip}`).join('\n');
    return [t.summary, steps].filter((p) => p && p.trim()).join('\n');
  }

  function persistJobs() {
    const stored: Record<string, string> = {};
    for (const [key, job] of Object.entries(jobsByKey)) {
      if (job.status === 'QUEUED' || job.status === 'RUNNING' || job.status === 'SUCCEEDED') {
        stored[key] = job.jobId;
      }
    }
    saveStoredJobs(evaluationId, stored);
  }

  async function refreshActiveCount() {
    try {
      const { data } = await aiJobsService.listMine();
      aiActiveCount.value = data.filter((j) => isActive(j.status)).length;
    } catch {
      // el indicador es informativo: si falla no molesta
    }
  }

  function stopPolling() {
    pollTimers.forEach((timer) => clearTimeout(timer));
    pollTimers.clear();
    stopBatchPolling();
  }

  function dropJob(key: string) {
    const timer = pollTimers.get(key);
    if (timer) clearTimeout(timer);
    pollTimers.delete(key);
    delete jobsByKey[key];
    persistJobs();
  }

  // Refleja el estado del trabajo en la tarjeta; devuelve true si sigue activo.
  function applyJob(key: string, job: AiJob): boolean {
    if (job.status === 'CANCELLED') {
      dropJob(key);
      return false;
    }
    jobsByKey[key] = {
      jobId: job.id,
      status: job.status,
      queuePosition: job.queuePosition,
      batchId: job.batchId ?? jobsByKey[key]?.batchId ?? null,
      error:
        job.status === 'FAILED'
          ? jobErrorMessage(job)
          : job.status === 'EXPIRED'
            ? 'La sugerencia venció. Volvé a pedirla.'
            : null,
    };
    if (job.status === 'SUCCEEDED' && job.guide) {
      tipsByKey[key] = {
        summary: job.guide.summary,
        tips: job.guide.tips,
        generated: true,
        guideId: job.guide.id,
      };
      delete usedText[key];
    }
    persistJobs();
    return isActive(job.status);
  }

  function schedulePoll(key: string, attempt: number) {
    const timer = setTimeout(async () => {
      pollTimers.delete(key);
      const state = jobsByKey[key];
      if (!state) return;
      try {
        const { data } = await aiJobsService.get(state.jobId);
        if (applyJob(key, data)) schedulePoll(key, attempt + 1);
      } catch (err) {
        const status = (err as { response?: { status?: number } }).response?.status;
        if (status === 404 || status === 403) {
          // El trabajo ya no existe o no es accesible: se descarta y se puede volver a pedir.
          dropJob(key);
        } else {
          // Falla transitoria de red o del servidor: se sigue intentando.
          schedulePoll(key, attempt + 1);
        }
      } finally {
        void refreshActiveCount();
      }
    }, nextPollDelay(attempt));
    pollTimers.set(key, timer);
  }

  async function loadTips(s: EditableSuggestion) {
    const key = sKey(s);
    if (isJobActive(s)) return;
    try {
      const { data } = await improvementService.requestTipsAsync({
        controlId: s.controlId,
        evaluationId,
        functionName: s.functionName,
        categoryName: s.categoryName,
        subcategoryName: s.subcategoryName,
        currentLevel: s.currentLevel,
        targetLevel: s.targetLevel,
      });
      jobsByKey[key] = { jobId: data.jobId, status: data.status, queuePosition: null, error: null };
      persistJobs();
      schedulePoll(key, 0);
      void refreshActiveCount();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo generar la sugerencia'));
    }
  }

  async function cancelJob(s: EditableSuggestion) {
    const key = sKey(s);
    const state = jobsByKey[key];
    if (!state) return;
    try {
      await aiJobsService.cancel(state.jobId);
      dropJob(key);
      void refreshActiveCount();
    } catch (err) {
      // Si otra réplica ya lo tomó no se puede cortar: sigue su camino y se lo sigue consultando.
      notification.error(getErrorMessage(err, 'No se pudo cancelar la sugerencia'));
    }
  }

  // Retoma los trabajos guardados de esta evaluación (modal cerrado y vuelto a abrir, o página
  // recargada): los terminados muestran su guía, los activos siguen consultándose.
  async function resumeJobs() {
    const stored = loadStoredJobs(evaluationId);
    const known = new Set(suggestions.value.map((s) => sKey(s)));
    await Promise.all(
      Object.entries(stored)
        .filter(([key]) => known.has(key))
        .map(async ([key, jobId]) => {
          try {
            const { data } = await aiJobsService.get(jobId);
            if (applyJob(key, data)) {
              // Un trabajo de lote se consulta junto con los demás del lote.
              if (data.batchId) scheduleBatchPoll(0);
              else schedulePoll(key, 0);
            }
          } catch {
            // venció, se borró o no es accesible: se olvida
            delete jobsByKey[key];
            persistJobs();
          }
        }),
    );
  }

  // ---- Lote de guías (F3) ----------------------------------------------------------------
  // «Generar con IA para las seleccionadas»: una guía por control, con prioridad de lote (una guía
  // pedida suelta pasa antes). El avance se consulta de una vez para todos los trabajos del lote.
  // Las filas que ya tienen guía o un trabajo activo se saltean.
  const selectedForBatch = computed(() =>
    suggestions.value.filter((s) => s.selected && !isJobActive(s) && !tipsByKey[sKey(s)]),
  );

  // Avance del lote: los trabajos de las filas que se pidieron como lote.
  const batchProgress = computed(() => {
    const batched = Object.values(jobsByKey).filter((j) => j.batchId);
    return {
      total: batched.length,
      done: batched.filter((j) => j.status === 'SUCCEEDED').length,
      failed: batched.filter((j) => j.status === 'FAILED' || j.status === 'EXPIRED').length,
      queued: batched.filter((j) => j.status === 'QUEUED').length,
      active: batched.filter((j) => isActive(j.status)).length,
    };
  });
  const batchRunning = computed(() => batchProgress.value.active > 0);

  let batchTimer: ReturnType<typeof setTimeout> | null = null;

  function stopBatchPolling() {
    if (batchTimer) clearTimeout(batchTimer);
    batchTimer = null;
  }

  function scheduleBatchPoll(attempt: number) {
    stopBatchPolling();
    batchTimer = setTimeout(async () => {
      batchTimer = null;
      const active = Object.entries(jobsByKey).filter(([, j]) => j.batchId && isActive(j.status));
      if (active.length === 0) return;
      try {
        const { data } = await aiJobsService.getMany([...new Set(active.map(([, j]) => j.jobId))]);
        for (const [key, state] of active) {
          const job = data.find((j) => j.id === state.jobId);
          if (job) applyJob(key, job);
        }
      } catch {
        // falla transitoria: se sigue intentando
      } finally {
        void refreshActiveCount();
      }
      if (Object.values(jobsByKey).some((j) => j.batchId && isActive(j.status))) {
        scheduleBatchPoll(attempt + 1);
      }
    }, nextPollDelay(attempt));
  }

  async function generateSelected() {
    const rows = selectedForBatch.value;
    if (rows.length === 0) return;
    try {
      const { data } = await improvementService.requestTipsBatch(
        rows.map((s) => ({
          controlId: s.controlId,
          evaluationId,
          functionName: s.functionName,
          categoryName: s.categoryName,
          subcategoryName: s.subcategoryName,
          currentLevel: s.currentLevel,
          targetLevel: s.targetLevel,
        })),
      );
      // Un control puede aparecer en varias filas (una por subcategoría): comparten el trabajo.
      for (const row of rows) {
        const item = data.jobs.find((j) => j.controlId === row.controlId);
        if (!item) continue;
        jobsByKey[sKey(row)] = {
          jobId: item.jobId,
          status: item.status,
          queuePosition: null,
          error: null,
          batchId: data.batchId,
        };
      }
      persistJobs();
      scheduleBatchPoll(0);
      void refreshActiveCount();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo generar el lote de sugerencias'));
    }
  }

  async function cancelBatch() {
    const batchId = Object.values(jobsByKey).find((j) => j.batchId)?.batchId;
    if (!batchId) return;
    try {
      await aiJobsService.cancelBatch(batchId);
      // Lo que seguía en cola queda cancelado; lo que ya corría termina: se refresca el estado.
      const ids = [
        ...new Set(
          Object.values(jobsByKey)
            .filter((j) => j.batchId)
            .map((j) => j.jobId),
        ),
      ];
      const { data } = await aiJobsService.getMany(ids);
      for (const [key, state] of Object.entries(jobsByKey)) {
        const job = data.find((j) => j.id === state.jobId);
        if (job) applyJob(key, job);
        else if (state.batchId) dropJob(key);
      }
      void refreshActiveCount();
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudo cancelar el lote'));
    }
  }

  function useTips(s: EditableSuggestion) {
    const key = sKey(s);
    const text = tipsToText(tipsByKey[key]);
    usedText[key] = text;
    s.action = text;
  }

  // Al cerrar el modal se corta el sondeo (los trabajos siguen en el servidor y se retoman al
  // volver a abrirlo) y se limpia el estado local.
  watch(showSuggestModal, (open) => {
    if (open) return;
    stopPolling();
    Object.keys(jobsByKey).forEach((k) => delete jobsByKey[k]);
    Object.keys(tipsByKey).forEach((k) => delete tipsByKey[k]);
    Object.keys(usedText).forEach((k) => delete usedText[k]);
    void refreshActiveCount();
  });

  onBeforeUnmount(stopPolling);

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
    try {
      const { data } = await improvementService.getSuggestions(evaluationId);
      suggestions.value = data.map((s) => ({ ...s, selected: true, responsible: '' }));
      await resumeJobs();
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
        selected.map((s) => {
          const key = sKey(s);
          // Si la acción se armó con «Usar en la acción», el servidor marca la guía como usada y
          // registra el feedback (sin editar / editada) en la misma operación.
          const guideId = usedText[key] !== undefined ? tipsByKey[key]?.guideId : undefined;
          return improvementService.create({
            evaluationId,
            controlId: s.controlId,
            action: s.action,
            responsible: s.responsible,
            priority: s.priority,
            dueDate: s.suggestedDueDate,
            ...(guideId ? { guideId, guideEdited: s.action !== usedText[key] } : {}),
          });
        }),
      );
      // Las guías de las acciones ya creadas no se vuelven a ofrecer.
      selected.filter((_, i) => results[i].status === 'fulfilled').forEach((s) => dropJob(sKey(s)));
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
    void refreshActiveCount();
    fetchPlans();
    fetchEvaluationStatus();
    fetchPlanDeadlines();
    fetchResults();
  });
</script>
