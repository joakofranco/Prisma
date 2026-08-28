<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Configuración</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Administración global de la plataforma</p>
    </div>

    <div class="flex flex-col md:flex-row gap-6">
      <nav class="md:w-56 shrink-0">
        <ul class="flex md:flex-col gap-1 overflow-x-auto md:overflow-visible">
          <li v-for="tab in tabs" :key="tab.key">
            <button
              type="button"
              :class="[
                'w-full text-left px-3 py-2 rounded-lg text-sm font-medium whitespace-nowrap flex items-center gap-2',
                activeTab === tab.key
                  ? 'bg-blue-50 text-blue-700'
                  : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700',
              ]"
              @click="activeTab = tab.key"
            >
              <component :is="tab.icon" class="w-4 h-4 shrink-0" />
              {{ tab.label }}
            </button>
          </li>
        </ul>
      </nav>

      <div class="flex-1 min-w-0">
        <EmailSettingsPanel v-if="activeTab === 'email'" />
        <CatalogImportPanel v-else-if="activeTab === 'catalog'" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref } from 'vue';
  import { EnvelopeIcon, BookOpenIcon } from '@heroicons/vue/24/outline';
  import EmailSettingsPanel from '@/components/settings/EmailSettingsPanel.vue';
  import CatalogImportPanel from '@/components/settings/CatalogImportPanel.vue';

  type TabKey = 'email' | 'catalog';

  const tabs: { key: TabKey; label: string; icon: unknown }[] = [
    { key: 'email', label: 'Correo electrónico', icon: EnvelopeIcon },
    { key: 'catalog', label: 'Catálogo', icon: BookOpenIcon },
  ];

  const activeTab = ref<TabKey>('email');
</script>
