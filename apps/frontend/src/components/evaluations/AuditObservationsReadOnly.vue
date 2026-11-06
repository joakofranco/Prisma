<template>
  <section
    class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6"
    aria-labelledby="auditor-observations-title"
    data-testid="auditor-observations"
  >
    <h3
      id="auditor-observations-title"
      class="text-lg font-semibold text-slate-900 dark:text-white mb-1"
    >
      Observaciones del auditor
    </h3>
    <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">
      Hallazgos que el auditor registró al revisar esta evaluación. Son solo de lectura.
    </p>

    <div v-if="loading" class="flex justify-center py-6">
      <div
        class="animate-spin rounded-full h-6 w-6 border-b-2 border-blue-600"
        role="status"
        aria-label="Cargando observaciones"
      />
    </div>
    <p v-else-if="failed" class="text-sm text-red-600 dark:text-red-400" role="alert">
      No se pudieron cargar las observaciones del auditor.
    </p>
    <p
      v-else-if="observations.length === 0"
      class="text-sm text-slate-500 dark:text-slate-400"
      data-testid="auditor-observations-empty"
    >
      El auditor no registró observaciones.
    </p>
    <ul v-else class="space-y-3">
      <li
        v-for="obs in observations"
        :key="obs.id"
        class="p-4 rounded-lg border"
        :class="typeClass(obs.type)"
        data-testid="auditor-observation"
      >
        <div class="flex items-center gap-2 mb-1 flex-wrap">
          <span :class="['text-xs font-semibold uppercase', typeTextClass(obs.type)]">
            {{ typeLabel(obs.type) }}
          </span>
          <span :class="['text-xs px-2 py-0.5 rounded-full', statusClass(obs.status)]">
            {{ statusLabel(obs.status) }}
          </span>
          <span
            v-if="obs.controlCode"
            class="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
          >
            Control {{ obs.controlCode }}
          </span>
          <span
            v-if="obs.subcategoryCode"
            class="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
          >
            {{ obs.subcategoryCode }}
          </span>
        </div>
        <p class="text-sm text-slate-700 dark:text-slate-200 whitespace-pre-line">
          {{ obs.description }}
        </p>
        <p class="text-xs text-slate-500 mt-2 dark:text-slate-400">
          {{ formatDate(obs.createdAt) }}
          <span v-if="obs.createdByName"> — Registrada por {{ obs.createdByName }}</span>
        </p>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
  import { ref, onMounted } from 'vue';
  import { auditService } from '@/services/resources';
  import { formatDate } from '@/utils/helpers';
  import type { AuditObservation } from '@/types';

  // Observaciones del auditor para la organización (PRI-354). Solo lectura: no hay botones de
  // edición. El backend decide cuándo se pueden ver (desde que la evaluación vuelve) y para qué
  // roles; este componente solo se monta cuando la pantalla ya sabe que corresponde.
  const props = defineProps<{ evaluationId: string }>();

  const observations = ref<AuditObservation[]>([]);
  const loading = ref(true);
  const failed = ref(false);

  const TYPE_LABELS: Record<string, string> = {
    OBSERVATION: 'Observación',
    NON_CONFORMITY: 'No Conformidad',
    RECOMMENDATION: 'Recomendación',
  };
  const STATUS_LABELS: Record<string, string> = {
    OPEN: 'Abierta',
    IN_PROGRESS: 'En Progreso',
    RESOLVED: 'Resuelta',
    CLOSED: 'Cerrada',
  };
  const TYPE_CLASSES: Record<string, string> = {
    OBSERVATION: 'bg-blue-50 border-blue-200 dark:bg-blue-950 dark:border-blue-800',
    NON_CONFORMITY: 'bg-red-50 border-red-200 dark:bg-red-950 dark:border-red-800',
    RECOMMENDATION: 'bg-amber-50 border-amber-200 dark:bg-amber-950 dark:border-amber-800',
  };
  const TYPE_TEXT_CLASSES: Record<string, string> = {
    OBSERVATION: 'text-blue-700 dark:text-blue-400',
    NON_CONFORMITY: 'text-red-700 dark:text-red-400',
    RECOMMENDATION: 'text-amber-700 dark:text-amber-400',
  };
  const NEUTRAL = 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';

  const typeLabel = (type: string) => TYPE_LABELS[type] ?? type;
  const statusLabel = (status: string) => STATUS_LABELS[status] ?? status;
  const typeClass = (type: string) =>
    TYPE_CLASSES[type] ?? 'bg-slate-50 dark:bg-slate-900 border-slate-200 dark:border-slate-700';
  const typeTextClass = (type: string) =>
    TYPE_TEXT_CLASSES[type] ?? 'text-slate-700 dark:text-slate-200';
  const statusClass = (status: string) =>
    ({
      IN_PROGRESS: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300',
      RESOLVED: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
    })[status] ?? NEUTRAL;

  onMounted(async () => {
    try {
      const { data } = await auditService.getObservations(props.evaluationId);
      observations.value = data;
    } catch (err) {
      console.error(err);
      failed.value = true;
    } finally {
      loading.value = false;
    }
  });
</script>
