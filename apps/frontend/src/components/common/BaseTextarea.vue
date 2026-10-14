<template>
  <div>
    <label
      v-if="label"
      :for="inputId"
      class="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
    >
      {{ label }}
      <span v-if="required" class="text-red-600 dark:text-red-400" aria-hidden="true">*</span>
    </label>
    <textarea
      :id="inputId"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :aria-required="required ? 'true' : undefined"
      :rows="rows"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? errorId : undefined"
      :class="[
        'w-full px-3 py-2 rounded-lg border text-sm transition-colors focus:outline-none focus:ring-2 resize-vertical text-slate-900 dark:text-white placeholder:text-slate-500 dark:placeholder:text-slate-300',
        error
          ? 'border-red-400 dark:border-red-400 focus:ring-red-500 focus:border-red-500'
          : 'border-slate-300 dark:border-slate-500 focus:ring-blue-500 focus:border-blue-500',
        disabled
          ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
          : 'bg-white dark:bg-slate-700',
      ]"
      @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <p v-if="error" :id="errorId" class="mt-1 text-xs text-red-600 dark:text-red-400">
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
  import { computed, useId } from 'vue';

  const props = withDefaults(
    defineProps<{
      modelValue: string;
      label?: string;
      placeholder?: string;
      disabled?: boolean;
      required?: boolean;
      error?: string;
      rows?: number;
      id?: string;
    }>(),
    {
      label: '',
      placeholder: '',
      rows: 3,
      disabled: false,
      required: false,
      error: '',
      id: '',
    },
  );

  defineEmits<{
    'update:modelValue': [value: string];
  }>();

  // Ver el comentario equivalente en BaseInput.vue: sin id la etiqueta no quedaba asociada.
  const autoId = useId();
  const inputId = computed(() => props.id || autoId);
  const errorId = computed(() => `${inputId.value}-error`);
</script>
