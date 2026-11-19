import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import { defineComponent, h } from 'vue';

// RF-VIZ-07 (PRI-321): gráfico del avance por período. Chart.js no dibuja en jsdom: se reemplaza
// <Line> por un stub que guarda los datos, las opciones y el plugin de las marcas.
interface Captured {
  data: { labels: string[]; datasets: { type: string; label: string; data: number[] }[] };
  options: {
    scales: { y1: { position: string } };
    plugins: {
      tooltip: {
        callbacks: {
          title: (items: { dataIndex: number }[]) => string;
          label: (item: { dataIndex: number; datasetIndex: number }) => string | string[];
          footer: (items: { dataIndex: number }[]) => string;
        };
        filter: (item: { datasetIndex: number }) => boolean;
      };
    };
    onClick: (e: unknown, elements: { index: number }[]) => void;
  };
  plugins: { afterDatasetsDraw: (chart: unknown) => void }[];
}
const captured = vi.hoisted(() => ({}) as Captured);
vi.mock('vue-chartjs', () => ({
  Line: defineComponent({
    props: {
      data: { type: Object, required: true },
      options: { type: Object, required: true },
      plugins: { type: Array, required: true },
    },
    setup(props) {
      return () => {
        Object.assign(captured, props);
        return h('canvas');
      };
    },
  }),
}));

import EvaluationProgressChart from '@/components/charts/EvaluationProgressChart.vue';
import type { EvaluationProgress, ProgressPeriod } from '@/types';

const change = (code: string, functionName: string) => ({
  controlId: code,
  code,
  description: code,
  functionName,
  changedAt: '2026-06-03T12:00:00',
  changedBy: null,
});

const period = (
  start: string,
  end: string,
  over: Partial<ProgressPeriod> = {},
): ProgressPeriod => ({
  periodStart: start,
  periodEnd: end,
  compliantControls: 0,
  answeredControls: 0,
  totalControls: 10,
  responseActivity: 0,
  evidenceActivity: 0,
  gained: [],
  lost: [],
  ...over,
});

const progress: EvaluationProgress = {
  evaluationId: 'e-1',
  evaluationName: 'EVA-1',
  status: 'IN_PROGRESS',
  statusLabel: 'En Curso',
  profileName: 'Estándar',
  startedAt: '2026-06-01T12:00:00',
  compliantControls: 3,
  answeredControls: 4,
  totalControls: 10,
  last30DaysDelta: 2,
  granularity: 'WEEK',
  periods: [
    period('2026-05-25', '2026-05-31'),
    period('2026-06-01', '2026-06-07', {
      compliantControls: 2,
      answeredControls: 3,
      responseActivity: 4,
      evidenceActivity: 1,
      gained: [change('PR.AA-1', 'Proteger'), change('ID.AM-1', 'Identificar')],
    }),
    period('2026-06-08', '2026-06-14', { compliantControls: 2, answeredControls: 3 }),
    period('2026-06-15', '2026-06-21', {
      compliantControls: 3,
      answeredControls: 4,
      responseActivity: 3,
      gained: [change('PR.DS-2', 'Proteger'), change('DE.CM-1', 'Detectar')],
      lost: [change('ID.AM-1', 'Identificar')],
    }),
  ],
  statusEvents: [
    { status: 'IN_PROGRESS', label: 'En Curso', occurredAt: '2026-06-01T12:00:00' },
    { status: 'READY_FOR_AUDIT', label: 'Lista', occurredAt: '2026-06-09T12:00:00' },
    { status: 'RETURNED', label: 'Devuelta', occurredAt: '2026-06-10T12:00:00' },
    { status: 'IN_PROGRESS', label: 'En Curso', occurredAt: '2026-06-16T12:00:00' },
    { status: 'ARCHIVED', label: 'Archivada', occurredAt: '2026-06-16T12:00:00' },
  ],
};

