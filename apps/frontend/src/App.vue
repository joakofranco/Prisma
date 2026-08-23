<template>
  <NotificationToast />
  <AppLayout v-if="!isBlankLayout">
    <router-view />
  </AppLayout>
  <router-view v-else />
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { useRoute } from 'vue-router';
  import NotificationToast from '@/components/common/NotificationToast.vue';
  import AppLayout from '@/components/layout/AppLayout.vue';

  const route = useRoute();

  const isBlankLayout = computed(() => route.meta.layout === 'blank');
</script>
