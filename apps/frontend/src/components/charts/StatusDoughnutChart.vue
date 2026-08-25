<template>
  <div class="relative" style="height: 220px">
    <Doughnut :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Doughnut } from 'vue-chartjs';
  import { useThemeStore } from '@/stores/theme';
  import { EVALUATION_STATUS_CONFIG } from '@/utils/constants';
  import type { EvaluationStatus } from '@/types';

  const props = defineProps<{
    data: Partial<Record<EvaluationStatus, number>>;
  }>();

  // Chart.js dibuja en un <canvas>: no lee CSS ni las clases "dark:" de Tailwind, así que sin
  // esto la leyenda quedaba con el gris oscuro por defecto de Chart.js -- ilegible sobre la
  // tarjeta oscura (bg-slate-800) del modo oscuro. chartOptions es "computed" (no una constante)
  // para que el color se actualice solo apenas se toca el toggle de tema, sin recargar la página.
  const themeStore = useThemeStore();

  const statusOrder = Object.keys(EVALUATION_STATUS_CONFIG) as EvaluationStatus[];

  const chartData = computed(() => {
    const labels: string[] = [];
    const values: number[] = [];
    const colors: string[] = [];
    for (const status of statusOrder) {
      const count = props.data?.[status] ?? 0;
      if (count === 0) continue;
      labels.push(EVALUATION_STATUS_CONFIG[status].label);
      values.push(count);
      colors.push(EVALUATION_STATUS_CONFIG[status].color);
    }
    return {
      labels,
      datasets: [{ data: values, backgroundColor: colors, borderWidth: 0, hoverOffset: 6 }],
    };
  });

  const chartOptions = computed(() => ({
    responsive: true,
    maintainAspectRatio: false,
    cutout: '65%',
    plugins: {
      legend: {
        position: 'bottom' as const,
        labels: {
          boxWidth: 10,
          padding: 12,
          font: { size: 11 },
          color: themeStore.theme === 'dark' ? '#cbd5e1' : '#475569', // slate-300 / slate-600
        },
      },
    },
  }));
</script>
