<template>
  <div>
    <label
      :for="inputId"
      class="inline-flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300"
      :class="disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'"
    >
      <input
        :id="inputId"
        type="checkbox"
        :checked="modelValue"
        :disabled="disabled"
        :aria-describedby="hint ? hintId : undefined"
        class="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-2 focus:ring-blue-500 dark:border-slate-500 dark:bg-slate-700"
        @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
      />
      <span class="font-medium">{{ label }}</span>
    </label>
    <p v-if="hint" :id="hintId" class="mt-1 ml-6 text-xs text-slate-500 dark:text-slate-400">
      {{ hint }}
    </p>
  </div>
</template>

<script setup lang="ts">
  import { computed, useId } from 'vue';

  const props = withDefaults(
    defineProps<{
      modelValue: boolean;
      label: string;
      /** Texto de ayuda debajo de la casilla. */
      hint?: string;
      disabled?: boolean;
      id?: string;
    }>(),
    { hint: '', disabled: false, id: undefined },
  );

  defineEmits<{ 'update:modelValue': [value: boolean] }>();

  const generatedId = useId();
  const inputId = computed(() => props.id ?? `checkbox-${generatedId}`);
  const hintId = computed(() => `${inputId.value}-hint`);
</script>
