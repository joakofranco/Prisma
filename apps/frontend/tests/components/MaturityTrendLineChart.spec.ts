import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import { defineComponent, h } from 'vue';

// RF-VIZ-07 (PRI-321): el gráfico de líneas. Chart.js no dibuja en jsdom: se reemplaza <Line> por
// un stub que guarda lo que recibe, y se prueban los datos, el tooltip y el clic.
interface Item {
  datasetIndex: number;
  dataIndex: number;
}
interface CapturedData {
  labels: string[];
  datasets: {
    label: string;
    data: (number | null)[];
    spanGaps: boolean;
    pointBackgroundColor: string[];
    segment: { borderDash: (ctx: { p1DataIndex: number }) => number[] | undefined };
  }[];
}
interface CapturedOptions {
  scales: { y: { max: number; ticks: { callback: (v: number) => string } } };
  plugins: {
    tooltip: {
      callbacks: {
        title: (items: Item[]) => string;
        label: (item: Item) => string | string[];
        footer: () => string;
      };
    };
  };
  onClick: (e: unknown, elements: { datasetIndex: number; index: number }[]) => void;
}
const captured = vi.hoisted(() => ({
  data: {} as CapturedData,
  options: {} as CapturedOptions,
}));
vi.mock('vue-chartjs', () => ({
  Line: defineComponent({
    props: { data: { type: Object, required: true }, options: { type: Object, required: true } },
    setup(props) {
      return () => {
        captured.data = props.data as unknown as CapturedData;
        captured.options = props.options as unknown as CapturedOptions;
        return h('canvas');
      };
    },
  }),
}));

import MaturityTrendLineChart from '@/components/charts/MaturityTrendLineChart.vue';
import type { MaturityTrendPoint } from '@/types';

const p: MaturityTrendPoint = {
  evaluationId: 'e-1',
  evaluationName: 'Anual 2026',
  status: 'APPROVED',
  provisional: false,
  date: '2026-10-28T13:00:00Z',
  approvedAt: '2026-10-28T13:00:00Z',
  profileId: 'p-e',
  profileName: 'Estándar',
  compliantControls: 142,
  totalControls: 230,
  compliancePercent: 61.7,
  maturityAverage: 1.48,
  globalMaturity: 1,
};

const series = [
  {
    key: 'p-e',
    label: 'Estándar',
    color: '#2563eb',
    pointStyle: 'circle' as const,
    points: [null, p],
  },
];

function mountChart(metric: 'compliance' | 'maturity' = 'compliance') {
  return mount(MaturityTrendLineChart, {
    props: { labels: ['01/01/2025', '28/10/2026'], series, metric },
  });
}

describe('MaturityTrendLineChart (PRI-321)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('una serie por perfil con null donde no tiene evaluación', () => {
    mountChart();

    expect(captured.data.labels).toEqual(['01/01/2025', '28/10/2026']);
    expect(captured.data.datasets[0].label).toBe('Estándar');
    expect(captured.data.datasets[0].data).toEqual([null, 61.7]);
    expect(captured.data.datasets[0].spanGaps).toBe(true);
    expect(captured.options.scales.y.max).toBe(100);
  });

  it('en «Nivel de madurez» grafica el promedio sobre la escala 0 a 4', () => {
    mountChart('maturity');

    expect(captured.data.datasets[0].data).toEqual([null, 1.48]);
    expect(captured.options.scales.y.max).toBe(4);
    expect(captured.options.scales.y.ticks.callback(1)).toBe('1 · Inicial');
  });

  it('el tooltip detalla la evaluación, sus controles y su nivel', () => {
    mountChart();
    const item = { datasetIndex: 0, dataIndex: 1 };
    const { callbacks } = captured.options.plugins.tooltip;

    expect(callbacks.title([item])).toBe('Anual 2026 · Estándar');
    const lines = callbacks.label(item) as string[];
    expect(lines[1]).toBe('142 de 230 controles cumplidos (61,7 %)');
    expect(lines[2]).toBe('Madurez 1,48 · Nivel 1 (Inicial)');
    expect(callbacks.label({ datasetIndex: 0, dataIndex: 0 })).toBe('');
    expect(callbacks.footer()).toBe('Clic para abrir la evaluación');
    expect(captured.options.scales.y.ticks.callback(50)).toBe('50 %');
  });

  it('un clic en un punto emite la evaluación', () => {
    const w = mountChart();

    captured.options.onClick({}, [{ datasetIndex: 0, index: 1 }]);
    captured.options.onClick({}, []);

    expect(w.emitted('select')).toEqual([['e-1']]);
  });

  it('describe el gráfico para lectores de pantalla', () => {
    const w = mountChart();

    expect(w.find('[role="img"]').attributes('aria-label')).toContain(
      'Estándar: 28/10/2026 61,7 %',
    );
  });

  it('una evaluación provisoria va con punto hueco, tramo punteado y aviso en el tooltip', () => {
    const live = {
      ...p,
      evaluationId: 'e-2',
      provisional: true,
      status: 'IN_AUDIT',
      approvedAt: null,
    };
    mount(MaturityTrendLineChart, {
      props: {
        labels: ['28/10/2026', 'Hoy (en curso)'],
        series: [{ ...series[0]!, points: [p, live] }],
        metric: 'compliance',
      },
    });
    const ds = captured.data.datasets[0]!;

    expect(ds.pointBackgroundColor[0]).toBe('#2563eb');
    expect(ds.pointBackgroundColor[1]).not.toBe('#2563eb');
    expect(ds.segment.borderDash({ p1DataIndex: 1 })).toEqual([7, 6]);
    expect(ds.segment.borderDash({ p1DataIndex: 0 })).toBeUndefined();
    const lines = captured.options.plugins.tooltip.callbacks.label({
      datasetIndex: 0,
      dataIndex: 1,
    }) as string[];
    expect(lines[0]).toContain('Provisoria');
  });
});
