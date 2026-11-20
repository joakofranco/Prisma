import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import { defineComponent, h } from 'vue';

// RF-VIZ-07 (PRI-321): sección «Avance de la evaluación» del Panel.
const dashboardService = vi.hoisted(() => ({ getEvaluationProgress: vi.fn() }));
vi.mock('@/services/resources', () => ({ dashboardService }));

import EvaluationProgressSection from '@/components/dashboard/EvaluationProgressSection.vue';
import type { EvaluationProgress, MaturityTrendPoint } from '@/types';

const ChartStub = defineComponent({
  name: 'EvaluationProgressChart',
  props: { progress: { type: Object, required: true } },
  emits: ['select'],
  setup(_props, { emit }) {
    return () =>
      h('button', { 'data-testid': 'progress-chart', onClick: () => emit('select', 0) }, 'chart');
  },
});

const point = (
  id: string,
  date: string,
  provisional: boolean,
  status = provisional ? 'IN_PROGRESS' : 'APPROVED',
): MaturityTrendPoint => ({
  evaluationId: id,
  evaluationName: id,
  status,
  provisional,
  date,
  approvedAt: provisional ? null : date,
  profileId: null,
  profileName: 'Catálogo completo',
  compliantControls: 1,
  totalControls: 2,
  compliancePercent: 50,
  maturityAverage: 1,
  globalMaturity: 1,
});

const POINTS = [
  point('vieja', '2025-01-01T10:00:00Z', false),
  point('en-curso', '2026-11-18T10:00:00Z', true),
];

const change = (code: string) => ({
  controlId: code,
  code,
  description: `Control ${code}`,
  functionName: 'Proteger',
  changedAt: '2026-06-03T12:00:00',
  changedBy: 'Ana Pérez',
});

const PROGRESS: EvaluationProgress = {
  evaluationId: 'en-curso',
  evaluationName: 'en-curso',
  status: 'IN_PROGRESS',
  statusLabel: 'En Curso',
  profileName: 'Estándar',
  startedAt: '2026-06-01T12:00:00',
  compliantControls: 142,
  answeredControls: 200,
  totalControls: 230,
  last30DaysDelta: 38,
  last30DaysAnsweredDelta: 70,
  granularity: 'WEEK',
  periods: [
    {
      periodStart: '2026-06-01',
      periodEnd: '2026-06-07',
      compliantControls: 7,
      answeredControls: 8,
      totalControls: 230,
      responseActivity: 9,
      evidenceActivity: 1,
      gained: ['A', 'B', 'C', 'D', 'E', 'F', 'G'].map((c) => change(`PR.${c}`)),
      lost: [change('RS.MA-2')],
    },
  ],
  statusEvents: [],
};

function mountSection(points = POINTS) {
  return mount(EvaluationProgressSection, {
    props: { points },
    global: {
      stubs: { EvaluationProgressChart: ChartStub, RouterLink: { template: '<a><slot /></a>' } },
    },
  });
}

