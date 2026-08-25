<template>
  <Teleport to="body">
    <div class="fixed bottom-4 right-4 z-[100] space-y-2 pointer-events-none">
      <TransitionGroup
        enter-active-class="transition ease-out duration-300"
        enter-from-class="translate-x-full opacity-0"
        enter-to-class="translate-x-0 opacity-100"
        leave-active-class="transition ease-in duration-200"
        leave-from-class="translate-x-0 opacity-100"
        leave-to-class="translate-x-full opacity-0"
      >
        <div
          v-for="n in notifications"
          :key="n.id"
          :class="[
            'pointer-events-auto max-w-sm w-full bg-white dark:bg-slate-800 shadow-lg rounded-xl border p-4 flex items-start gap-3',
            n.type === 'success' && 'border-emerald-200 dark:border-emerald-800',
            n.type === 'error' && 'border-red-200 dark:border-red-800',
            n.type === 'warning' && 'border-amber-200 dark:border-amber-800',
            n.type === 'info' && 'border-blue-200 dark:border-blue-800',
          ]"
        >
          <component :is="getIcon(n.type)" :class="['w-5 h-5 shrink-0', iconColor(n.type)]" />
          <p class="text-sm text-slate-700 dark:text-slate-200 flex-1">
            {{ n.message }}
          </p>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
  import {
    CheckCircleIcon,
    ExclamationCircleIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
  } from '@heroicons/vue/24/outline';
  import type { Component } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useNotificationStore } from '@/stores/notification';

  // Ver el comentario en OrganizationsView.vue: hay que usar storeToRefs, no desestructurar el
  // store directamente. Acá el síntoma era peor que "no reacciona": dismiss() reasigna
  // notifications.value a un array NUEVO (notifications.value.filter(...)) en vez de mutar el
  // mismo array in place. Con `const notifications = store.notifications` el template quedaba
  // apuntando para siempre al array original -- el primer toast que se auto-cerraba nunca
  // desaparecía del DOM (quedaba "freezado" arriba a la derecha) y ningún toast posterior volvía
  // a aparecer, porque el componente ya no veía el array nuevo donde se van agregando. El div
  // freezado, con pointer-events-auto y z-[100] (por encima del menú de usuario en z-50), tapaba
  // los clics sobre el botón de perfil/cerrar sesión del header.
  const store = useNotificationStore();
  const { notifications } = storeToRefs(store);

  function getIcon(type: string): Component {
    return (
      {
        success: CheckCircleIcon,
        error: ExclamationCircleIcon,
        warning: ExclamationTriangleIcon,
        info: InformationCircleIcon,
      }[type] || InformationCircleIcon
    );
  }

  function iconColor(type: string): string {
    return (
      {
        success: 'text-emerald-500',
        error: 'text-red-500',
        warning: 'text-amber-500',
        info: 'text-blue-500',
      }[type] || 'text-slate-500 dark:text-slate-400'
    );
  }
</script>
