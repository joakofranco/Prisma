<template>
  <!-- Por debajo de lg el menú lateral es un cajón oculto: el encabezado ocupa TODO el ancho
       (left-0). Antes llevaba siempre el margen del menú (16rem/4rem) y dejaba un hueco a la
       izquierda con la hamburguesa "flotando" a 272 px. Desde lg se corre según el menú. -->
  <header
    :class="[
      'fixed top-0 right-0 left-0 z-30 h-16 bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between gap-3 px-4 lg:px-6 transition-[left] duration-300',
      collapsed ? 'lg:left-16' : 'lg:left-64',
    ]"
  >
    <div class="flex items-center gap-3 min-w-0">
      <button
        class="lg:hidden shrink-0 p-2 -ml-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700"
        title="Abrir menú"
        aria-label="Abrir menú"
        @click="onToggleMobile"
      >
        <Bars3Icon class="w-6 h-6 text-slate-600 dark:text-slate-300" />
      </button>
      <!-- Logo junto a la hamburguesa (en escritorio ya está en el menú lateral). -->
      <div class="flex items-center gap-2 shrink-0 lg:hidden">
        <ShieldCheckIcon class="w-7 h-7 text-blue-600 dark:text-blue-400" />
        <span
          class="hidden sm:inline text-lg font-bold tracking-wide text-slate-900 dark:text-white"
        >
          PRISMA
        </span>
      </div>
      <h1 class="text-lg font-semibold text-slate-800 dark:text-white truncate">
        {{ currentTitle }}
      </h1>
    </div>

    <div class="flex items-center gap-3">
      <div ref="userMenuRoot" class="relative">
        <button
          ref="userMenuButton"
          class="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          aria-haspopup="true"
          :aria-expanded="showUserMenu"
          @click="showUserMenu = !showUserMenu"
        >
          <div
            class="w-8 h-8 rounded-full bg-blue-100 dark:bg-blue-950 flex items-center justify-center"
          >
            <span class="text-sm font-medium text-blue-700 dark:text-blue-300">{{
              userInitials
            }}</span>
          </div>
          <span class="text-sm font-medium text-slate-700 dark:text-slate-200 hidden md:block">{{
            userName
          }}</span>
          <ChevronDownIcon class="w-4 h-4 text-slate-500 dark:text-slate-400" />
        </button>

        <Transition
          enter-active-class="transition ease-out duration-100"
          enter-from-class="transform opacity-0 scale-95"
          enter-to-class="transform opacity-100 scale-100"
          leave-active-class="transition ease-in duration-75"
          leave-from-class="transform opacity-100 scale-100"
          leave-to-class="transform opacity-0 scale-95"
        >
          <div
            v-if="showUserMenu"
            class="absolute right-0 mt-2 w-56 bg-white dark:bg-slate-800 rounded-xl shadow-lg border border-slate-200 dark:border-slate-700 py-1 z-50"
          >
            <div class="px-4 py-3 border-b border-slate-100 dark:border-slate-700">
              <p class="text-sm font-medium text-slate-900 dark:text-white">
                {{ userName }}
              </p>
              <p class="text-xs text-slate-500 dark:text-slate-400">
                {{ authStore.user?.email }}
              </p>
              <div class="mt-2 flex flex-wrap gap-1">
                <span
                  v-for="role in authStore.roles"
                  :key="role"
                  class="inline-block px-2 py-0.5 text-xs font-medium rounded-full bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300"
                >
                  {{ formatRole(role) }}
                </span>
              </div>
            </div>
            <router-link
              to="/account"
              class="w-full text-left px-4 py-2 text-sm text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 flex items-center gap-2"
              @click="showUserMenu = false"
            >
              <Cog6ToothIcon class="w-4 h-4" />
              Mi Cuenta
            </router-link>
            <button
              class="w-full text-left px-4 py-2 text-sm text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 flex items-center gap-2"
              @click="themeStore.toggle()"
            >
              <SunIcon v-if="themeStore.theme === 'dark'" class="w-4 h-4" />
              <MoonIcon v-else class="w-4 h-4" />
              {{ themeStore.theme === 'dark' ? 'Tema Claro' : 'Tema Oscuro' }}
            </button>
            <div class="my-1 border-t border-slate-100 dark:border-slate-700" />
            <button
              class="w-full text-left px-4 py-2 text-sm text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 flex items-center gap-2"
              @click="handleLogout"
            >
              <ArrowRightOnRectangleIcon class="w-4 h-4" />
              Cerrar Sesión
            </button>
          </div>
        </Transition>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
  import { ref, computed, nextTick, watch } from 'vue';
  import { onClickOutside, onKeyStroke } from '@vueuse/core';
  import { useRoute } from 'vue-router';
  import { useAuthStore } from '@/stores/auth';
  import { useThemeStore } from '@/stores/theme';
  import { ROLE_LABELS } from '@/utils/constants';
  import {
    Bars3Icon,
    ChevronDownIcon,
    Cog6ToothIcon,
    ArrowRightOnRectangleIcon,
    SunIcon,
    MoonIcon,
  } from '@heroicons/vue/24/outline';
  import { ShieldCheckIcon } from '@heroicons/vue/24/solid';
  import type { UserRole } from '@/types';

  defineProps<{
    collapsed: boolean;
  }>();

  const emit = defineEmits<{
    'toggle-mobile': [];
  }>();

  const route = useRoute();
  const authStore = useAuthStore();
  const themeStore = useThemeStore();
  const showUserMenu = ref(false);
  const userMenuRoot = ref<HTMLElement | null>(null);
  const userMenuButton = ref<HTMLButtonElement | null>(null);

  // El menú de usuario se cierra: al clickear FUERA de él (incluido el menú lateral), con ESC
  // (devolviendo el foco al botón que lo abrió: WCAG 2.4.3) y al cambiar de ruta -- antes quedaba
  // abierto al navegar por el menú lateral, tapando contenido de la nueva página.
  onClickOutside(userMenuRoot, () => {
    showUserMenu.value = false;
  });

  onKeyStroke('Escape', () => {
    if (!showUserMenu.value) return;
    showUserMenu.value = false;
    nextTick(() => userMenuButton.value?.focus());
  });

  watch(
    () => route.fullPath,
    () => {
      showUserMenu.value = false;
    },
  );

  function onToggleMobile() {
    showUserMenu.value = false;
    emit('toggle-mobile');
  }

  const currentTitle = computed(() => (route.meta.title as string) || 'PRISMA');

  const userName = computed(() => {
    const user = authStore.user;
    if (!user) return '';
    return `${user.firstName} ${user.lastName}`;
  });

  const userInitials = computed(() => {
    const user = authStore.user;
    if (!user) return '?';
    return `${user.firstName?.[0] || ''}${user.lastName?.[0] || ''}`.toUpperCase();
  });

  function formatRole(role: UserRole): string {
    return ROLE_LABELS[role] || role;
  }

  function handleLogout() {
    showUserMenu.value = false;
    authStore.logout();
  }
</script>