describe('EvaluationProgressSection (PRI-321)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    dashboardService.getEvaluationProgress.mockResolvedValue({ data: PROGRESS });
  });

  it('arranca en la evaluación en curso y muestra el resumen', async () => {
    const w = mountSection();
    await flushPromises();

    expect(dashboardService.getEvaluationProgress).toHaveBeenCalledWith('en-curso', undefined);
    const chips = w.find('[data-testid="progress-chips"]').text();
    expect(chips).toContain('Controles cumplidos');
    expect(chips).toContain('142');
    expect(chips).toContain('de 230');
    expect(chips).toContain('61,7 % · el resultado');
    expect(chips).toContain('87 % · el trabajo');
    expect(chips).toContain('30 sin responder');
    expect(chips).toContain('▲ +38');
    expect(chips).toContain('controles cumplidos más que hace un mes · +70 respondidos');
    expect(chips).toContain('En curso desde');
    expect(chips).toContain('perfil Estándar');
    const bars = w.findAll('[role="progressbar"]');
    expect(bars.map((b) => b.attributes('aria-valuenow'))).toEqual(['61.7', '87']);
    expect(w.text()).toContain('declarado por la organización');
  });

  it('sin evaluaciones en curso arranca en la más reciente', async () => {
    mountSection([
      point('2024', '2024-01-01T10:00:00Z', false),
      point('2025', '2025-01-01T10:00:00Z', false),
    ]);
    await flushPromises();

    expect(dashboardService.getEvaluationProgress).toHaveBeenCalledWith('2025', undefined);
  });

  it('un clic en una semana abre su detalle, con «Ver todos» y cierre', async () => {
    const w = mountSection();
    await flushPromises();

    await w.find('[data-testid="progress-chart"]').trigger('click');
    const detail = w.find('[data-testid="progress-week"]');
    expect(detail.text()).toContain('Detalle · Semana del 01/06 al 07/06');
    expect(detail.text()).toContain('PASARON A «CUMPLE» (7)');
    expect(detail.text()).toContain('PASARON A «NO CUMPLE» (1)');
    expect(detail.text()).toContain('Ana Pérez');
    expect(detail.text()).not.toContain('PR.G');

    await w.find('[data-testid="progress-more-gained"]').trigger('click');
    expect(w.find('[data-testid="progress-week"]').text()).toContain('PR.G');

    await w.find('[aria-label="Cerrar detalle del período"]').trigger('click');
    expect(w.find('[data-testid="progress-week"]').exists()).toBe(false);
  });

  it('una evaluación sin respuestas lo dice', async () => {
    dashboardService.getEvaluationProgress.mockResolvedValue({
      data: { ...PROGRESS, periods: [], compliantControls: 0, last30DaysDelta: 0 },
    });
    const w = mountSection();
    await flushPromises();

    expect(w.find('[data-testid="progress-empty"]').exists()).toBe(true);
  });

  it('una baja en los últimos 30 días se muestra en negativo', async () => {
    dashboardService.getEvaluationProgress.mockResolvedValue({
      data: { ...PROGRESS, last30DaysDelta: -1 },
    });
    const w = mountSection();
    await flushPromises();

    const chips = w.find('[data-testid="progress-chips"]').text();
    expect(chips).toContain('▼ −1');
    expect(chips).toContain('control cumplido menos que hace un mes');
  });

  it('sin cambios en el último mes lo dice', async () => {
    dashboardService.getEvaluationProgress.mockResolvedValue({
      data: { ...PROGRESS, last30DaysDelta: 0, last30DaysAnsweredDelta: 0 },
    });
    const w = mountSection();
    await flushPromises();

    const chips = w.find('[data-testid="progress-chips"]').text();
    expect(chips).toContain('Sin cambios');
    expect(chips).toContain('en controles cumplidos durante el último mes');
  });

  it('muestra el error del backend', async () => {
    dashboardService.getEvaluationProgress.mockRejectedValue({
      response: {
        data: { message: 'Solo podés ver el avance de las evaluaciones de tu organización' },
      },
    });
    const w = mountSection();
    await flushPromises();

    expect(w.text()).toContain('Solo podés ver el avance');
  });

  it('sin evaluaciones no consulta nada', async () => {
    mountSection([]);
    await flushPromises();

    expect(dashboardService.getEvaluationProgress).not.toHaveBeenCalled();
  });

  it('cambiar la escala vuelve a pedir el avance en esa escala', async () => {
    const w = mountSection();
    await flushPromises();
    expect(w.find('[data-testid="progress-scale-AUTO"]').attributes('aria-pressed')).toBe('true');

    await w.find('[data-testid="progress-scale-MONTH"]').trigger('click');
    await flushPromises();

    expect(dashboardService.getEvaluationProgress).toHaveBeenLastCalledWith('en-curso', 'MONTH');
    expect(w.find('[data-testid="progress-scale-MONTH"]').attributes('aria-pressed')).toBe('true');
  });

  it('explica las dos líneas y las barras', async () => {
    const w = mountSection();
    await flushPromises();

    expect(w.text()).toContain('% respondido');
    expect(w.text()).toContain('% que cumple');
    expect(w.text()).toContain('Actividad del período');
  });

  it('un mes con respuestas pero sin nuevos «Cumple» muestra el trabajo', async () => {
    dashboardService.getEvaluationProgress.mockResolvedValue({
      data: { ...PROGRESS, last30DaysDelta: 0, last30DaysAnsweredDelta: 1 },
    });
    const w = mountSection();
    await flushPromises();

    expect(w.find('[data-testid="progress-chips"]').text()).toContain(
      'en controles cumplidos durante el último mes · +1 respondido',
    );
  });
});
