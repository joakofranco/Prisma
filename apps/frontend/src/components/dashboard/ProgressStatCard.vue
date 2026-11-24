<template>
  <div
    class="flex flex-col rounded-xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-700 dark:bg-slate-800"
  >
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0">
        <p class="text-sm font-medium text-slate-500 dark:text-slate-400">{{ title }}</p>
        <p :class="['mt-1 text-2xl font-bold', valueClass || 'text-slate-900 dark:text-white']">
          {{ value
          }}<span
            v-if="suffix"
            class="ml-1 text-base font-semibold text-slate-500 dark:text-slate-400"
            >{{ suffix }}</span
          >
        </p>
      </div>
      <div :class="['shrink-0 rounded-xl p-3', iconBgClass]" aria-hidden="true">
        <component :is="icon" :class="['h-6 w-6', iconColorClass]" />
      </div>
    </div>
    <div
      v-if="percent !== undefined"
      class="mt-3 h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="title"
    >
      <span
        :class="['block h-full rounded-full', barClass]"
        :style="{ width: `${Math.min(100, Math.max(0, percent))}%` }"
      ></span>
    </div>
    <p class="mt-2 text-xs text-slate-500 dark:text-slate-400"><slot /></p>
  </div>
</template>

<script setup lang="ts">
  import type { Component } from 'vue';

  // RF-VIZ-07 (PRI-321): tarjeta del avance de una evaluación con el mismo estilo que StatsCard
  // (las tarjetas de arriba del Panel) y, opcionalmente, una barra de progreso.
  defineProps<{
    title: string;
    value: string | number;
    suffix?: string;
    icon: Component;
    iconBgClass?: string;
    iconColorClass?: string;
    valueClass?: string;
    /** 0–100: muestra la barra de progreso. */
    percent?: number;
    barClass?: string;
  }>();
</script>
