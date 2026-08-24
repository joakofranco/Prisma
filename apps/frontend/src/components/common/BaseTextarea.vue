<template>
  <div>
    <label v-if="label" class="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
      {{ label }}
      <span v-if="required" class="text-red-500">*</span>
    </label>
    <textarea
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :rows="rows"
      :class="[
        'w-full px-3 py-2 rounded-lg border text-sm transition-colors focus:outline-none focus:ring-2 resize-vertical text-slate-900 dark:text-white placeholder:text-slate-400 dark:placeholder:text-slate-500',
        error
          ? 'border-red-300 dark:border-red-500 focus:ring-red-500 focus:border-red-500'
          : 'border-slate-300 dark:border-slate-600 focus:ring-blue-500 focus:border-blue-500',
        disabled
          ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
          : 'bg-white dark:bg-slate-700',
      ]"
      @input="$emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <p v-if="error" class="mt-1 text-xs text-red-600">
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
  withDefaults(
    defineProps<{
      modelValue: string;
      label?: string;
      placeholder?: string;
      disabled?: boolean;
      required?: boolean;
      error?: string;
      rows?: number;
    }>(),
    {
      label: '',
      placeholder: '',
      rows: 3,
      disabled: false,
      required: false,
      error: '',
    },
  );

  defineEmits<{
    'update:modelValue': [value: string];
  }>();
</script>
