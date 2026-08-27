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
      <div class="flex items-center gap-2">
        <BaseButton variant="secondary" :loading="loadingSuggestions" @click="openSuggestModal">
          <SparklesIcon class="w-4 h-4" />
          Generar Sugerencias
        </BaseButton>
        <BaseButton variant="primary" @click="showCreateModal = true">
          <PlusIcon class="w-4 h-4" />
          Nueva Acción
        </BaseButton>
      </div>
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
          title="En Progreso"
          :value="plansByStatus('IN_PROGRESS')"
          :icon="PlayIcon"
          icon-bg-class="bg-blue-50"
          icon-color-class="text-blue-600"
        />
        <StatsCard
          title="Completadas"
          :value="plansByStatus('COMPLETED')"
          :icon="CheckCircleIcon"
          icon-bg-class="bg-emerald-50"
          icon-color-class="text-emerald-600"
        />
        <StatsCard
          title="Vencidas"
          :value="plansByStatus('OVERDUE')"
          :icon="ExclamationCircleIcon"
          icon-bg-class="bg-red-50"
          icon-color-class="text-red-600"
        />
      </div>

      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div v-if="plans.length === 0" class="p-12 text-center text-slate-500 dark:text-slate-400">
          No hay planes de mejora registrados
        </div>
        <div v-else class="divide-y divide-slate-100 dark:divide-slate-700">
          <div v-for="plan in plans" :key="plan.id" class="p-4 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors">
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
                  <span class="text-xs text-slate-400">Responsable: {{ plan.responsible }}</span>
                  <span class="text-xs text-slate-400">Vence: {{ formatDate(plan.dueDate) }}</span>
                </div>
              </div>
              <div class="flex gap-1 shrink-0">
                <select
                  :value="plan.status"
                  class="text-xs border border-slate-300 dark:border-slate-600 rounded-lg px-2 py-1"
                  @change="
                    updatePlanStatus(
                      plan.id,
                      ($event.target as HTMLSelectElement).value as ImprovementPlan['status'],
                    )
                  "
                >
                  <option value="PENDING">Pendiente</option>
                  <option value="IN_PROGRESS">En Progreso</option>
                  <option value="COMPLETED">Completada</option>
                </select>
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
          :error="formErrors.dueDate"
          required
        />
      </form>
      <template #footer>
        <BaseButton variant="secondary" @click="showCreateModal = false"> Cancelar </BaseButton>
        <BaseButton variant="primary" :loading="saving" @click="handleCreate"> Crear </BaseButton>
      </template>
    </BaseModal>

    <BaseModal v-model="showSuggestModal" title="Sugerencias de Mejora" size="lg">
      <div v-if="loadingSuggestions" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
      </div>
      <div v-else-if="suggestions.length === 0" class="text-center py-8 text-slate-500 dark:text-slate-400">
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
          :class="s.selected ? 'bg-white dark:bg-slate-800' : 'bg-slate-50 dark:bg-slate-900 opacity-60'"
        >
          <div class="flex items-start gap-3">
            <input
              v-model="s.selected"
              type="checkbox"
              class="mt-1 w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500"
            />
            <div class="flex-1 space-y-2">
              <div class="flex items-center justify-between flex-wrap gap-2">
                <span class="text-xs text-slate-500 dark:text-slate-400">
                  {{ s.functionName }} › {{ s.categoryName }} › {{ s.subcategoryName }} ({{
                    s.controlCode
                  }})
                </span>
                <span class="text-xs font-medium text-slate-600 dark:text-slate-300"> Nivel {{ s.targetLevel }} </span>
              </div>
              <BaseTextarea v-model="s.action" :rows="2" />

              <button
                type="button"
                class="inline-flex items-center gap-1 text-xs font-medium text-indigo-600 hover:text-indigo-700 disabled:opacity-50"
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
                class="p-3 rounded-lg bg-indigo-50 border border-indigo-100 space-y-1.5"
              >
                <p
                  v-if="tipsByControl[s.controlId].summary"
                  class="text-xs font-medium text-indigo-900"
                >
                  {{ tipsByControl[s.controlId].summary }}
                </p>
                <ul class="text-xs text-indigo-800 list-disc pl-4 space-y-0.5">
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
                  class="w-full px-3 py-2 text-sm rounded-lg border border-slate-300 dark:border-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-500"
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
  import { ref, computed, reactive, onMounted } from 'vue';
  import { useRoute } from 'vue-router';
  import { improvementService } from '@/services/resources';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import { useNotification } from '@/composables/useUtils';
  import StatsCard from '@/components/common/StatsCard.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseSelect from '@/components/common/BaseSelect.vue';
  import BaseTextarea from '@/components/common/BaseTextarea.vue';
  import {
    ArrowLeftIcon,
    PlusIcon,
    SparklesIcon,
    ClockIcon,
    PlayIcon,
    CheckCircleIcon,
    ExclamationCircleIcon,
  } from '@heroicons/vue/24/outline';
  import type { ImprovementPlan, SuggestedImprovement, RemediationTips } from '@/types';

  interface EditableSuggestion extends SuggestedImprovement {
    selected: boolean;
    responsible: string;
  }

  const route = useRoute();
  const notification = useNotification();

  const evaluationId = route.params.evaluationId as string;
  const loading = ref(true);
  const saving = ref(false);
  const showCreateModal = ref(false);
  const plans = ref<ImprovementPlan[]>([]);

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
  });

  const formErrors = reactive({
    action: '',
    responsible: '',
    dueDate: '',
  });

  const priorityOptions = [
    { value: 'HIGH', label: 'Alta' },
    { value: 'MEDIUM', label: 'Media' },
    { value: 'LOW', label: 'Baja' },
  ];

  function plansByStatus(status: string) {
    return plans.value.filter((p) => p.status === status).length;
  }

  function getPriorityLabel(p: string) {
    return { HIGH: 'Alta', MEDIUM: 'Media', LOW: 'Baja' }[p] || p;
  }

  function priorityClass(p: string) {
    return (
      {
        HIGH: 'bg-red-50 text-red-700',
        MEDIUM: 'bg-amber-50 text-amber-700',
        LOW: 'bg-emerald-50 text-emerald-700',
      }[p] || 'bg-slate-50 dark:bg-slate-900 text-slate-700 dark:text-slate-200'
    );
  }

  function planStatusLabel(s: string) {
    return (
      {
        PENDING: 'Pendiente',
        IN_PROGRESS: 'En Progreso',
        COMPLETED: 'Completada',
        OVERDUE: 'Vencida',
      }[s] || s
    );
  }

  function planStatusClass(s: string) {
    return (
      {
        PENDING: 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300',
        IN_PROGRESS: 'bg-blue-100 text-blue-700',
        COMPLETED: 'bg-emerald-100 text-emerald-700',
        OVERDUE: 'bg-red-100 text-red-700',
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

    saving.value = true;
    try {
      await improvementService.create({
        evaluationId,
        ...newPlan,
        status: 'PENDING',
      });
      notification.success('Acción de mejora creada');
      showCreateModal.value = false;
      newPlan.action = '';
      newPlan.responsible = '';
      newPlan.priority = 'MEDIUM';
      newPlan.dueDate = '';
      await fetchPlans();
    } catch {
      notification.error('Error al crear la acción');
    } finally {
      saving.value = false;
    }
  }

  async function updatePlanStatus(id: string, status: ImprovementPlan['status']) {
    try {
      await improvementService.update(id, { status });
      await fetchPlans();
    } catch (err) {
      console.error(err);
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
  });
</script>
