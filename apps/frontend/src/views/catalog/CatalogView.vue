<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Catálogos</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
        Estructura de funciones, categorías, subcategorías, requisitos y controles de cada
        catálogo disponible
      </p>
    </div>

    <!-- Una pestaña por cada catálogo dado de alta (ver CatalogImportPanel.vue, Configuración):
         cambiar de pestaña carga ese catálogo completo, con sus propias funciones/categorías/
         subcategorías/requisitos/controles -- reemplaza el selector desplegable que había antes,
         que ocultaba la existencia de otros catálogos hasta que se abría. -->
    <div
      v-if="versionOptions.length > 0"
      class="flex gap-1 border-b border-slate-200 dark:border-slate-700 overflow-x-auto"
    >
      <button
        v-for="opt in versionOptions"
        :key="opt.value"
        type="button"
        :class="[
          'px-4 py-2.5 text-sm font-medium border-b-2 -mb-px whitespace-nowrap transition-colors',
          selectedVersion === opt.value
            ? 'border-blue-600 text-blue-600 dark:text-blue-400'
            : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:border-slate-300 dark:hover:border-slate-600',
        ]"
        @click="selectedVersion = opt.value"
      >
        {{ opt.label }}
      </button>
    </div>

    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div v-else class="space-y-4">
      <div
        v-for="func in catalogFunctions"
        :key="func.id"
        class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden"
      >
        <button
          class="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
          @click="toggleFunction(func.id)"
        >
          <div class="flex items-center gap-3">
            <span
              class="w-8 h-8 rounded-lg bg-blue-50 dark:bg-blue-950 flex items-center justify-center text-sm font-bold text-blue-700 dark:text-blue-300"
            >
              {{ func.name.charAt(0) }}
            </span>
            <span class="font-semibold text-slate-900 dark:text-white">{{ func.name }}</span>
          </div>
          <div class="flex items-center gap-3">
            <span class="text-xs text-slate-400">{{ func.categories.length }} categorías</span>
            <ChevronDownIcon
              :class="[
                'w-5 h-5 text-slate-400 transition-transform',
                expandedFunctions.has(func.id) ? 'rotate-180' : '',
              ]"
            />
          </div>
        </button>

        <div
          v-if="expandedFunctions.has(func.id)"
          class="border-t border-slate-100 dark:border-slate-700 px-6 py-4 space-y-4"
        >
          <p class="text-sm text-slate-600 dark:text-slate-300">
            {{ func.description }}
          </p>

          <div v-for="category in func.categories" :key="category.id" class="ml-4">
            <h4 class="text-sm font-semibold text-slate-800 dark:text-slate-100 mb-2">
              {{ category.name }}
            </h4>
            <p class="text-xs text-slate-500 dark:text-slate-400 mb-3">
              {{ category.description }}
            </p>

            <div
              v-for="subcategory in category.subcategories"
              :key="subcategory.id"
              class="ml-4 mb-3 p-3 bg-slate-50 dark:bg-slate-900 rounded-lg"
            >
              <h5 class="text-xs font-medium text-slate-700 dark:text-slate-200 mb-2">
                {{ subcategory.name }}
              </h5>
              <div v-for="req in subcategory.requirements" :key="req.id" class="ml-3 mb-2">
                <p class="text-xs text-slate-600 dark:text-slate-300 font-medium">
                  {{ req.code }} — {{ req.description }}
                </p>
                <div
                  v-for="control in req.controls"
                  :key="control.id"
                  class="ml-4 mt-1 flex items-center gap-2"
                >
                  <span class="w-1.5 h-1.5 rounded-full bg-slate-400" />
                  <span class="text-xs text-slate-500 dark:text-slate-400"
                    >{{ control.code }}: {{ control.description }}</span
                  >
                  <span class="text-xs text-slate-400">(Objetivo: {{ control.targetLevel }})</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, onMounted, watch } from 'vue';
  import { catalogService } from '@/services/resources';
  import { ChevronDownIcon } from '@heroicons/vue/24/outline';
  import type { MaturityFunction, MaturityCatalog } from '@/types';

  const loading = ref(true);
  const catalogFunctions = ref<MaturityFunction[]>([]);
  const expandedFunctions = ref<Set<string>>(new Set());
  const versionOptions = ref<{ value: string; label: string }[]>([]);
  const selectedVersion = ref('5.0');

  function toggleFunction(id: string) {
    if (expandedFunctions.value.has(id)) {
      expandedFunctions.value.delete(id);
    } else {
      expandedFunctions.value.add(id);
    }
  }

  async function loadCatalog(version: string) {
    loading.value = true;
    try {
      const { data } = await catalogService.getByVersion(version);
      catalogFunctions.value = (data as MaturityCatalog).functions || [];
    } catch {
      catalogFunctions.value = [
        {
          id: '1',
          name: 'Gestión de Riesgos',
          description: 'Identificación, evaluación y tratamiento de riesgos de seguridad.',
          categories: [
            {
              id: '1.1',
              name: 'Evaluación de Riesgos',
              description: 'Procesos de identificación y valoración de amenazas.',
              subcategories: [
                {
                  id: '1.1.1',
                  name: 'Identificación de Activos',
                  description: 'Identificación y valoración de los activos de información.',
                  requirements: [
                    {
                      id: 'r1',
                      code: 'R-CRI-01',
                      description: 'Identificar activos de información',
                      controls: [
                        {
                          id: 'c1',
                          code: 'C-CRI-01',
                          description: 'Mantenimiento de inventario de activos',
                          targetLevel: 3,
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
        {
          id: '2',
          name: 'Seguridad de la Información',
          description: 'Protección de la confidencialidad, integridad y disponibilidad.',
          categories: [],
        },
        {
          id: '3',
          name: 'Seguridad de las Operaciones',
          description: 'Gestión segura de operaciones tecnológicas.',
          categories: [],
        },
      ];
    } finally {
      loading.value = false;
    }
  }

  // immediate:true dispara la primera carga (con la versión inicial '5.0') -- por eso onMounted
  // de abajo no llama a loadCatalog directo, solo puede reasignar selectedVersion si hace falta,
  // y ese cambio ya dispara este mismo watcher. Evita el doble fetch de tener las dos llamadas.
  watch(selectedVersion, (version) => loadCatalog(version), { immediate: true });

  onMounted(async () => {
    try {
      const { data } = await catalogService.getVersions();
      versionOptions.value = data.versions.map((v) => ({ value: v, label: `MCU ${v}` }));
      if (data.versions.length > 0 && !data.versions.includes(selectedVersion.value)) {
        selectedVersion.value = data.versions[0];
      }
    } catch {
      // Sin conexión al backend: se sigue con la versión 5.0 por defecto y el catálogo de
      // ejemplo hardcodeado más abajo (ver el catch de loadCatalog).
    }
  });
</script>
