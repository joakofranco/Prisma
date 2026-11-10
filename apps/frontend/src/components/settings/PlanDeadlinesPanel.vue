<template>
  <div
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6 w-full"
  >
    <h2 class="text-lg font-semibold text-slate-900 dark:text-white">Plazos por defecto</h2>
    <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
      Meses que se suman a la fecha de creación para sugerir la fecha límite de cada acción del plan
      de mejora, según su prioridad.
    </p>

    <div v-if="loading" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400">
      Cargando...
    </div>

    <form v-else class="space-y-5 mt-5" novalidate @submit.prevent="handleSubmit">
      <BaseAlert v-if="saveError" variant="danger" data-testid="deadlines-save-error">
        {{ saveError }}
      </BaseAlert>

      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <div
          v-for="row in rows"
          :key="row.key"
          :class="[
            'p-4 rounded-lg border',
            errors[row.key]
              ? 'border-red-300 bg-red-50/40 dark:border-red-800 dark:bg-red-950/30'
              : 'border-slate-200 dark:border-slate-700',
          ]"
        >
          <BaseInput
            :id="`deadline-${row.key}`"
            :model-value="form[row.key]"
            :label="`Prioridad ${row.label} (meses)`"
            type="number"
            :min="String(MIN_DEADLINE_MONTHS)"
            :error="errors[row.key]"
            :data-testid="`deadline-${row.key}`"
            required
            @update:model-value="form[row.key] = $event"
          />
          <p v-if="!errors[row.key]" class="mt-1 text-xs text-slate-500 dark:text-slate-400">
            Por defecto: {{ DEFAULT_PLAN_DEADLINES[row.key] }} meses
          </p>
        </div>
      </div>

      <!-- Vista previa en vivo: se recalcula con cada tecla, antes de guardar. -->
      <div
        v-if="isValid"
        class="px-4 py-3 rounded-lg bg-slate-100 dark:bg-slate-700/60 text-sm text-slate-700 dark:text-slate-200"
        data-testid="deadlines-preview"
      >
        <p class="font-medium">Así quedaría una acción creada hoy ({{ formatDate(today) }})</p>
        <p class="mt-1">
          <span v-for="(row, i) in rows" :key="row.key">
            {{ row.label }} → <strong>{{ previewFor(row.priority) }}</strong
            ><span v-if="i < rows.length - 1"> · </span>
          </span>
        </p>
      </div>

      <ul class="text-xs text-slate-500 dark:text-slate-400 space-y-1">
        <li>
          Entre {{ MIN_DEADLINE_MONTHS }} y {{ MAX_DEADLINE_MONTHS }} meses. Una prioridad más
          urgente no puede tener más plazo (Alta ≤ Media ≤ Baja).
        </li>
        <li>
          El cambio aplica a las sugerencias y acciones nuevas: las fechas ya cargadas no se
          modifican.
        </li>
      </ul>

      <div class="flex flex-wrap items-center justify-between gap-3 pt-2">
        <BaseButton
          type="button"
          variant="ghost"
          :disabled="isDefault"
          data-testid="deadlines-reset"
          @click="resetToDefaults"
        >
          Restablecer {{ DEFAULT_PLAN_DEADLINES.highMonths }} /
          {{ DEFAULT_PLAN_DEADLINES.mediumMonths }} / {{ DEFAULT_PLAN_DEADLINES.lowMonths }}
        </BaseButton>
        <div class="flex gap-2">
          <BaseButton type="button" variant="secondary" :disabled="!isDirty" @click="discard">
            Cancelar
          </BaseButton>
          <BaseButton
            type="submit"
            variant="primary"
            :loading="saving"
            :disabled="!isValid || !isDirty"
          >
            Guardar
          </BaseButton>
        </div>
      </div>
    </form>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from 'vue';
  import { planDeadlinesService } from '@/services/resources';
  import { useNotificationStore } from '@/stores/notification';
  import { formatDate, getErrorMessage } from '@/utils/helpers';
  import {
    DEFAULT_PLAN_DEADLINES,
    MAX_DEADLINE_MONTHS,
    MIN_DEADLINE_MONTHS,
    defaultDueDate,
    validatePlanDeadlines,
  } from '@/utils/planDeadlines';
  import BaseInput from '@/components/common/BaseInput.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import type { ImprovementPlan, PlanDeadlines } from '@/types';

  type Key = keyof PlanDeadlines;

  const rows: { key: Key; label: string; priority: ImprovementPlan['priority'] }[] = [
    { key: 'highMonths', label: 'Alta', priority: 'HIGH' },
    { key: 'mediumMonths', label: 'Media', priority: 'MEDIUM' },
    { key: 'lowMonths', label: 'Baja', priority: 'LOW' },
  ];

  const notification = useNotificationStore();
  const today = new Date();

  const loading = ref(true);
  const saving = ref(false);
  const saveError = ref('');
  const saved = ref<PlanDeadlines>({ ...DEFAULT_PLAN_DEADLINES });
  // Los inputs emiten texto: se guarda tal cual y se convierte a número para validar.
  const form = reactive<Record<Key, string | number>>({ ...DEFAULT_PLAN_DEADLINES });

  const values = computed<PlanDeadlines>(() => ({
    highMonths: Number(form.highMonths === '' ? NaN : form.highMonths),
    mediumMonths: Number(form.mediumMonths === '' ? NaN : form.mediumMonths),
    lowMonths: Number(form.lowMonths === '' ? NaN : form.lowMonths),
  }));
  const errors = computed(() => validatePlanDeadlines(values.value));
  const isValid = computed(() => !Object.values(errors.value).some(Boolean));
  const isDirty = computed(() => rows.some((r) => values.value[r.key] !== saved.value[r.key]));
  const isDefault = computed(() =>
    rows.every((r) => values.value[r.key] === DEFAULT_PLAN_DEADLINES[r.key]),
  );

  function previewFor(priority: ImprovementPlan['priority']) {
    return formatDate(defaultDueDate(priority, values.value, today) + 'T00:00:00');
  }

  function apply(data: PlanDeadlines) {
    saved.value = { ...data };
    rows.forEach((r) => (form[r.key] = data[r.key]));
  }

  function resetToDefaults() {
    rows.forEach((r) => (form[r.key] = DEFAULT_PLAN_DEADLINES[r.key]));
  }

  function discard() {
    apply(saved.value);
    saveError.value = '';
  }

  onMounted(async () => {
    try {
      const { data } = await planDeadlinesService.get();
      apply(data);
    } catch (err) {
      notification.error(getErrorMessage(err, 'No se pudieron cargar los plazos por defecto'));
    } finally {
      loading.value = false;
    }
  });

  async function handleSubmit() {
    if (!isValid.value || !isDirty.value) return;
    saving.value = true;
    saveError.value = '';
    try {
      const { data } = await planDeadlinesService.update({ ...values.value });
      apply(data);
      notification.success('Plazos por defecto guardados');
    } catch (err) {
      saveError.value = getErrorMessage(err, 'No se pudieron guardar los plazos por defecto');
    } finally {
      saving.value = false;
    }
  }
</script>
