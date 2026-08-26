<template>
  <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-6">
    <div class="flex items-center justify-between mb-5">
      <h3 class="text-sm font-semibold text-slate-900 dark:text-white">Ciclo de vida de la evaluación</h3>
      <button
        type="button"
        class="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700 hover:underline"
        @click="showGlossary = true"
      >
        <QuestionMarkCircleIcon class="w-4 h-4" />
        ¿Qué significa cada estado?
      </button>
    </div>

    <BaseAlert v-if="isReturned" variant="danger" title="Devuelta por auditoría" class="mb-5">
      {{ getStatusDescription('RETURNED') }}
      <span v-if="responsibleLabel" class="font-medium">
        Responsable ahora: {{ responsibleLabel }}.</span
      >
    </BaseAlert>

    <div class="grid" :style="{ gridTemplateColumns: `repeat(${steps.length}, 1fr)` }">
      <div
        v-for="(step, i) in steps"
        :key="step"
        class="relative flex flex-col items-center text-center px-1"
      >
        <div
          v-if="i > 0"
          class="absolute h-0.5 w-full"
          style="top: 15px; right: 50%"
          :class="connectorClass(i - 1)"
        />
        <div
          class="relative z-10 w-8 h-8 rounded-full flex items-center justify-center text-xs font-semibold border-2 bg-white dark:bg-slate-800"
          :class="circleClass(i)"
        >
          <CheckIcon v-if="stepState(i) === 'done'" class="w-4 h-4" />
          <span v-else>{{ i + 1 }}</span>
        </div>
        <span class="mt-2 text-[11px] leading-tight" :class="labelClass(i)">
          {{ getStatusLabel(step) }}
        </span>
      </div>
    </div>

    <p v-if="!isReturned" class="mt-5 text-xs text-slate-500 dark:text-slate-400 text-center">
      {{ getStatusDescription(status) }}
      <span v-if="responsibleLabel" class="font-medium text-slate-700 dark:text-slate-200">
        Responsable ahora: {{ responsibleLabel }}.
      </span>
    </p>

    <BaseModal v-model="showGlossary" title="Estados de una evaluación" size="lg">
      <div class="space-y-3">
        <div
          v-for="s in glossaryOrder"
          :key="s"
          class="flex items-start gap-3 p-3 rounded-lg"
          :class="s === status ? 'bg-blue-50 ring-1 ring-blue-200' : 'bg-slate-50 dark:bg-slate-900'"
        >
          <span
            class="mt-0.5 inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium shrink-0"
            :style="{ backgroundColor: getStatusBgColor(s), color: getStatusColor(s) }"
          >
            {{ getStatusLabel(s) }}
          </span>
          <div class="min-w-0">
            <p class="text-sm text-slate-700 dark:text-slate-200">{{ getStatusDescription(s) }}</p>
            <p v-if="getStatusResponsibleLabel(s)" class="text-xs text-slate-400 mt-0.5">
              Responsable: {{ getStatusResponsibleLabel(s) }}
            </p>
          </div>
        </div>
      </div>
      <template #footer>
        <BaseButton variant="secondary" @click="showGlossary = false"> Cerrar </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
  import { computed, ref } from 'vue';
  import { QuestionMarkCircleIcon, CheckIcon } from '@heroicons/vue/24/outline';
  import { EVALUATION_LIFECYCLE_STEPS } from '@/utils/constants';
  import {
    getStatusLabel,
    getStatusColor,
    getStatusBgColor,
    getStatusDescription,
    getStatusResponsibleLabel,
  } from '@/utils/helpers';
  import BaseAlert from '@/components/common/BaseAlert.vue';
  import BaseModal from '@/components/common/BaseModal.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import type { EvaluationStatus } from '@/types';

  const props = defineProps<{ status: EvaluationStatus }>();

  const steps = EVALUATION_LIFECYCLE_STEPS;
  // Orden de lectura del glosario: RETURNED se intercala después de IN_PROGRESS porque, en la
  // práctica, es el mismo punto del proceso (falta corregir y reenviar), no un paso aparte.
  const glossaryOrder: EvaluationStatus[] = [
    'DRAFT',
    'IN_PROGRESS',
    'RETURNED',
    'READY_FOR_AUDIT',
    'IN_AUDIT',
    'APPROVED',
    'ARCHIVED',
  ];

  const showGlossary = ref(false);

  const isReturned = computed(() => props.status === 'RETURNED');
  // RETURNED no tiene posición propia en el stepper: mientras dura, lo pendiente es lo mismo que
  // en IN_PROGRESS (corregir y reenviar), así que se posiciona ahí y se señaliza aparte con la
  // alerta roja de arriba en vez de inventarle un séptimo paso al camino principal.
  const effectiveStatus = computed(() => (isReturned.value ? 'IN_PROGRESS' : props.status));
  const currentIndex = computed(() =>
    steps.indexOf(effectiveStatus.value as (typeof steps)[number]),
  );

  const responsibleLabel = computed(() => getStatusResponsibleLabel(props.status));

  function stepState(i: number): 'done' | 'current' | 'upcoming' {
    if (i < currentIndex.value) return 'done';
    if (i === currentIndex.value) return 'current';
    return 'upcoming';
  }

  function circleClass(i: number) {
    const state = stepState(i);
    if (state === 'done') return 'bg-emerald-500 border-emerald-500 text-white';
    if (state === 'current') {
      return isReturned.value
        ? 'bg-red-500 border-red-500 text-white ring-4 ring-red-100'
        : 'bg-blue-600 border-blue-600 text-white ring-4 ring-blue-100';
    }
    // ARCHIVED es un paso manual/opcional (no todas las evaluaciones se archivan): se marca con
    // borde punteado en vez del sólido de los pasos que sí son obligatorios en el camino.
    return steps[i] === 'ARCHIVED'
      ? 'bg-white dark:bg-slate-800 border-dashed border-slate-300 dark:border-slate-600 text-slate-400'
      : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600 text-slate-400';
  }

  function labelClass(i: number) {
    const state = stepState(i);
    if (state === 'current')
      return isReturned.value ? 'text-red-600 font-semibold' : 'text-slate-900 dark:text-white font-semibold';
    if (state === 'done') return 'text-slate-600 dark:text-slate-300';
    return 'text-slate-400';
  }

  function connectorClass(i: number) {
    return i < currentIndex.value ? 'bg-emerald-400' : 'bg-slate-200';
  }
</script>
