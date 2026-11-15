<template>
  <div
    v-if="observations.length > 0"
    role="status"
    class="flex flex-col sm:flex-row sm:items-center gap-3 rounded-xl border border-amber-300 bg-amber-50 px-5 py-4 dark:border-amber-800 dark:bg-amber-950"
    data-testid="reopened-notice"
  >
    <ArrowUturnLeftIcon
      class="w-6 h-6 shrink-0 text-amber-700 dark:text-amber-400"
      aria-hidden="true"
    />
    <div class="flex-1">
      <p class="text-base font-bold text-amber-900 dark:text-amber-200">
        Evaluación reabierta para corregir lo que observó el auditor
      </p>
      <p class="mt-1 text-sm text-amber-900 dark:text-amber-200">
        El auditor la devolvió<template v-if="returnedAt">
          el {{ formatDate(returnedAt) }}</template
        >
        con
        <strong>
          {{ observations.length }}
          {{ observations.length === 1 ? 'observación' : 'observaciones' }}
          <template v-if="openCount > 0">
            ({{ openCount }} {{ openCount === 1 ? 'abierta' : 'abiertas' }})</template
          ></strong
        >. Revisalas abajo, corregí las respuestas o las evidencias que correspondan y volvé a
        <strong>enviarla a auditoría</strong>.
      </p>
    </div>
    <button
      type="button"
      class="shrink-0 self-start sm:self-center h-10 px-4 rounded-lg border border-amber-500 bg-white text-sm font-semibold text-amber-800 hover:bg-amber-100 dark:bg-amber-900 dark:text-amber-100 dark:border-amber-700 dark:hover:bg-amber-800"
      data-testid="reopened-notice-go"
      @click="goToObservations"
    >
      Ver observaciones
    </button>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue';
  import { ArrowUturnLeftIcon } from '@heroicons/vue/24/outline';
  import { auditService, evaluationsService } from '@/services/resources';
  import { formatDate } from '@/utils/helpers';
  import type { AuditObservation, EvaluationAuditCycleEvent } from '@/types';

  // RF-AUD-08 (PRI-298): al «Reanudar» una evaluación devuelta, la organización corrige con las
  // observaciones del auditor a la vista. Este aviso explica por qué está otra vez En curso y qué
  // hacer. Solo aparece si hay observaciones (en el primer ciclo no hay ninguna: solo se crean con
  // la evaluación en auditoría).
  const props = defineProps<{ evaluationId: string }>();

  const observations = ref<AuditObservation[]>([]);
  const trail = ref<EvaluationAuditCycleEvent[]>([]);

  const openCount = computed(
    () =>
      observations.value.filter((o) => o.status === 'OPEN' || o.status === 'IN_PROGRESS').length,
  );

  // Fecha de la última devolución, según el historial de estados.
  const returnedAt = computed(() => {
    const returns = trail.value.filter((e) => e.status === 'RETURNED');
    return returns.length > 0 ? returns[returns.length - 1].occurredAt : null;
  });

  function goToObservations() {
    document
      .getElementById('auditor-observations')
      ?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }

  onMounted(async () => {
    try {
      const { data } = await auditService.getObservations(props.evaluationId);
      observations.value = data;
    } catch (err) {
      // Sin observaciones legibles no se muestra el aviso; el bloque de abajo informa el error.
      console.error(err);
      return;
    }
    try {
      const { data } = await evaluationsService.getAuditTrail(props.evaluationId);
      trail.value = data;
    } catch (err) {
      // Sin historial el aviso sale igual, solo sin la fecha.
      console.error(err);
    }
  });
</script>
