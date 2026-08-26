<template>
  <div class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h1 class="text-2xl font-bold text-slate-900 dark:text-white">Panel</h1>
        <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">Resumen general de la plataforma PRISMA</p>
      </div>
      <BaseButton variant="primary" @click="router.push('/evaluations/new')">
        <PlusIcon class="w-4 h-4" />
        Nueva Evaluación
      </BaseButton>
    </div>

    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <StatsCard
        title="Total Evaluaciones"
        :value="stats?.totalEvaluations || 0"
        :icon="ClipboardDocumentCheckIcon"
        icon-bg-class="bg-blue-50"
        icon-color-class="text-blue-600"
      />
      <StatsCard
        title="Organizaciones Activas"
        :value="stats?.activeOrganizations || 0"
        :icon="BuildingOffice2Icon"
        icon-bg-class="bg-emerald-50"
        icon-color-class="text-emerald-600"
      />
      <StatsCard
        title="Nivel de Madurez Promedio"
        :value="stats?.avgMaturityLevel?.toFixed(1) || '0.0'"
        :icon="ChartBarIcon"
        icon-bg-class="bg-amber-50"
        icon-color-class="text-amber-600"
        subtitle="Escala 1-5"
      />
      <StatsCard
        title="Mejoras Pendientes"
        :value="stats?.pendingImprovements || 0"
        :icon="ArrowTrendingUpIcon"
        icon-bg-class="bg-red-50"
        icon-color-class="text-red-600"
      />
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5">
        <h3 class="text-sm font-semibold text-slate-900 dark:text-white mb-4">Evaluaciones por Estado</h3>
        <StatusDoughnutChart v-if="totalByStatus > 0" :data="stats?.evaluationsByStatus || {}" />
        <p v-else class="text-sm text-slate-400 text-center py-16">No hay evaluaciones aún</p>
      </div>

      <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5">
        <h3 class="text-sm font-semibold text-slate-900 dark:text-white mb-4">Madurez por Función</h3>
        <MaturityBarChart
          v-if="stats?.maturityByFunction?.length"
          :items="stats.maturityByFunction"
        />
        <p v-else class="text-sm text-slate-400 text-center py-16">
          Todavía no hay resultados de madurez calculados
        </p>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-5">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-sm font-semibold text-slate-900 dark:text-white">Últimas Evaluaciones</h3>
        <router-link
          to="/evaluations"
          class="text-sm text-blue-600 hover:text-blue-700 font-medium"
        >
          Ver todas →
        </router-link>
      </div>
      <DataTable :columns="evaluationColumns" :data="recentEvaluations" :loading="loading">
        <template #cell-status="{ value }">
          <span
            class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium"
            :style="{
              backgroundColor: getStatusBgColor(value as string),
              color: getStatusColor(value as string),
            }"
          >
            {{ getStatusLabel(value as string) }}
          </span>
        </template>
        <template #cell-createdAt="{ value }">
          {{ formatDate(value as string) }}
        </template>
      </DataTable>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { onMounted, computed } from 'vue';
  import { useRouter } from 'vue-router';
  import { useDashboardStore } from '@/stores/dashboard';
  import { useEvaluationsStore } from '@/stores/evaluations';
  import { getStatusLabel, getStatusColor, getStatusBgColor, formatDate } from '@/utils/helpers';
  import StatsCard from '@/components/common/StatsCard.vue';
  import DataTable from '@/components/common/DataTable.vue';
  import BaseButton from '@/components/common/BaseButton.vue';
  import StatusDoughnutChart from '@/components/charts/StatusDoughnutChart.vue';
  import MaturityBarChart from '@/components/charts/MaturityBarChart.vue';
  import {
    PlusIcon,
    ClipboardDocumentCheckIcon,
    BuildingOffice2Icon,
    ChartBarIcon,
    ArrowTrendingUpIcon,
  } from '@heroicons/vue/24/outline';

  const router = useRouter();
  const dashboardStore = useDashboardStore();
  const evaluationsStore = useEvaluationsStore();

  const loading = computed(() => dashboardStore.loading || evaluationsStore.loading);
  const stats = computed(() => dashboardStore.stats);
  const recentEvaluations = computed(() => evaluationsStore.evaluations.slice(0, 5));
  const totalByStatus = computed(() =>
    Object.values(stats.value?.evaluationsByStatus || {}).reduce(
      (sum, count) => sum + (count || 0),
      0,
    ),
  );

  const evaluationColumns = [
    { key: 'name', label: 'Nombre' },
    { key: 'organizationName', label: 'Organización' },
    { key: 'status', label: 'Estado' },
    { key: 'createdAt', label: 'Fecha' },
  ];

  onMounted(async () => {
    await Promise.all([dashboardStore.fetchStats(), evaluationsStore.fetchEvaluations(1, 5)]);
  });
</script>
