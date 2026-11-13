<template>
  <!-- Cualquier entorno que no sea producción se marca a la vista: una franja fina arriba de todo
       (sobre la cabecera, sin correr el diseño) y una etiqueta abajo que dice cuál es. Así nadie
       confunde un entorno de pruebas con el real. No intercepta clics. -->
  <div
    v-if="appInfo.showEnvironmentBanner"
    class="pointer-events-none"
    data-testid="environment-banner"
    role="status"
  >
    <div
      class="fixed inset-x-0 top-0 z-[100] h-1"
      :class="appInfo.environment === 'development' ? 'bg-amber-500' : 'bg-violet-500'"
      aria-hidden="true"
    />
    <div
      class="fixed bottom-3 left-1/2 z-[100] -translate-x-1/2 rounded-full px-4 py-1 text-xs font-semibold shadow-lg ring-1"
      :class="
        appInfo.environment === 'development'
          ? 'bg-amber-400 text-amber-950 ring-amber-600'
          : 'bg-violet-200 text-violet-950 ring-violet-500'
      "
      data-testid="environment-label"
    >
      {{ appInfo.environmentLabel }}
      <span v-if="appInfo.environment === 'development'" class="font-normal">
        · datos de prueba</span
      >
    </div>
  </div>
</template>

<script setup lang="ts">
  import { watch } from 'vue';
  import { useAppInfoStore } from '@/stores/appInfo';

  const appInfo = useAppInfoStore();

  // La pestaña también lo dice: [DEV] / [STAGING] antes del título, para distinguirla entre varias.
  const TITLE_PREFIX = /^\[[A-Z]+\] /;
  watch(
    () => appInfo.environment,
    (environment) => {
      const base = document.title.replace(TITLE_PREFIX, '');
      if (!environment || environment === 'production') {
        document.title = base;
        return;
      }
      const tag = environment === 'development' ? 'DEV' : environment.toUpperCase().slice(0, 7);
      document.title = `[${tag}] ${base}`;
    },
    { immediate: true },
  );
</script>
