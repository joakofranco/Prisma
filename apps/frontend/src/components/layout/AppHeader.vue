<template>
  <header
    class="fixed top-0 right-0 z-30 h-16 bg-white dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between px-4 lg:px-6"
    :style="{ left: collapsed ? '4rem' : '16rem' }"
  >
    <div class="flex items-center gap-4">
      <button
        class="lg:hidden p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700"
        title="Abrir menú"
        @click="$emit('toggle-mobile')"
      >
        <Bars3Icon class="w-6 h-6 text-slate-600 dark:text-slate-300" />
      </button>
      <h1 class="text-lg font-semibold text-slate-800 dark:text-white hidden sm:block">
        {{ currentTitle }}
      </h1>
    </div>

    <div class="flex items-center gap-3">
      <div class="relative">
        <button
          class="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          @click="showUserMenu = !showUserMenu"
        >
          <div class="w-8 h-8 rounded-full bg-blue-100 dark:bg-blue-950 flex items-center justify-center">
            <span class="text-sm font-medium text-blue-700 dark:text-blue-300">{{ userInitials }}</span>
          </div>
          <span class="text-sm font-medium text-slate-700 dark:text-slate-200 hidden md:block">{{ userName }}</span>
          <ChevronDownIcon class="w-4 h-4 text-slate-400" />
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
  import { ref, computed } from 'vue';
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
  import type { UserRole } from '@/types';

  defineProps<{
    collapsed: boolean;
  }>();

  defineEmits<{
    'toggle-mobile': [];
  }>();

  const route = useRoute();
  const authStore = useAuthStore();
  const themeStore = useThemeStore();
  const showUserMenu = ref(false);

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
