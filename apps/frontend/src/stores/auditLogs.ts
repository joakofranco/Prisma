import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { AuditLog } from '@/types';
import { auditLogsService } from '@/services/resources';

export const useAuditLogsStore = defineStore('auditLogs', () => {
  const logs = ref<AuditLog[]>([]);
  const total = ref(0);
  const loading = ref(false);

  async function fetchAuditLogs(page = 1, pageSize = 20, search = '', action = '') {
    loading.value = true;
    try {
      const { data } = await auditLogsService.getAll({
        page,
        pageSize,
        search: search || undefined,
        action: action || undefined,
      });
      logs.value = data.data;
      total.value = data.total;
    } catch (err) {
      console.error('Error fetching audit logs:', err);
      throw err;
    } finally {
      loading.value = false;
    }
  }

  return { logs, total, loading, fetchAuditLogs };
});
