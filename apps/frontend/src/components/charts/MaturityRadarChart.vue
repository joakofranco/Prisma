<template>
  <div class="relative" style="height: 320px">
    <Radar :data="chartData" :options="chartOptions" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Radar } from 'vue-chartjs';
  import { useThemeStore } from '@/stores/theme';

  const props = defineProps<{
    items: { name: string; current: number; target: number }[];
  }>();

  // Ver el comentario en StatusDoughnutChart.vue: Chart.js dibuja en <canvas>, no sigue el CSS de
  // Tailwind.
  const themeStore = useThemeStore();

  const chartData = computed(() => ({
    labels: props.items.map((i) => i.name),
    datasets: [
      {
        label: 'Nivel actual',
        data: props.items.map((i) => i.current),
        backgroundColor: 'rgba(37, 99, 235, 0.2)',
        borderColor: '#2563eb',
        pointBackgroundColor: '#2563eb',
      },
      {
        label: 'Nivel objetivo',
        data: props.items.map((i) => i.target),
        backgroundColor: 'rgba(148, 163, 184, 0.15)',
        borderColor: '#94a3b8',
        borderDash: [4, 4],
        pointBackgroundColor: '#94a3b8',
      },
    ],
  }));

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const textColor = isDark ? '#cbd5e1' : '#475569'; // slate-300 / slate-600
    return {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        r: {
          min: 0,
          max: 4,
          ticks: { stepSize: 1, backdropColor: 'transparent', color: textColor },
          pointLabels: { font: { size: 11 }, color: textColor },
          grid: { color: isDark ? '#334155' : '#e2e8f0' }, // slate-700 / slate-200
          angleLines: { color: isDark ? '#334155' : '#e2e8f0' },
        },
      },
      plugins: {
        legend: {
          position: 'bottom' as const,
          labels: { boxWidth: 10, padding: 12, font: { size: 11 }, color: textColor },
        },
      },
    };
  });
</script>
