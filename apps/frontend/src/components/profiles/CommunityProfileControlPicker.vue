<template>
  <div class="space-y-3">
    <!-- Barra de acciones -->
    <div class="flex flex-wrap items-center gap-2">
      <input
        v-model="search"
        type="search"
        placeholder="Buscar por código o descripción..."
        class="flex-1 min-w-[12rem] px-3 py-1.5 text-sm rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
      <div class="flex items-center gap-1.5 text-xs text-slate-600 dark:text-slate-300">
        <span>Nivel:</span>
        <label
          v-for="lvl in [1, 2, 3, 4]"
          :key="lvl"
          class="flex items-center gap-1 cursor-pointer"
        >
          <input
            v-model="levelFilters[lvl]"
            type="checkbox"
            class="rounded border-slate-300 text-blue-600"
          />
          {{ lvl }}
        </label>
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-2 text-xs">
      <button
        type="button"
        class="px-2 py-1 rounded-md bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600"
        @click="selectVisible"
      >
        Seleccionar visibles ({{ visibleControls.length }})
      </button>
      <button
        type="button"
        class="px-2 py-1 rounded-md bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600"
        @click="clearVisible"
      >
        Limpiar visibles
      </button>
      <span class="mx-1 text-slate-300 dark:text-slate-600">|</span>
      <button
        type="button"
        class="px-2 py-1 rounded-md bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600"
        @click="selectAll"
      >
        Seleccionar todo ({{ controls.length }})
      </button>
      <button
        type="button"
        class="px-2 py-1 rounded-md bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600"
        @click="clearAll"
      >
        Limpiar todo
      </button>
      <span class="ml-auto font-medium text-slate-700 dark:text-slate-200">
        {{ selectedSet.size }} seleccionados
      </span>
    </div>

    <!-- Lista agrupada por dominio -->
    <div
      class="max-h-96 overflow-y-auto border border-slate-200 dark:border-slate-700 rounded-lg divide-y divide-slate-100 dark:divide-slate-700"
    >
      <div v-if="loading" class="text-center py-6 text-sm text-slate-400">
        Cargando controles...
      </div>
      <div v-else-if="groupedDomains.length === 0" class="text-center py-6 text-sm text-slate-400">
        No hay controles que coincidan con el filtro.
      </div>
      <div v-for="grp in groupedDomains" :key="grp.domain">
        <!-- Encabezado del dominio -->
        <div
          class="flex items-center gap-2 px-3 py-2 bg-slate-50 dark:bg-slate-800/60 sticky top-0"
        >
          <input
            type="checkbox"
            class="rounded border-slate-300 text-blue-600"
            :checked="grp.selectedCount > 0 && grp.selectedCount === grp.controls.length"
            :indeterminate.prop="grp.selectedCount > 0 && grp.selectedCount < grp.controls.length"
            @change="toggleDomain(grp)"
          />
          <button
            type="button"
            class="flex-1 flex items-center gap-2 text-left"
            @click="toggleExpanded(grp.domain)"
          >
            <span class="text-slate-400 text-xs w-3">{{
              expanded.has(grp.domain) ? '▾' : '▸'
            }}</span>
            <span class="text-sm font-semibold text-slate-800 dark:text-slate-100">
              {{ grp.domain }} · {{ domainLabel(grp.domain) }}
            </span>
            <span class="text-xs text-slate-500 dark:text-slate-400">
              {{ grp.selectedCount }} / {{ grp.controls.length }}
            </span>
          </button>
        </div>
        <!-- Controles del dominio -->
        <div v-if="expanded.has(grp.domain)" class="px-3 py-1.5 space-y-0.5">
          <label
            v-for="c in grp.controls"
            :key="c.id"
            class="flex items-start gap-2 py-0.5 cursor-pointer"
          >
            <input
              type="checkbox"
              class="mt-0.5 rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500"
              :checked="selectedSet.has(c.id)"
              @change="toggleControl(c.id)"
            />
            <span class="text-xs text-slate-600 dark:text-slate-300">
              <span class="font-medium text-slate-700 dark:text-slate-200">{{ c.code }}</span>
              <span
                class="ml-1 inline-block px-1 rounded bg-slate-100 dark:bg-slate-700 text-[10px]"
                >N{{ c.targetLevel }}</span
              >
              — {{ c.description }}
            </span>
          </label>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, reactive, ref } from 'vue';
  import type { CatalogControlFlat } from '@/types';
  import { MCU_DOMAIN_LABELS } from '@/utils/constants';

  const props = defineProps<{
    controls: CatalogControlFlat[];
    modelValue: string[];
    loading?: boolean;
  }>();

  const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();

  const search = ref('');
  const levelFilters = reactive<Record<number, boolean>>({ 1: true, 2: true, 3: true, 4: true });
  const expanded = reactive(new Set<string>());

  const selectedSet = computed(() => new Set(props.modelValue));

  function emitSet(set: Set<string>) {
    emit('update:modelValue', [...set]);
  }

  function domainLabel(domain: string): string {
    return MCU_DOMAIN_LABELS[domain] ?? domain;
  }

  // Controles que pasan el buscador + filtro de nivel
  const visibleControls = computed(() => {
    const q = search.value.trim().toLowerCase();
    return props.controls.filter((c) => {
      if (!levelFilters[c.targetLevel]) return false;
      if (!q) return true;
      return c.code.toLowerCase().includes(q) || c.description.toLowerCase().includes(q);
    });
  });

  const groupedDomains = computed(() => {
    const map = new Map<string, CatalogControlFlat[]>();
    for (const c of visibleControls.value) {
      const arr = map.get(c.domain) ?? [];
      arr.push(c);
      map.set(c.domain, arr);
    }
    return [...map.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([domain, controls]) => ({
        domain,
        controls,
        selectedCount: controls.reduce((n, c) => n + (selectedSet.value.has(c.id) ? 1 : 0), 0),
      }));
  });

  function toggleExpanded(domain: string) {
    if (expanded.has(domain)) expanded.delete(domain);
    else expanded.add(domain);
  }

  function toggleControl(id: string) {
    const set = new Set(props.modelValue);
    if (set.has(id)) set.delete(id);
    else set.add(id);
    emitSet(set);
  }

  function toggleDomain(grp: { controls: CatalogControlFlat[]; selectedCount: number }) {
    const set = new Set(props.modelValue);
    const selectAllInDomain = grp.selectedCount < grp.controls.length;
    for (const c of grp.controls) {
      if (selectAllInDomain) set.add(c.id);
      else set.delete(c.id);
    }
    emitSet(set);
  }

  function selectVisible() {
    const set = new Set(props.modelValue);
    for (const c of visibleControls.value) set.add(c.id);
    emitSet(set);
  }

  function clearVisible() {
    const visibleIds = new Set(visibleControls.value.map((c) => c.id));
    emitSet(new Set([...props.modelValue].filter((id) => !visibleIds.has(id))));
  }

  function selectAll() {
    emitSet(new Set(props.controls.map((c) => c.id)));
  }

  function clearAll() {
    emitSet(new Set());
  }
</script>
