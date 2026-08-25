<template>
  <div
    :class="[
      'rounded-xl p-5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm',
      clickable ? 'cursor-pointer hover:shadow-md transition-shadow' : '',
    ]"
    @click="clickable ? $emit('click') : null"
  >
    <div class="flex items-center justify-between">
      <div>
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400">
          {{ title }}
        </p>
        <p class="text-2xl font-bold text-slate-900 dark:text-white mt-1">
          {{ value }}
        </p>
        <p v-if="subtitle" class="text-xs text-slate-400 dark:text-slate-500 mt-1">
          {{ subtitle }}
        </p>
      </div>
      <div :class="['p-3 rounded-xl', iconBgClass]">
        <component :is="icon" :class="['w-6 h-6', iconColorClass]" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { Component } from 'vue';

  defineProps<{
    title: string;
    value: string | number;
    subtitle?: string;
    icon: Component;
    iconBgClass?: string;
    iconColorClass?: string;
    clickable?: boolean;
  }>();

  defineEmits<{
    click: [];
  }>();
</script>
