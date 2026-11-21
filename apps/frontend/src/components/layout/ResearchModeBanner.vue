<template>
  <!-- Aviso visible mientras la organización tiene el modo investigación encendido (C8): lo que
       genera la IA se registra para investigación. Se pide la organización de la sesión; los
       administradores lo ven en el detalle de cada organización. -->
  <div
    v-if="active"
    class="bg-violet-100 text-violet-900 dark:bg-violet-950 dark:text-violet-200 text-xs text-center py-1 px-3"
    role="status"
    data-testid="research-mode-banner"
  >
    Modo investigación activo: las respuestas de la IA de su organización se registran para
    investigación.
  </div>
</template>

<script setup lang="ts">
  import { ref, watch } from 'vue';
  import { organizationsService } from '@/services/resources';
  import { useAuthStore } from '@/stores/auth';

  const authStore = useAuthStore();
  const active = ref(false);

  async function refresh() {
    active.value = false;
    const orgId = authStore.user?.organizationIds?.[0];
    if (!authStore.isAuthenticated || !orgId || authStore.isAdmin) return;
    try {
      const { data } = await organizationsService.getById(orgId);
      active.value = data.researchMode === true;
    } catch {
      // sin aviso si no se puede consultar: no es un error para la persona
    }
  }

  watch(() => [authStore.isAuthenticated, authStore.user?.organizationIds?.[0]], refresh, {
    immediate: true,
  });
</script>
