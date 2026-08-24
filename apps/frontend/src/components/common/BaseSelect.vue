<template>
  <div>
    <label v-if="label" :for="id" class="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
      {{ label }}
      <span v-if="required" class="text-red-500">*</span>
    </label>
    <select
      :id="id"
      :value="modelValue"
      :disabled="disabled"
      :class="[
        'w-full px-3 py-2 rounded-lg border text-sm transition-colors focus:outline-none focus:ring-2 text-slate-900 dark:text-white',
        error
          ? 'border-red-300 dark:border-red-500 focus:ring-red-500 focus:border-red-500'
          : 'border-slate-300 dark:border-slate-600 focus:ring-blue-500 focus:border-blue-500',
        disabled
          ? 'bg-slate-100 dark:bg-slate-800 cursor-not-allowed'
          : 'bg-white dark:bg-slate-700',
      ]"
      @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
    >
      <option v-if="placeholder" value="" disabled>
        {{ placeholder }}
      </option>
      <option v-for="option in options" :key="option.value" :value="option.value">
        {{ option.label }}
      </option>
    </select>
    <p v-if="error" class="mt-1 text-xs text-red-600">
      {{ error }}
    </p>
  </div>
</template>

<script setup lang="ts">
  defineProps<{
    modelValue: string;
    options: { value: string; label: string }[];
    label?: string;
    placeholder?: string;
    disabled?: boolean;
    required?: boolean;
    error?: string;
    id?: string;
  }>();

  defineEmits<{
    'update:modelValue': [value: string];
  }>();
</script>
