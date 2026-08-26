import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { DashboardStats, EvaluationStatus } from '@/types';
import { dashboardService } from '@/services/resources';

export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<DashboardStats | null>(null);
  const loading = ref(false);

  async function fetchStats() {
    loading.value = true;
    try {
      const { data } = await dashboardService.getStats();
      stats.value = data;
    } catch (err) {
      console.error('Error fetching dashboard stats:', err);
      // Fallback a datos mock en caso de error
      stats.value = {
        totalEvaluations: 24,
        activeOrganizations: 8,
        avgMaturityLevel: 2.7,
        pendingImprovements: 15,
        evaluationsByStatus: {
          DRAFT: 3,
          IN_PROGRESS: 5,
          READY_FOR_AUDIT: 4,
          IN_AUDIT: 2,
          APPROVED: 6,
          RETURNED: 2,
          ARCHIVED: 2,
        } as Record<EvaluationStatus, number>,
        maturityByFunction: [
          { name: 'Gestión de Riesgos', level: 3 },
          { name: 'Seguridad de la Información', level: 2 },
          { name: 'Seguridad de las Operaciones', level: 4 },
          { name: 'Seguridad del Personal', level: 2 },
          { name: 'Gestión de Activos', level: 3 },
          { name: 'Control de Acceso', level: 3 },
          { name: 'Seguridad del Desarrollo', level: 2 },
          { name: 'Seguridad de la Continuidad', level: 3 },
        ],
      };
    } finally {
      loading.value = false;
    }
  }

  return { stats, loading, fetchStats };
});
