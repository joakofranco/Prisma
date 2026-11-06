<template>
  <div>
    <div class="flex items-baseline justify-between gap-2 mb-1">
      <span :id="labelId" class="text-sm font-medium text-slate-700 dark:text-slate-200">
        {{ label }}
      </span>
      <span class="text-xs text-slate-500 dark:text-slate-400">
        {{ detail }}
      </span>
    </div>
    <div
      class="h-2.5 w-full rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden"
      role="progressbar"
      :aria-labelledby="labelId"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="value ?? undefined"
      :aria-valuetext="valueText"
    >
      <div
        v-if="value !== null"
        :class="['h-full rounded-full transition-all duration-300', barClass]"
        :style="{ width: `${clamped}%` }"
      />
    </div>
    <p class="mt-1 text-xs font-semibold text-slate-700 dark:text-slate-200">
      {{ value === null ? 'Sin acciones' : `${value} %` }}
    </p>
  </div>
</template>

<script setup lang="ts">
  import { computed, useId } from 'vue';

  const props = withDefaults(
    defineProps<{
      label: string;
      /** Porcentaje de 0 a 100, o `null` si no hay nada que medir. */
      value: number | null;
      /** Texto de apoyo a la derecha de la etiqueta, por ejemplo «2 de 5 cerradas». */
      detail?: string;
      /** Clases de color de la barra (con su variante para modo oscuro). */
      barClass?: string;
    }>(),
    { detail: '', barClass: 'bg-blue-600 dark:bg-blue-500' },
  );

  const labelId = useId();
  const clamped = computed(() => Math.min(100, Math.max(0, props.value ?? 0)));
  // Lo que anuncia el lector de pantalla: el valor con su contexto, o que no hay acciones.
  const valueText = computed(() =>
    props.value === null
      ? 'Sin acciones'
      : `${props.value} %${props.detail ? `, ${props.detail}` : ''}`,
  );
</script>
