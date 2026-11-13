<template>
  <EnvironmentBanner />
  <NotificationToast />
  <AppLayout v-if="!isBlankLayout">
    <router-view />
  </AppLayout>
  <router-view v-else />
</template>

<script setup lang="ts">
  import { computed, onMounted, watch } from 'vue';
  import { useRoute } from 'vue-router';
  import NotificationToast from '@/components/common/NotificationToast.vue';
  import AppLayout from '@/components/layout/AppLayout.vue';
  import EnvironmentBanner from '@/components/layout/EnvironmentBanner.vue';
  import { useAppInfoStore } from '@/stores/appInfo';
  import { useAuthStore } from '@/stores/auth';

  const route = useRoute();
  const appInfo = useAppInfoStore();
  const authStore = useAuthStore();

  // Versión y entorno de la API: al arrancar (es público, lo necesita el login) y otra vez al
  // iniciar sesión, para sumar el commit.
  onMounted(() => appInfo.fetch());
  watch(
    () => authStore.isAuthenticated,
    (authenticated) => {
      if (authenticated) appInfo.fetch();
    },
  );

  const isBlankLayout = computed(() => route.meta.layout === 'blank');
</script>
