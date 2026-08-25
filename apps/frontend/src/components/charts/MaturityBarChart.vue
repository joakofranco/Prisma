<template>
  <div class="relative" style="height: 260px">
    <Bar :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Bar } from 'vue-chartjs';
  import type { TooltipItem } from 'chart.js';
  import { useThemeStore } from '@/stores/theme';
  import { getMaturityColor } from '@/utils/helpers';

  const props = defineProps<{
    items: { name: string; level: number }[];
  }>();

  // Ver el comentario en StatusDoughnutChart.vue: Chart.js dibuja en <canvas>, no sigue el CSS de
  // Tailwind -- sin esto los números del eje quedaban con el gris oscuro por defecto de Chart.js,
  // ilegibles sobre la tarjeta oscura del modo oscuro.
  const themeStore = useThemeStore();

  const chartData = computed(() => ({
    labels: props.items.map((i) => i.name),
    datasets: [
      {
        label: 'Nivel de madurez',
        data: props.items.map((i) => i.level),
        backgroundColor: props.items.map((i) => getMaturityColor(i.level)),
        borderRadius: 4,
        maxBarThickness: 28,
      },
    ],
  }));

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const tickColor = isDark ? '#cbd5e1' : '#475569'; // slate-300 / slate-600
    return {
      indexAxis: 'y' as const,
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: {
          min: 0,
          max: 4,
          ticks: { stepSize: 1, color: tickColor },
          grid: { color: isDark ? '#334155' : '#f1f5f9' }, // slate-700 / slate-100
        },
        y: { ticks: { color: tickColor }, grid: { display: false } },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx: TooltipItem<'bar'>) => `Nivel ${ctx.parsed.x}/4`,
          },
        },
      },
    };
  });
</script>
