<template>
  <div class="relative" style="height: 220px">
    <Bar :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Bar } from 'vue-chartjs';
  import type { TooltipItem } from 'chart.js';
  import { useThemeStore } from '@/stores/theme';
  import { getMaturityColor } from '@/utils/helpers';
  import type { Evaluation } from '@/types';

  const props = defineProps<{
    evaluations: Evaluation[];
  }>();

  // Ver el comentario en StatusDoughnutChart.vue: Chart.js dibuja en <canvas>, no sigue el CSS de
  // Tailwind.
  const themeStore = useThemeStore();

  // Orden cronológico ascendente para leer la evolución de izquierda a derecha.
  const ordered = computed(() =>
    [...props.evaluations].sort(
      (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
    ),
  );

  const chartData = computed(() => ({
    labels: ordered.value.map((e) => e.name),
    datasets: [
      {
        label: 'Nivel de madurez global',
        data: ordered.value.map((e) => e.globalMaturity ?? 0),
        backgroundColor: ordered.value.map((e) => getMaturityColor(e.globalMaturity ?? 0)),
        borderRadius: 4,
        maxBarThickness: 36,
      },
    ],
  }));

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const tickColor = isDark ? '#cbd5e1' : '#475569'; // slate-300 / slate-600
    return {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        y: {
          min: 0,
          max: 4,
          ticks: { stepSize: 1, color: tickColor },
          grid: { color: isDark ? '#334155' : '#f1f5f9' }, // slate-700 / slate-100
        },
        x: { ticks: { color: tickColor }, grid: { display: false } },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx: TooltipItem<'bar'>) => `Nivel ${ctx.parsed.y}/4`,
          },
        },
      },
    };
  });
</script>
