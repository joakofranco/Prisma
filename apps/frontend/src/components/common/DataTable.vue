<template>
  <div class="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800">
    <div v-if="loading" class="flex items-center justify-center py-12">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600" />
    </div>

    <div v-else-if="data.length === 0" class="py-12 text-center">
      <slot name="empty">
        <p class="text-slate-500 dark:text-slate-400">No hay datos disponibles</p>
      </slot>
    </div>

    <div v-else class="overflow-x-auto">
      <table class="min-w-full divide-y divide-slate-200 dark:divide-slate-700">
        <thead class="bg-slate-50 dark:bg-slate-900">
          <tr>
            <th
              v-for="column in columns"
              :key="column.key"
              :class="[
                'px-4 py-3 text-left text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider',
                column.align === 'right' ? 'text-right' : '',
                column.align === 'center' ? 'text-center' : '',
              ]"
            >
              {{ column.label }}
            </th>
            <th
              v-if="$slots.actions"
              class="px-4 py-3 text-right text-xs font-semibold text-slate-600 dark:text-slate-300 uppercase tracking-wider"
            >
              Acciones
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
          <tr
            v-for="(row, index) in data"
            :key="getRowKey(row, index)"
            class="hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
          >
            <td
              v-for="column in columns"
              :key="column.key"
              :class="[
                'px-4 py-3 text-sm text-slate-700 dark:text-slate-200',
                column.align === 'right' ? 'text-right' : '',
                column.align === 'center' ? 'text-center' : '',
              ]"
            >
              <slot :name="`cell-${column.key}`" :row="row" :value="getCellValue(row, column.key)">
                {{ getCellValue(row, column.key) }}
              </slot>
            </td>
            <td v-if="$slots.actions" class="px-4 py-3 text-right">
              <div class="flex items-center justify-end gap-2">
                <slot name="actions" :row="row" />
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="showPagination && totalPages > 1"
      class="flex items-center justify-between px-4 py-3 border-t border-slate-200 dark:border-slate-700"
    >
      <p class="text-sm text-slate-600 dark:text-slate-300">
        Mostrando {{ (currentPage - 1) * pageSize + 1 }} a
        {{ Math.min(currentPage * pageSize, totalItems) }} de {{ totalItems }}
      </p>
      <div class="flex items-center gap-1">
        <button
          :disabled="currentPage === 1"
          class="px-3 py-1.5 text-sm rounded-lg border border-slate-300 dark:border-slate-600 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed"
          @click="$emit('page-change', currentPage - 1)"
        >
          Anterior
        </button>
        <button
          v-for="page in visiblePages"
          :key="page"
          :class="[
            'px-3 py-1.5 text-sm rounded-lg border',
            page === currentPage
              ? 'bg-blue-600 text-white border-blue-600'
              : 'border-slate-300 dark:border-slate-600 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700',
          ]"
          @click="$emit('page-change', page)"
        >
          {{ page }}
        </button>
        <button
          :disabled="currentPage === totalPages"
          class="px-3 py-1.5 text-sm rounded-lg border border-slate-300 dark:border-slate-600 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed"
          @click="$emit('page-change', currentPage + 1)"
        >
          Siguiente
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts" generic="T extends object">
  import { computed } from 'vue';

  interface Column {
    key: string;
    label: string;
    align?: 'left' | 'center' | 'right';
  }

  type Row = Record<string, unknown>;

  const props = withDefaults(
    defineProps<{
      columns: Column[];
      data: T[];
      loading?: boolean;
      showPagination?: boolean;
      currentPage?: number;
      pageSize?: number;
      totalItems?: number;
      rowKey?: string;
    }>(),
    {
      loading: false,
      showPagination: false,
      currentPage: 1,
      pageSize: 10,
      totalItems: 0,
      rowKey: 'id',
    },
  );

  defineEmits<{
    'page-change': [page: number];
  }>();

  const totalPages = computed(() => Math.ceil(props.totalItems / props.pageSize));

  const visiblePages = computed(() => {
    const pages: number[] = [];
    const start = Math.max(1, props.currentPage - 2);
    const end = Math.min(totalPages.value, props.currentPage + 2);
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  });

  function getRowKey(row: T, index: number): string {
    const record = row as unknown as Row;
    const id = record[props.rowKey];
    return id !== undefined && id !== null ? String(id) : String(index);
  }

  function getCellValue(row: T, key: string): unknown {
    return key.split('.').reduce<unknown>((obj, part) => {
      if (obj !== null && typeof obj === 'object') {
        return (obj as Row)[part];
      }
      return undefined;
    }, row);
  }
</script>
