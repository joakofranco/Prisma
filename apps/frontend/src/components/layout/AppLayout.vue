<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900">
    <AppSidebar
      :collapsed="sidebarCollapsed"
      :mobile-open="mobileMenuOpen"
      @toggle-collapse="sidebarCollapsed = !sidebarCollapsed"
      @close-mobile="mobileMenuOpen = false"
    />

    <div :class="['transition-all duration-300', sidebarCollapsed ? 'lg:ml-16' : 'lg:ml-64']">
      <AppHeader :collapsed="sidebarCollapsed" @toggle-mobile="mobileMenuOpen = !mobileMenuOpen" />

      <main class="pt-16 min-h-screen">
        <div class="p-4 lg:p-6">
          <slot />
        </div>
      </main>
    </div>

    <!-- Mobile overlay -->
    <Transition
      enter-active-class="transition-opacity ease-linear duration-300"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity ease-linear duration-300"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="mobileMenuOpen"
        class="fixed inset-0 z-30 bg-black/50 lg:hidden"
        @click="mobileMenuOpen = false"
      />
    </Transition>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, onUnmounted, ref } from 'vue';
  import AppSidebar from './AppSidebar.vue';
  import AppHeader from './AppHeader.vue';

  const sidebarCollapsed = ref(false);
  const mobileMenuOpen = ref(false);

  // ESC cierra el cajón del menú en móvil / tableta (WCAG 2.1.1: se debe poder cerrar con teclado).
  function onKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape' && mobileMenuOpen.value) mobileMenuOpen.value = false;
  }

  onMounted(() => window.addEventListener('keydown', onKeydown));
  onUnmounted(() => window.removeEventListener('keydown', onKeydown));
</script>
