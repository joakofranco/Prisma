<template>
  <div class="relative" style="height: 280px">
    <Line :data="chartData" :options="chartOptions" :aria-label="ariaLabel" role="img" />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Line } from 'vue-chartjs';
  import type { ActiveElement, ChartEvent, TooltipItem } from 'chart.js';
  import { useThemeStore } from '@/stores/theme';
  import { formatDate, getMaturityLabel, getStatusLabel } from '@/utils/helpers';
  import { formatMeasure, type TrendMetric, type TrendSeries } from '@/utils/maturityTrend';
  import type { MaturityTrendPoint } from '@/types';

  // RF-VIZ-07 (PRI-321): una línea por perfil sobre un eje de fechas de aprobación compartido.
  // Cada serie trae `null` en las fechas que no son suyas; spanGaps une sus propios puntos. Lo
  // provisorio (en curso o en auditoría) va con punto hueco y el tramo que llega a él punteado:
  // no se confunde avance declarado con resultado auditado.
  const props = defineProps<{
    labels: string[];
    series: TrendSeries[];
    metric: TrendMetric;
  }>();

  const emit = defineEmits<{ select: [evaluationId: string] }>();

  const themeStore = useThemeStore();

  const value = (p: MaturityTrendPoint | null) =>
    p == null ? null : props.metric === 'compliance' ? p.compliancePercent : p.maturityAverage;

  const isProvisional = (s: TrendSeries, i: number) => !!s.points[i]?.provisional;
  const pointFill = computed(() => (themeStore.theme === 'dark' ? '#1e293b' : '#ffffff'));

  const chartData = computed(() => ({
    labels: props.labels,
    datasets: props.series.map((s) => ({
      label: s.label,
      data: s.points.map(value),
      borderColor: s.color,
      backgroundColor: s.color,
      pointBackgroundColor: s.points.map((_, i) =>
        isProvisional(s, i) ? pointFill.value : s.color,
      ),
      pointBorderColor: s.color,
      pointBorderWidth: s.points.map((_, i) => (isProvisional(s, i) ? 3 : 1)),
      segment: {
        borderDash: (ctx: { p1DataIndex: number }) =>
          isProvisional(s, ctx.p1DataIndex) ? [7, 6] : undefined,
      },
      pointStyle: s.pointStyle,
      pointRadius: 6,
      pointHoverRadius: 8,
      borderWidth: 3,
      tension: 0,
      spanGaps: true,
    })),
  }));

  // Texto equivalente para lectores de pantalla: cada perfil con sus valores y fechas.
  const ariaLabel = computed(() => {
    const unit = props.metric === 'compliance' ? '% de controles cumplidos' : 'de madurez';
    const parts = props.series.map((s) => {
      const values = s.points
        .map((p, i) =>
          p ? `${props.labels[i]} ${formatMeasure(value(p) ?? 0, props.metric)}` : null,
        )
        .filter(Boolean)
        .join(', ');
      return `${s.label}: ${values}`;
    });
    return `Tendencia ${unit} por perfil. ${parts.join('. ') || 'Sin datos'}`;
  });

  function pointAt(datasetIndex: number, index: number): MaturityTrendPoint | null {
    return props.series[datasetIndex]?.points[index] ?? null;
  }

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const tickColor = isDark ? '#cbd5e1' : '#475569'; // slate-300 / slate-600
    const gridColor = isDark ? '#334155' : '#f1f5f9'; // slate-700 / slate-100
    const compliance = props.metric === 'compliance';
    return {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'nearest' as const, intersect: true },
      scales: {
        y: {
          min: 0,
          max: compliance ? 100 : 4,
          ticks: {
            stepSize: compliance ? 25 : 1,
            color: tickColor,
            callback: (v: string | number) =>
              compliance ? `${v} %` : `${v} · ${getMaturityLabel(Number(v))}`,
          },
          grid: { color: gridColor },
        },
        x: { ticks: { color: tickColor }, grid: { display: false } },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: (items: TooltipItem<'line'>[]) => {
              const p = items[0] ? pointAt(items[0].datasetIndex, items[0].dataIndex) : null;
              return p ? `${p.evaluationName} · ${p.profileName}` : '';
            },
            label: (item: TooltipItem<'line'>) => {
              const p = pointAt(item.datasetIndex, item.dataIndex);
              if (!p) return '';
              return [
                p.provisional || !p.approvedAt
                  ? `Provisoria · ${getStatusLabel(p.status)}, sin auditar (valor de hoy)`
                  : `Aprobada el ${formatDate(p.approvedAt)}`,
                `${p.compliantControls} de ${p.totalControls} controles cumplidos (${formatMeasure(p.compliancePercent, 'compliance')})`,
                `Madurez ${formatMeasure(p.maturityAverage, 'maturity')} · Nivel ${p.globalMaturity ?? 0} (${getMaturityLabel(p.globalMaturity ?? 0)})`,
              ];
            },
            footer: () => 'Clic para abrir la evaluación',
          },
        },
      },
      onClick: (_e: ChartEvent, elements: ActiveElement[]) => {
        const hit = elements[0];
        const p = hit ? pointAt(hit.datasetIndex, hit.index) : null;
        if (p) emit('select', p.evaluationId);
      },
    };
  });
</script>
