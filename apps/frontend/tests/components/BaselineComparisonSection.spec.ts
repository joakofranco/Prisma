import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import BaselineComparisonSection from '@/components/improvement/BaselineComparisonSection.vue';
import type { PlanBaselineComparison, PlanBaselineItem, PlanBaselineSummary } from '@/types';

const item = (extra: Partial<PlanBaselineItem> = {}): PlanBaselineItem => ({
  id: '1',
  controlCode: 'PS.1-1',
  action: 'Definir la política',
  status: 'PENDING',
  baselineDueDate: '2026-11-01',
  dueDate: '2026-11-01',
  dueDateShiftDays: 0,
  replanned: false,
  baselinePriority: 'HIGH',
  priority: 'HIGH',
  priorityChanged: false,
  baselineResponsible: 'Ana',
  responsible: 'Ana',
  responsibleChanged: false,
  completedAt: null,
  compliance: 'IN_TERM',
  delayDays: null,
  ...extra,
});

const summary = (extra: Partial<PlanBaselineSummary> = {}): PlanBaselineSummary => ({
  total: 1,
  replanned: 0,
  averageShiftDays: null,
  priorityChanged: 0,
  responsibleChanged: 0,
  onTime: 0,
  late: 0,
  delayed: 0,
  plannedDue: 0,
  completed: 0,
  plannedPercent: 0,
  actualPercent: 0,
  deviationPoints: 0,
  ...extra,
});

const mountWith = (comparison: PlanBaselineComparison | null) =>
  mount(BaselineComparisonSection, { props: { comparison } });

describe('BaselineComparisonSection (RF-PLN-07)', () => {
  it('sin comparativa cargada muestra el estado vacío', () => {
    const wrapper = mountWith(null);

    expect(wrapper.find('[data-test="baseline-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="baseline-table"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="baseline-deviation"]').exists()).toBe(false);
  });

  it('sin acciones muestra el estado vacío y no el desvío', () => {
    const wrapper = mountWith({
      summary: summary({
        total: 0,
        plannedPercent: null,
        actualPercent: null,
        deviationPoints: null,
      }),
      items: [],
    });

    expect(wrapper.find('[data-test="baseline-empty"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="baseline-deviation"]').exists()).toBe(false);
  });

  it('muestra el avance planificado, el real y el desvío en puntos', () => {
    const wrapper = mountWith({
      summary: summary({
        total: 4,
        plannedDue: 3,
        completed: 2,
        plannedPercent: 75,
        actualPercent: 50,
        deviationPoints: -25,
      }),
      items: [item()],
    });

    const valueOf = (key: string) =>
      wrapper.find(`[data-test="${key}"] [role="progressbar"]`).attributes('aria-valuenow');
    expect(valueOf('baseline-planned')).toBe('75');
    expect(valueOf('baseline-actual')).toBe('50');
    expect(wrapper.find('[data-test="baseline-planned"]').text()).toContain('3 de 4');
    expect(wrapper.find('[data-test="baseline-actual"]').text()).toContain('2 de 4 realizadas');
    expect(wrapper.find('[data-test="baseline-deviation"]').text()).toBe('Desvío -25 pts');
  });

  it('un desvío a favor se muestra con signo positivo', () => {
    const wrapper = mountWith({ summary: summary({ deviationPoints: 10 }), items: [item()] });

    expect(wrapper.find('[data-test="baseline-deviation"]').text()).toBe('Desvío +10 pts');
  });

  it('el resumen cuenta replanificadas, cumplimiento y cambios', () => {
    const wrapper = mountWith({
      summary: summary({
        replanned: 2,
        averageShiftDays: 20,
        onTime: 1,
        late: 1,
        delayed: 3,
        priorityChanged: 1,
        responsibleChanged: 4,
      }),
      items: [item()],
    });

    const text = wrapper.find('[data-test="baseline-stats"]').text();
    expect(text).toContain('Replanificadas2');
    expect(text).toContain('Promedio +20 días');
    expect(text).toContain('En plazo1');
    expect(text).toContain('Tarde1');
    expect(text).toContain('Atrasadas3');
    expect(text).toContain('Prioridad cambiada1');
    expect(text).toContain('Responsable cambiado4');
  });

  it('una acción sin cambios dice «Sin cambios» y no muestra valores anteriores', () => {
    const wrapper = mountWith({ summary: summary(), items: [item()] });

    const row = wrapper.find('[data-test="baseline-row"]');
    expect(row.text()).toContain('PS.1-1');
    expect(row.text()).toContain('01/11/2026');
    expect(row.find('[data-test="baseline-shift"]').text()).toBe('Sin cambios');
    expect(row.text()).not.toContain('antes:');
    expect(row.find('[data-test="baseline-compliance"]').text()).toBe('En curso');
  });

  it('una acción replanificada y atrasada muestra corrimiento, originales y días de atraso', () => {
    const wrapper = mountWith({
      summary: summary({ replanned: 1, delayed: 1 }),
      items: [
        item({
          dueDate: '2026-11-16',
          dueDateShiftDays: 15,
          replanned: true,
          priority: 'LOW',
          priorityChanged: true,
          responsible: 'Bruno',
          responsibleChanged: true,
          compliance: 'DELAYED',
          delayDays: 4,
        }),
      ],
    });

    const row = wrapper.find('[data-test="baseline-row"]');
    expect(row.find('[data-test="baseline-shift"]').text()).toBe('+15 días');
    expect(row.find('[data-test="baseline-priority"]').text()).toContain('antes: Alta');
    expect(row.find('[data-test="baseline-responsible"]').text()).toContain('Bruno');
    expect(row.find('[data-test="baseline-responsible"]').text()).toContain('antes: Ana');
    expect(row.find('[data-test="baseline-compliance"]').text()).toBe('Atrasada (4 días)');
  });

  it('cubre el resto de los estados de cumplimiento y una fecha adelantada', () => {
    const wrapper = mountWith({
      summary: summary({ total: 4 }),
      items: [
        item({ id: 'a', compliance: 'ON_TIME' }),
        item({ id: 'b', compliance: 'LATE', delayDays: 1 }),
        item({ id: 'c', compliance: 'NO_CLOSE_DATE', dueDateShiftDays: -1, replanned: true }),
        item({
          id: 'd',
          compliance: 'NO_DATE',
          baselineDueDate: null,
          dueDate: '2026-12-01',
          dueDateShiftDays: null,
          replanned: true,
          controlCode: null,
        }),
      ],
    });

    const labels = wrapper.findAll('[data-test="baseline-compliance"]').map((cell) => cell.text());
    expect(labels).toEqual(['En plazo', 'Tarde (1 día)', 'Sin fecha de cierre', 'Sin fecha']);
    const shifts = wrapper.findAll('[data-test="baseline-shift"]').map((cell) => cell.text());
    expect(shifts).toEqual(['Sin cambios', 'Sin cambios', '-1 día', 'Replanificada']);
    expect(wrapper.findAll('[data-test="baseline-row"]')[3].text()).toContain('—');
  });
});
