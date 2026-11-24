<template>
  <div class="relative" style="height: 300px">
    <Line
      :data="chartData"
      :options="chartOptions"
      :plugins="[statusMarkers]"
      :aria-label="ariaLabel"
      role="img"
    />
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue';
  import { Line } from 'vue-chartjs';
  import type { ActiveElement, Chart, ChartData, ChartEvent, TooltipItem } from 'chart.js';
  import { useThemeStore } from '@/stores/theme';
  import {
    answeredPercent,
    compliantPercent,
    formatMeasure,
    functionBreakdown,
    periodIndexOf,
    periodLabel,
    periodTitle,
  } from '@/utils/maturityTrend';
  import type { EvaluationProgress } from '@/types';

  // RF-VIZ-07 (PRI-321): avance de una evaluación por día, semana o mes. Dos líneas separan el
  // TRABAJO (% respondido) del RESULTADO (% que cumple); las barras muestran la actividad del
  // período (respuestas guardadas y evidencias subidas), que se ve aunque «Cumple» no se mueva.
  // Una línea vertical marca cada cambio de estado. El primer período es el vacío de arranque.
  const props = defineProps<{ progress: EvaluationProgress }>();
  const emit = defineEmits<{ select: [periodIndex: number] }>();

  const themeStore = useThemeStore();

  const periods = computed(() => props.progress.periods);
  const granularity = computed(() => props.progress.granularity);
  const labels = computed(() => periods.value.map((p) => periodLabel(p, granularity.value)));
  const activity = (i: number) => {
    const p = periods.value[i];
    return p ? p.responseActivity + p.evidenceActivity : 0;
  };

  // Qué cambios de estado se marcan y cómo. El primer «En curso» es el inicio, no se marca; uno
  // posterior es una reanudación después de una devolución.
  const MARKS: Record<string, { label: string; color: string }> = {
    READY_FOR_AUDIT: { label: 'Enviada a auditoría', color: '#7c3aed' },
    IN_AUDIT: { label: 'En auditoría', color: '#6d28d9' },
    RETURNED: { label: 'Devuelta con observaciones', color: '#ea580c' },
    IN_PROGRESS: { label: 'Reanudada', color: '#0f766e' },
    APPROVED: { label: 'Aprobada', color: '#15803d' },
  };

  const markers = computed(() => {
    let started = false;
    const out: { index: number; label: string; color: string }[] = [];
    for (const e of props.progress.statusEvents) {
      if (e.status === 'IN_PROGRESS' && !started) {
        started = true;
        continue;
      }
      started = true;
      const mark = MARKS[e.status];
      const index = periodIndexOf(periods.value, e.occurredAt);
      if (mark && index >= 0) out.push({ index, ...mark });
    }
    return out;
  });

  // Plugin propio (sin dependencias nuevas): dibuja las marcas sobre el área del gráfico.
  const statusMarkers = {
    id: 'prismaStatusMarkers',
    afterDatasetsDraw(chart: Chart) {
      const { ctx, chartArea, scales } = chart;
      const x = scales.x;
      if (!x || !chartArea) return;
      markers.value.forEach((m, n) => {
        const px = x.getPixelForValue(m.index);
        ctx.save();
        ctx.strokeStyle = m.color;
        ctx.lineWidth = 2;
        ctx.setLineDash([5, 4]);
        ctx.beginPath();
        ctx.moveTo(px, chartArea.top);
        ctx.lineTo(px, chartArea.bottom);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = m.color;
        ctx.font = '600 11px Inter, system-ui, sans-serif';
        ctx.fillText(m.label, px + 4, chartArea.top + 12 + (n % 3) * 14);
        ctx.restore();
      });
    },
  };

  const activityColor = computed(() => (themeStore.theme === 'dark' ? '#475569' : '#cbd5e1'));

  // Chart.js admite un dataset de barras dentro de un gráfico de líneas, pero el tipado de
  // vue-chartjs no: se declara como ChartData<'line'> (los controladores están registrados).
  const chartData = computed(
    () =>
      ({
        labels: labels.value,
        datasets: [
          {
            type: 'line' as const,
            label: '% respondido',
            data: periods.value.map(answeredPercent),
            borderColor: '#2563eb',
            backgroundColor: '#2563eb',
            pointRadius: 3,
            pointHoverRadius: 7,
            borderWidth: 3,
            tension: 0,
            yAxisID: 'y',
            order: 1,
          },
          {
            type: 'line' as const,
            label: '% que cumple',
            data: periods.value.map(compliantPercent),
            borderColor: '#16a34a',
            backgroundColor: '#16a34a',
            pointRadius: 3,
            pointHoverRadius: 7,
            borderWidth: 3,
            tension: 0,
            yAxisID: 'y',
            order: 2,
          },
          {
            type: 'bar' as const,
            label: 'Actividad',
            data: periods.value.map((_, i) => activity(i)),
            backgroundColor: activityColor.value,
            borderRadius: 3,
            maxBarThickness: 48,
            yAxisID: 'y1',
            order: 3,
          },
        ],
      }) as unknown as ChartData<'line'>,
  );

  const ariaLabel = computed(() => {
    const last = periods.value[periods.value.length - 1];
    const marks = markers.value.map((m) => `${m.label} el ${labels.value[m.index]}`);
    return (
      `Avance de ${props.progress.evaluationName}` +
      (last
        ? `: hoy ${formatMeasure(answeredPercent(last), 'compliance')} respondido y ` +
          `${formatMeasure(compliantPercent(last), 'compliance')} que cumple`
        : ', sin respuestas') +
      (marks.length ? `. ${marks.join('. ')}` : '')
    );
  });

  function tooltipLines(index: number): string[] {
    const p = periods.value[index];
    if (!p) return [];
    const lines = [
      `Respondidos: ${p.answeredControls} de ${p.totalControls} (${formatMeasure(answeredPercent(p), 'compliance')})`,
      `Cumplen: ${p.compliantControls} de ${p.totalControls} (${formatMeasure(compliantPercent(p), 'compliance')})`,
      `Actividad: ${p.responseActivity} ${p.responseActivity === 1 ? 'respuesta guardada' : 'respuestas guardadas'}` +
        (p.evidenceActivity
          ? ` y ${p.evidenceActivity} ${p.evidenceActivity === 1 ? 'evidencia' : 'evidencias'}`
          : ''),
    ];
    if (p.gained.length) lines.push(`▲ +${p.gained.length} pasaron a «Cumple»`);
    if (p.lost.length) lines.push(`▼ −${p.lost.length} pasaron a «No cumple»`);
    const byFunction = functionBreakdown(p);
    if (byFunction.length) {
      lines.push('Por función:');
      for (const f of byFunction)
        lines.push(`  ${f.name} ${f.delta > 0 ? '+' : '−'}${Math.abs(f.delta)}`);
    }
    return lines;
  }

  const chartOptions = computed(() => {
    const isDark = themeStore.theme === 'dark';
    const tickColor = isDark ? '#cbd5e1' : '#475569';
    const gridColor = isDark ? '#334155' : '#f1f5f9';
    return {
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index' as const, intersect: false },
      scales: {
        y: {
          min: 0,
          max: 100,
          position: 'left' as const,
          ticks: { stepSize: 25, color: tickColor, callback: (v: string | number) => `${v} %` },
          grid: { color: gridColor },
        },
        y1: {
          min: 0,
          position: 'right' as const,
          title: { display: true, text: 'Actividad', color: tickColor },
          ticks: { color: tickColor, precision: 0 },
          grid: { display: false },
        },
        x: {
          ticks: { color: tickColor, maxRotation: 0, autoSkip: true },
          grid: { display: false },
        },
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            title: (items: TooltipItem<'line'>[]) => {
              const p = items[0] ? periods.value[items[0].dataIndex] : undefined;
              return p ? periodTitle(p, granularity.value) : '';
            },
            // Un solo bloque de texto por período (no uno por serie).
            label: (item: TooltipItem<'line'>) =>
              item.datasetIndex === 0 ? tooltipLines(item.dataIndex) : '',
            footer: (items: TooltipItem<'line'>[]) => {
              const p = items[0] ? periods.value[items[0].dataIndex] : undefined;
              return p && (p.gained.length || p.lost.length)
                ? 'Clic para ver qué controles fueron'
                : '';
            },
          },
          filter: (item: TooltipItem<'line'>) => item.datasetIndex === 0,
        },
      },
      onClick: (_e: ChartEvent, elements: ActiveElement[]) => {
        const hit = elements[0];
        if (hit) emit('select', hit.index);
      },
    };
  });

  defineExpose({ tooltipLines, markers });
</script>
