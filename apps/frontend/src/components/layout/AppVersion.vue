<template>
  <span
    class="inline-flex flex-wrap items-center justify-center gap-x-1"
    :title="details"
    data-testid="app-version"
  >
    <template v-if="appInfo.api && !appInfo.versionMismatch">
      <span>v{{ appInfo.api.version }}</span>
    </template>
    <!-- Web y API de versiones distintas: se muestran las dos y se resalta (despliegue a medias). -->
    <template v-else-if="appInfo.api">
      <span class="font-semibold text-amber-500" data-testid="version-mismatch">
        web v{{ FRONTEND_VERSION }} · API v{{ appInfo.api.version }}
      </span>
    </template>
    <template v-else>
      <span>v{{ FRONTEND_VERSION }}</span>
    </template>
    <span v-if="appInfo.environment && appInfo.environment !== 'production'">
      · {{ appInfo.environment }}
    </span>
  </span>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { useAppInfoStore } from '@/stores/appInfo';
  import {
    FRONTEND_BUILD_TIME,
    FRONTEND_COMMIT,
    FRONTEND_VERSION,
    shortCommit,
  } from '@/utils/appVersion';

  const appInfo = useAppInfoStore();

  // Detalle al pasar el mouse. El commit de la API solo llega con sesión iniciada.
  const details = computed(() => {
    const lines = [
      `Web: v${FRONTEND_VERSION}${FRONTEND_COMMIT ? ` (${shortCommit(FRONTEND_COMMIT)})` : ''}`,
    ];
    if (FRONTEND_BUILD_TIME) lines.push(`Web construida: ${FRONTEND_BUILD_TIME}`);
    if (appInfo.api) {
      const commit = appInfo.api.commit ? ` (${shortCommit(appInfo.api.commit)})` : '';
      lines.push(`API: v${appInfo.api.version}${commit}`);
      if (appInfo.api.buildTime) lines.push(`API construida: ${appInfo.api.buildTime}`);
      lines.push(`Entorno: ${appInfo.api.environment}`);
    } else {
      lines.push('API: sin información');
    }
    if (appInfo.versionMismatch) {
      lines.push('La web y la API tienen versiones distintas.');
    }
    return lines.join('\n');
  });
</script>
