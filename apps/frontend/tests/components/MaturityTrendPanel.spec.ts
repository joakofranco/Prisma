import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import { defineComponent, h } from 'vue';

// RF-VIZ-07 (PRI-321): bloque «Tendencia de la madurez» del Panel.
const dashboardService = vi.hoisted(() => ({ getMaturityTrend: vi.fn() }));
const organizationsService = vi.hoisted(() => ({ getAll: vi.fn() }));
const push = vi.hoisted(() => vi.fn());

vi.mock('@/services/resources', () => ({ dashboardService, organizationsService }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }));

import MaturityTrendPanel from '@/components/dashboard/MaturityTrendPanel.vue';

// El gráfico se reemplaza por un stub que expone lo que recibe y permite simular un clic.
const ChartStub = defineComponent({
  name: 'MaturityTrendLineChart',
  props: {
    labels: { type: Array, required: true },
    series: { type: Array, required: true },
    metric: { type: String, required: true },
  },
  emits: ['select'],
  setup(props, { emit }) {
    return () =>
      h(
        'button',
        {
          'data-testid': 'chart',
          'data-labels': (props.labels as string[]).join('|'),
          'data-series': (props.series as { label: string }[]).map((s) => s.label).join('|'),
          'data-metric': props.metric,
          onClick: () => emit('select', 'e-ultima'),
        },
        'chart',
      );
  },
});

const point = (
  id: string,
  approvedAt: string,
  profileId: string | null,
  profileName: string,
  compliancePercent: number,
  maturityAverage: number,
) => ({
  evaluationId: id,
  evaluationName: id,
  status: 'APPROVED',
  provisional: false,
  date: approvedAt,
  approvedAt,
  profileId,
  profileName,
  compliantControls: 10,
  totalControls: 20,
  compliancePercent,
  maturityAverage,
  globalMaturity: 1,
});

const TREND = {
  organizationId: 'org-1',
  organizationName: 'Intendencia de Rivera',
  points: [
    point('e1', '2024-03-14T10:00:00Z', 'p-b', 'Básico', 40, 0.7),
    point('e2', '2025-03-14T10:00:00Z', 'p-b', 'Básico', 58, 1),
    point('e-ultima', '2026-03-14T10:00:00Z', 'p-e', 'Estándar', 62, 1.48),
  ],
};

function mountPanel(isPlatformAdmin = false) {
  return mount(MaturityTrendPanel, {
    props: { isPlatformAdmin },
    global: {
      stubs: {
        MaturityTrendLineChart: ChartStub,
        EvaluationProgressSection: {
          name: 'EvaluationProgressSection',
          props: { points: { type: Array, required: true } },
          template: '<div data-testid="progress-stub">{{ points.length }}</div>',
        },
      },
    },
  });
}

async function openBetween(w: ReturnType<typeof mountPanel>) {
  await w.find('[data-testid="trend-view-between"]').trigger('click');
}

const chart = (w: ReturnType<typeof mountPanel>) => w.find('[data-testid="chart"]');