describe('EvaluationProgressChart (PRI-321)', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('dos líneas (respondido y cumple) y barras de actividad, arrancando en 0', () => {
    mount(EvaluationProgressChart, { props: { progress } });
    const [answered, compliant, activity] = captured.data.datasets;

    expect(captured.data.labels).toEqual(['25/05', '01/06', '08/06', '15/06']);
    expect(answered!.label).toBe('% respondido');
    expect(answered!.data).toEqual([0, 30, 30, 40]);
    expect(compliant!.label).toBe('% que cumple');
    expect(compliant!.data).toEqual([0, 20, 20, 30]);
    expect(activity!.type).toBe('bar');
    expect(activity!.data).toEqual([0, 5, 0, 3]);
    expect(captured.options.scales.y1.position).toBe('right');
  });

  it('marca los cambios de estado, salvo el inicio', () => {
    const w = mount(EvaluationProgressChart, { props: { progress } });
    const marks = (w.vm as unknown as { markers: { index: number; label: string }[] }).markers;

    expect(marks.map((m) => [m.index, m.label])).toEqual([
      [2, 'Enviada a auditoría'],
      [2, 'Devuelta con observaciones'],
      [3, 'Reanudada'],
    ]);
  });

  it('el tooltip resume trabajo, resultado, actividad y cambio por función', () => {
    mount(EvaluationProgressChart, { props: { progress } });
    const cb = captured.options.plugins.tooltip.callbacks;

    expect(cb.title([{ dataIndex: 3 }])).toBe('Semana del 15/06 al 21/06');
    const lines = cb.label({ dataIndex: 3, datasetIndex: 0 }) as string[];
    expect(lines[0]).toBe('Respondidos: 4 de 10 (40 %)');
    expect(lines[1]).toBe('Cumplen: 3 de 10 (30 %)');
    expect(lines[2]).toBe('Actividad: 3 respuestas guardadas');
    expect(lines).toContain('▲ +2 pasaron a «Cumple»');
    expect(lines).toContain('▼ −1 pasaron a «No cumple»');
    expect(lines).toContain('  Proteger +1');
    expect(lines).toContain('  Identificar −1');
    expect((cb.label({ dataIndex: 1, datasetIndex: 0 }) as string[])[2]).toBe(
      'Actividad: 4 respuestas guardadas y 1 evidencia',
    );
    expect(cb.label({ dataIndex: 3, datasetIndex: 1 })).toBe('');
    expect(captured.options.plugins.tooltip.filter({ datasetIndex: 2 })).toBe(false);
    expect(cb.footer([{ dataIndex: 3 }])).toBe('Clic para ver qué controles fueron');
    expect(cb.footer([{ dataIndex: 2 }])).toBe('');
    expect(cb.label({ dataIndex: 9, datasetIndex: 0 })).toEqual([]);
  });

  it('un clic en un período lo emite', () => {
    const w = mount(EvaluationProgressChart, { props: { progress } });

    captured.options.onClick({}, [{ index: 3 }]);
    captured.options.onClick({}, []);

    expect(w.emitted('select')).toEqual([[3]]);
  });

  it('el plugin dibuja una línea por marca', () => {
    mount(EvaluationProgressChart, { props: { progress } });
    const ctx = {
      save: vi.fn(),
      restore: vi.fn(),
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      stroke: vi.fn(),
      setLineDash: vi.fn(),
      fillText: vi.fn(),
    };

    captured.plugins[0]!.afterDatasetsDraw({
      ctx,
      chartArea: { top: 0, bottom: 100 },
      scales: { x: { getPixelForValue: (i: number) => i * 10 } },
    });
    captured.plugins[0]!.afterDatasetsDraw({ ctx, chartArea: null, scales: {} });

    expect(ctx.stroke).toHaveBeenCalledTimes(3);
    expect(ctx.fillText).toHaveBeenCalledWith('Reanudada', 34, expect.any(Number));
  });

  it('describe el avance para lectores de pantalla', () => {
    const w = mount(EvaluationProgressChart, { props: { progress } });

    expect(w.find('[role="img"]').attributes('aria-label')).toContain(
      'hoy 40 % respondido y 30 % que cumple',
    );
  });
});
