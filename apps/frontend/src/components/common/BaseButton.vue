<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'inline-flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium rounded-lg transition-all focus:outline-none focus:ring-2 focus:ring-offset-2',
      variant === 'primary' && 'bg-blue-600 text-white hover:bg-blue-700 focus:ring-blue-500',
      variant === 'secondary' &&
        'bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-slate-200 focus:ring-slate-500 dark:hover:bg-slate-600',
      variant === 'danger' && 'bg-red-600 text-white hover:bg-red-700 focus:ring-red-500',
      variant === 'success' &&
        'bg-emerald-600 text-white hover:bg-emerald-700 focus:ring-emerald-500',
      variant === 'warning' && 'bg-amber-500 text-white hover:bg-amber-600 focus:ring-amber-500',
      variant === 'ghost' &&
        'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 focus:ring-slate-500',
      variant === 'outline' &&
        'bg-white dark:bg-slate-800 text-blue-600 border-2 border-blue-600 hover:bg-blue-50 focus:ring-blue-500 dark:bg-transparent dark:hover:bg-blue-950',
      variant === 'link' &&
        'text-blue-600 hover:text-blue-700 underline-offset-4 hover:underline p-0 h-auto',
      (disabled || loading) && 'opacity-50 cursor-not-allowed',
    ]"
  >
    <svg v-if="loading" class="animate-spin -ml-1 h-4 w-4" fill="none" viewBox="0 0 24 24">
      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
      <path
        class="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
      />
    </svg>
    <slot />
  </button>
</template>

<script setup lang="ts">
  withDefaults(
    defineProps<{
      variant?:
        'primary' | 'secondary' | 'danger' | 'success' | 'warning' | 'ghost' | 'outline' | 'link';
      type?: 'button' | 'submit' | 'reset';
      disabled?: boolean;
      loading?: boolean;
    }>(),
    {
      variant: 'primary',
      type: 'button',
      disabled: false,
      loading: false,
    },
  );
</script>