describe('MaturityTrendPanel (PRI-321)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    dashboardService.getMaturityTrend.mockResolvedValue({ data: TREND });
    organizationsService.getAll.mockResolvedValue({
      data: {
        data: [
          { id: 'org-2', name: 'Zeta', enabled: true },
          { id: 'org-1', name: 'Intendencia de Rivera', enabled: true },
          { id: 'org-3', name: 'Inactiva', enabled: false },
        ],
        total: 3,
      },
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('el responsable ve su organización sin elegirla, con todos sus perfiles marcados', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);

    expect(dashboardService.getMaturityTrend).toHaveBeenCalledWith(undefined);
    expect(w.text()).toContain('Intendencia de Rivera');
    expect(w.find('[data-testid="trend-organization"]').exists()).toBe(false);
    expect(chart(w).attributes('data-series')).toBe('Básico|Estándar');
    expect(w.text()).toContain('Básico');
    expect(w.text()).toContain('(2)');
  });

  it('resume cada perfil y avisa cuando tiene una sola evaluación', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);
    const table = w.find('[data-testid="trend-summary"]').text();

    expect(table).toContain('40 %');
    expect(table).toContain('58 %');
    expect(table).toContain('▲ +18 puntos');
    expect(table).toContain('Una sola evaluación');
    expect(table).toContain('10 de 20');
  });

  it('desmarcar un perfil lo saca del gráfico y del resumen', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);

    await w.find('[data-testid="trend-profile-Estándar"]').setValue(false);

    expect(chart(w).attributes('data-series')).toBe('Básico');
    expect(w.find('[data-testid="trend-summary"]').text()).not.toContain('Estándar');
  });

  it('sin perfiles marcados pide marcar al menos uno', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);

    await w.find('[data-testid="trend-profile-Estándar"]').setValue(false);
    await w.find('[data-testid="trend-profile-Básico"]').setValue(false);

    expect(chart(w).exists()).toBe(false);
    expect(w.text()).toContain('Marcá al menos un perfil');
  });

  it('cambia entre controles cumplidos y nivel de madurez', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);
    expect(chart(w).attributes('data-metric')).toBe('compliance');

    await w.find('[data-testid="trend-metric-maturity"]').trigger('click');

    expect(chart(w).attributes('data-metric')).toBe('maturity');
    expect(w.find('[data-testid="trend-metric-maturity"]').attributes('aria-pressed')).toBe('true');
    expect(w.find('[data-testid="trend-summary"]').text()).toContain('▲ +0,30');
  });

  it('un clic en un punto abre la evaluación', async () => {
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);

    await chart(w).trigger('click');

    expect(push).toHaveBeenCalledWith('/evaluations/e-ultima');
  });

  it('sin evaluaciones aprobadas lo explica', async () => {
    dashboardService.getMaturityTrend.mockResolvedValue({ data: { ...TREND, points: [] } });
    const w = mountPanel();
    await flushPromises();

    expect(w.find('[data-testid="trend-empty"]').text()).toContain(
      'Todavía no hay evaluaciones con respuestas',
    );
  });

  it('muestra el error del backend', async () => {
    dashboardService.getMaturityTrend.mockRejectedValue({
      response: { data: { message: 'Solo podés ver la tendencia de tu organización' } },
    });
    const w = mountPanel();
    await flushPromises();

    expect(w.text()).toContain('Solo podés ver la tendencia de tu organización');
  });

  it('el administrador arranca en la organización con actividad más reciente', async () => {
    const w = mountPanel(true);
    await flushPromises();

    expect(organizationsService.getAll).toHaveBeenCalled();
    // Sin organización elegida, el backend devuelve la de actividad más reciente…
    expect(dashboardService.getMaturityTrend).toHaveBeenCalledTimes(1);
    expect(dashboardService.getMaturityTrend).toHaveBeenCalledWith(undefined);
    // …y el selector queda en ella sin volver a pedirla.
    expect(w.find('[data-testid="trend-organization"]').exists()).toBe(true);
    expect(w.text()).toContain('Intendencia de Rivera');
  });

  it('el administrador puede cambiar de organización', async () => {
    const w = mountPanel(true);
    await flushPromises();
    const select = w.findComponent({ name: 'BaseSelect' });

    select.vm.$emit('update:modelValue', 'org-2');
    await flushPromises();

    expect(dashboardService.getMaturityTrend).toHaveBeenLastCalledWith('org-2');
  });

  it('el administrador sin organizaciones con actividad ve el pedido de elegir una', async () => {
    dashboardService.getMaturityTrend.mockRejectedValue({
      response: { data: { message: 'Elegí la organización para ver su tendencia' } },
    });
    const w = mountPanel(true);
    await flushPromises();

    expect(w.text()).toContain('Elegí una organización para ver su tendencia');
    expect(w.text()).not.toContain('Elegí la organización para ver su tendencia');
  });

  it('arranca en «Avance de la evaluación» con las evaluaciones de la organización', async () => {
    const w = mountPanel();
    await flushPromises();

    expect(w.find('[data-testid="trend-view-progress"]').attributes('aria-pressed')).toBe('true');
    expect(w.find('[data-testid="progress-stub"]').text()).toBe('3');
    expect(w.text()).toContain('cómo se van cumpliendo los controles');
  });

  it('una evaluación en curso figura como provisoria en el resumen', async () => {
    dashboardService.getMaturityTrend.mockResolvedValue({
      data: {
        ...TREND,
        points: [
          ...TREND.points,
          {
            ...point('e-curso', '2026-11-18T10:00:00Z', 'p-e', 'Estándar', 70, 1.6),
            status: 'IN_PROGRESS',
            provisional: true,
            approvedAt: null,
          },
        ],
      },
    });
    const w = mountPanel();
    await flushPromises();
    await openBetween(w);

    expect(w.find('[data-testid="trend-summary"]').text()).toContain('hoy, provisorio');
  });
});
