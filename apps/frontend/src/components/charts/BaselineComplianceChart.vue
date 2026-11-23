<template>
  <div class="relative" style="height: 190px">
    <Doughnut :data="chartData" :options="chartOptions" :aria-label="ariaLabel" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Doughnut } from 'vue-chartjs';
  import { useThemeStore } from '@/stores/theme';
  import { describeChart } from '@/utils/helpers';
  import type { ComplianceSlice } from '@/utils/planBaseline';

  const props = defineProps<{ slices: ComplianceSlice[] }>();

  const themeStore = useThemeStore();

  const chartData = computed(() => ({
    labels: props.slices.map((s) => s.label),
    datasets: [
      {
        data: props.slices.map((s) => s.count),
        backgroundColor: props.slices.map((s) => s.color),
        borderWidth: 0,
        hoverOffset: 6,
      },
    ],
  }));

  const ariaLabel = computed(() =>
    describeChart(
      'Cumplimiento contra la fecha original',
      chartData.value.labels,
      chartData.value.datasets,
    ),
  );

  const chartOptions = computed(() => ({
    responsive: true,
    maintainAspectRatio: false,
    cutout: '62%',
    plugins: {
      legend: {
        position: 'right' as const,
        labels: {
          boxWidth: 10,
          padding: 10,
          font: { size: 11 },
          color: themeStore.theme === 'dark' ? '#cbd5e1' : '#475569',
        },
      },
    },
  }));
</script>
