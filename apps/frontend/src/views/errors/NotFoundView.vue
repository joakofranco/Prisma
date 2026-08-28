<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 flex items-center justify-center p-4">
    <div class="text-center">
      <h1 class="text-6xl font-bold text-slate-300">404</h1>
      <p class="text-xl text-slate-600 dark:text-slate-300 mt-4">Página no encontrada</p>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-2">La página que busca no existe o fue movida.</p>
      <router-link
        to="/dashboard"
        class="inline-flex items-center gap-2 mt-6 px-4 py-2 bg-blue-600 text-white text-sm font-medium rounded-lg hover:bg-blue-700"
      >
        <HomeIcon class="w-4 h-4" />
        Volver al Panel
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { HomeIcon } from '@heroicons/vue/24/outline';
</script>
