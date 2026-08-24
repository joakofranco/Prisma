<template>
  <div
    :class="[
      'p-4 rounded-xl border',
      variant === 'info' &&
        'bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-950 dark:border-blue-800 dark:text-blue-200',
      variant === 'success' &&
        'bg-emerald-50 border-emerald-200 text-emerald-800 dark:bg-emerald-950 dark:border-emerald-800 dark:text-emerald-200',
      variant === 'warning' &&
        'bg-amber-50 border-amber-200 text-amber-800 dark:bg-amber-950 dark:border-amber-800 dark:text-amber-200',
      variant === 'danger' &&
        'bg-red-50 border-red-200 text-red-800 dark:bg-red-950 dark:border-red-800 dark:text-red-200',
    ]"
  >
    <div class="flex items-start gap-3">
      <component :is="iconComponent" class="w-5 h-5 mt-0.5 shrink-0" />
      <div class="flex-1">
        <p v-if="title" class="font-semibold text-sm">
          {{ title }}
        </p>
        <p class="text-sm mt-1">
          <slot />
        </p>
      </div>
      <button
        v-if="dismissible"
        class="shrink-0 p-1 rounded-lg hover:bg-black/5"
        title="Cerrar"
        @click="$emit('dismiss')"
      >
        <XMarkIcon class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import {
    InformationCircleIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
    ExclamationCircleIcon,
    XMarkIcon,
  } from '@heroicons/vue/24/outline';

  const props = withDefaults(
    defineProps<{
      variant?: 'info' | 'success' | 'warning' | 'danger';
      title?: string;
      dismissible?: boolean;
    }>(),
    {
      variant: 'info',
      title: '',
      dismissible: false,
    },
  );

  defineEmits<{
    dismiss: [];
  }>();

  const iconComponent = computed(() => {
    switch (props.variant) {
      case 'info':
        return InformationCircleIcon;
      case 'success':
        return CheckCircleIcon;
      case 'warning':
        return ExclamationTriangleIcon;
      case 'danger':
        return ExclamationCircleIcon;
      default:
        return InformationCircleIcon;
    }
  });
</script>
