<template>
  <div class="space-y-6">
    <div>
      <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Actividad del Sistema</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
        {{
          isPlatformAdmin
            ? 'Bitácora de acciones realizadas en todas las organizaciones'
            : 'Bitácora de acciones realizadas en tu organización'
        }}
      </p>
    </div>

    <div class="flex flex-col sm:flex-row items-stretch sm:items-center gap-3">
      <div class="relative flex-1 max-w-md">
        <MagnifyingGlassIcon
          class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400"
        />
        <input
          v-model="searchTerm"
          type="text"
          placeholder="Buscar por acción o recurso..."
          class="w-full pl-10 pr-4 py-2 rounded-lg border border-slate-300 dark:border-slate-600 dark:bg-slate-700 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          @input="debouncedSearch"
        />
      </div>
      <select
        v-model="actionFilter"
        class="px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-600 dark:bg-slate-700 dark:text-white text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        @change="refresh"
      >
        <option value="">Todas las acciones</option>
        <option v-for="a in KNOWN_ACTIONS" :key="a" :value="a">
          {{ AUDIT_ACTION_LABELS[a] || a }}
        </option>
      </select>
    </div>

    <DataTable
      :columns="columns"
      :data="logs"
      :loading="loading"
      :show-pagination="true"
      :current-page="currentPage"
      :page-size="pageSize"
      :total-items="total"
      @page-change="handlePageChange"
    >
      <template #cell-createdAt="{ value }">
        <span class="text-slate-700 dark:text-slate-200 whitespace-nowrap">{{ formatDateTime(value as string) }}</span>
      </template>
      <template #cell-action="{ value }">
        <span
          :class="[
            'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium',
            actionBadgeClass(value as string),
          ]"
        >
          {{ AUDIT_ACTION_LABELS[value as string] || value }}
        </span>
      </template>
      <template #cell-userEmail="{ row }">
        <span v-if="(row as AuditLog).userEmail" class="text-slate-700 dark:text-slate-200">
          {{ (row as AuditLog).userFullName }}
          <span class="text-slate-400">({{ (row as AuditLog).userEmail }})</span>
        </span>
        <span v-else class="text-slate-400">Usuario eliminado</span>
      </template>
      <template #cell-tenantName="{ value }">
        <span v-if="value" class="text-slate-700 dark:text-slate-200">{{ value }}</span>
        <span v-else class="text-slate-400">—</span>
      </template>
      <template #cell-ipAddress="{ value }">
        <span class="text-slate-500 dark:text-slate-400 text-xs">{{ value || '—' }}</span>
      </template>
      <template #cell-resource="{ row }">
        <span :title="(row as AuditLog).resource" class="text-slate-700 dark:text-slate-200 cursor-help">
          {{ resourceLabel(row as AuditLog) }}
        </span>
      </template>
    </DataTable>
  </div>
</template>

<script setup lang="ts">
  import { ref, computed, onMounted } from 'vue';
  import { storeToRefs } from 'pinia';
  import { useAuditLogsStore } from '@/stores/auditLogs';
  import { useAuthStore } from '@/stores/auth';
  import { formatDateTime } from '@/utils/helpers';
  import { AUDIT_ACTION_LABELS, AUDIT_RESOURCE_TYPE_LABELS } from '@/utils/constants';
  import DataTable from '@/components/common/DataTable.vue';
  import { MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
  import type { AuditLog } from '@/types';

  const store = useAuditLogsStore();
  const authStore = useAuthStore();

  // El backend ya acota la consulta al tenant del usuario para todo rol que no sea PRISMA_ADMIN
  // (ver AuditLogService.list) -- esto sólo decide si mostramos la columna "Organización" (no
  // tiene sentido repetir la misma organización en cada fila para un ORG_RESPONSIBLE).
  const isPlatformAdmin = computed(() => authStore.isAdmin);

  const { logs, total, loading } = storeToRefs(store);
  const currentPage = ref(1);
  const pageSize = 20;
  const searchTerm = ref('');
  const actionFilter = ref('');

  // Acciones que hoy efectivamente registra el backend (ver los usos de AuditLogService.record) --
  // es sólo para poblar el filtro; una acción nueva que se agregue ahí sigue viéndose en la
  // bitácora igual, sólo no aparece como opción del combo hasta que se sume acá también.
  const KNOWN_ACTIONS = [
    'CREATE',
    'UPDATE',
    'DELETE',
    'UPDATE_STATUS',
    'UPDATE_PROFILE',
    'CHANGE_PASSWORD',
    'SAVE_RESPONSE',
    'LOGIN',
    'LOGOUT',
    'LOGIN_FAILED',
  ];

  const columns = computed(() => {
    const base = [
      { key: 'createdAt', label: 'Fecha' },
      { key: 'userEmail', label: 'Usuario' },
      ...(isPlatformAdmin.value ? [{ key: 'tenantName', label: 'Organización' }] : []),
      { key: 'action', label: 'Acción' },
      { key: 'resource', label: 'Recurso Afectado' },
      { key: 'ipAddress', label: 'IP' },
    ];
    return base;
  });

  function actionBadgeClass(action: string): string {
    if (action === 'CREATE') return 'bg-emerald-50 text-emerald-700';
    if (action === 'DELETE') return 'bg-red-50 text-red-700';
    if (action === 'LOGIN') return 'bg-teal-50 text-teal-700';
    if (action === 'LOGOUT') return 'bg-amber-50 text-amber-700';
    if (action === 'LOGIN_FAILED') return 'bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300';
    if (action.startsWith('UPDATE') || action === 'CHANGE_PASSWORD' || action === 'SAVE_RESPONSE')
      return 'bg-blue-50 text-blue-700';
    return 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
  }

  // "resource" siempre llega como "tipo:id" (p.ej. "user:38dab301-...") salvo los singleton sin
  // id propio ("email-settings"). El backend (AuditLogService.resolveResourceName) ya resuelve el
  // nombre real de la entidad cuando puede -- se prioriza eso ("Usuario: Jane Doe") sobre el id
  // crudo acortado, que sólo queda como respaldo si el tipo no tiene resolución (p.ej.
  // "email-settings") o la entidad ya no existe. El string completo siempre queda en el atributo
  // title del span (ver el template) para quien necesite el id exacto.
  function resourceLabel(row: AuditLog): string {
    const resource = row.resource;
    const idx = resource.indexOf(':');
    if (idx === -1) return AUDIT_RESOURCE_TYPE_LABELS[resource] || resource;
    const type = resource.slice(0, idx);
    const id = resource.slice(idx + 1);
    const label = AUDIT_RESOURCE_TYPE_LABELS[type] || type;
    if (row.resourceName) return `${label}: ${row.resourceName}`;
    return `${label} #${id.slice(0, 8)}`;
  }

  function refresh() {
    currentPage.value = 1;
    store.fetchAuditLogs(1, pageSize, searchTerm.value, actionFilter.value);
  }

  let searchTimeout: ReturnType<typeof setTimeout>;
  function debouncedSearch() {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(refresh, 300);
  }

  function handlePageChange(page: number) {
    currentPage.value = page;
    store.fetchAuditLogs(page, pageSize, searchTerm.value, actionFilter.value);
  }

  onMounted(() => {
    store.fetchAuditLogs(1, pageSize);
  });
</script>
