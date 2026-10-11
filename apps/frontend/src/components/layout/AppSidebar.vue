<template>
  <!-- Tres zonas: cabecera fija (logo + botón de contraer/cerrar), lista que se desplaza (flex-1 +
       overflow-y-auto: ningún ítem queda fuera de alcance en pantallas bajas) y nada flotando
       encima de la lista. "Colapsado" solo existe en escritorio (lg+): en el cajón móvil el menú
       siempre se muestra completo. -->
  <aside
    :class="[
      'fixed left-0 top-0 z-40 h-screen flex flex-col transition-transform duration-300 bg-slate-900 text-white',
      collapsed ? 'w-64 lg:w-16' : 'w-64',
      mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0',
    ]"
    aria-label="Menú principal"
  >
    <div
      :class="[
        'shrink-0 border-b border-slate-700 flex items-center gap-2 px-4 h-16',
        collapsed ? 'lg:flex-col lg:h-auto lg:px-0 lg:py-3' : '',
      ]"
    >
      <ShieldCheckIcon class="w-8 h-8 text-blue-400 shrink-0" />
      <span :class="['text-lg font-bold tracking-wide', collapsed ? 'lg:hidden' : '']">PRISMA</span>

      <!-- Contraer / expandir (solo escritorio): junto al logo, ya no abajo del todo. -->
      <button
        type="button"
        :class="[
          'hidden lg:flex items-center justify-center w-8 h-8 rounded-full bg-slate-700 hover:bg-slate-600 transition-colors shrink-0',
          collapsed ? '' : 'ml-auto',
        ]"
        :title="collapsed ? 'Expandir menú' : 'Contraer menú'"
        :aria-label="collapsed ? 'Expandir menú' : 'Contraer menú'"
        @click="$emit('toggle-collapse')"
      >
        <ChevronLeftIcon :class="['w-4 h-4 transition-transform', collapsed ? 'rotate-180' : '']" />
      </button>

      <!-- Cerrar (solo móvil / tableta, donde el menú es un cajón). -->
      <button
        type="button"
        class="lg:hidden ml-auto p-1.5 rounded-lg text-slate-300 hover:bg-slate-800 hover:text-white transition-colors"
        title="Cerrar menú"
        aria-label="Cerrar menú"
        @click="emit('close-mobile')"
      >
        <XMarkIcon class="w-6 h-6" />
      </button>
    </div>

    <nav class="flex-1 min-h-0 mt-4 mb-2 px-2 space-y-1 overflow-y-auto">
      <router-link
        v-for="item in filteredMenuItems"
        :key="item.to"
        :to="item.to"
        :class="[
          'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors',
          isActive(item.to)
            ? 'bg-blue-600 text-white'
            : 'text-slate-300 hover:bg-slate-800 hover:text-white',
        ]"
        :title="collapsed ? item.label : undefined"
        @click="emit('close-mobile')"
      >
        <component :is="item.icon" class="w-5 h-5 shrink-0" />
        <span :class="collapsed ? 'lg:hidden' : ''">{{ item.label }}</span>
      </router-link>
    </nav>
  </aside>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { useRoute } from 'vue-router';
  import { useAuthStore } from '@/stores/auth';
  import type { UserRole } from '@/types';
  import {
    HomeIcon,
    BuildingOffice2Icon,
    UsersIcon,
    ClipboardDocumentCheckIcon,
    BookOpenIcon,
    ChartBarIcon,
    AdjustmentsHorizontalIcon,
    Cog6ToothIcon,
    ClockIcon,
    XMarkIcon,
  } from '@heroicons/vue/24/outline';
  import { ShieldCheckIcon, ChevronLeftIcon } from '@heroicons/vue/24/solid';

  defineProps<{
    collapsed: boolean;
    mobileOpen: boolean;
  }>();

  const emit = defineEmits<{
    'toggle-collapse': [];
    'close-mobile': [];
  }>();

  const route = useRoute();
  const authStore = useAuthStore();

  const menuItems = [
    { to: '/dashboard', label: 'Panel', icon: HomeIcon, roles: [] },
    {
      to: '/organizations',
      label: 'Organizaciones',
      icon: BuildingOffice2Icon,
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'],
    },
    {
      to: '/users',
      label: 'Usuarios',
      icon: UsersIcon,
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'],
    },
    { to: '/evaluations', label: 'Evaluaciones', icon: ClipboardDocumentCheckIcon, roles: [] },
    { to: '/catalog', label: 'Catálogos', icon: BookOpenIcon, roles: [] },
    {
      to: '/community-profiles',
      label: 'Perfiles Comunitarios',
      icon: AdjustmentsHorizontalIcon,
      roles: ['PRISMA_ADMIN'],
    },
    { to: '/reports', label: 'Reportes', icon: ChartBarIcon, roles: [] },
    {
      to: '/activity',
      label: 'Actividad del Sistema',
      icon: ClockIcon,
      roles: ['PRISMA_ADMIN', 'ORG_RESPONSIBLE'],
    },
    { to: '/settings', label: 'Configuración', icon: Cog6ToothIcon, roles: ['PRISMA_ADMIN'] },
  ];

  const filteredMenuItems = computed(() => {
    return menuItems.filter((item) => {
      if (item.roles.length === 0) return true;
      return authStore.hasAnyRole(...(item.roles as UserRole[]));
    });
  });

  function isActive(path: string): boolean {
    return route.path.startsWith(path);
  }
</script>
