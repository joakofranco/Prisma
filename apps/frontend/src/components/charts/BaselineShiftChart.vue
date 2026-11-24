<template>
  <div class="relative" :style="{ height: `${height}px` }">
    <Bar :data="chartData" :options="chartOptions" :aria-label="ariaLabel" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Bar } from 'vue-chartjs';
  import type { TooltipItem } from 'chart.js';
  import { useThemeStore } from '@/stores/theme';
  import { describeChart } from '@/utils/helpers';
  import { signedDays, type ShiftBar } from '@/utils/planBaseline';

  const props = defineProps<{ bars: ShiftBar[] }>();

  const themeStore = useThemeStore();

  const height = computed(() => Math.max(120, props.bars.length * 26 + 40));

  const chartData = computed(() => ({
    labels: props.bars.map((b) => b.label),
    datasets: [
      {
        label: 'Corrimiento (días)',
        data: props.bars.map((b) => b.days),
        backgroundColor: props.bars.map((b) => (b.days > 0 ? '#dc2626' : '#059669')),
        borderRadius: 4,
        maxBarThickness: 18,
      },
    ],
  }));

  const ariaLabel = computed(() =>
    describeChart(
      'Corrimiento de la fecha límite por acción, en días',
      chartData.value.labels,
      chartData.value.datasets,
    ),
  );

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const tickColor = isDark ? '#cbd5e1' : '#475569';
    return {
      indexAxis: 'y' as const,
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: {
          ticks: { color: tickColor, precision: 0 },
          grid: { color: isDark ? '#334155' : '#f1f5f9' },
        },
        y: { ticks: { color: tickColor }, grid: { display: false } },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx: TooltipItem<'bar'>) => signedDays(ctx.parsed.x as number),
          },
        },
      },
    };
  });
</script>
