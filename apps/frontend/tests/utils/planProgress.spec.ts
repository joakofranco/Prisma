import { describe, it, expect } from 'vitest';
import { planProgress } from '@/utils/planProgress';

type P = Parameters<typeof planProgress>[0][number];
const plan = (priority: P['priority'], status: P['status']): P => ({ priority, status });

describe('planProgress (RF-PLN-06)', () => {
  it('sin acciones no hay porcentaje: es null, no 0 %', () => {
    const p = planProgress([]);

    expect(p.overall).toEqual({ total: 0, closed: 0, percent: null });
    expect(p.HIGH.percent).toBeNull();
    expect(p.MEDIUM.percent).toBeNull();
    expect(p.LOW.percent).toBeNull();
  });

  it('todas cerradas da 100 % en cada prioridad con acciones', () => {
    const p = planProgress([
      plan('HIGH', 'COMPLETED'),
      plan('HIGH', 'COMPLETED'),
      plan('LOW', 'COMPLETED'),
    ]);

    expect(p.overall).toEqual({ total: 3, closed: 3, percent: 100 });
    expect(p.HIGH).toEqual({ total: 2, closed: 2, percent: 100 });
    expect(p.LOW).toEqual({ total: 1, closed: 1, percent: 100 });
  });

  it('una prioridad sin acciones queda en null aunque las otras tengan', () => {
    const p = planProgress([plan('HIGH', 'COMPLETED'), plan('LOW', 'PENDING')]);

    expect(p.MEDIUM).toEqual({ total: 0, closed: 0, percent: null });
    expect(p.HIGH.percent).toBe(100);
    expect(p.LOW.percent).toBe(0);
  });

  it('mezcla de prioridades: cada una cuenta solo las suyas', () => {
    const p = planProgress([
      plan('HIGH', 'COMPLETED'),
      plan('HIGH', 'PENDING'),
      plan('MEDIUM', 'COMPLETED'),
      plan('MEDIUM', 'COMPLETED'),
      plan('MEDIUM', 'IN_PROGRESS'),
      plan('MEDIUM', 'PENDING'),
      plan('LOW', 'PENDING'),
    ]);

    expect(p.HIGH).toEqual({ total: 2, closed: 1, percent: 50 });
    expect(p.MEDIUM).toEqual({ total: 4, closed: 2, percent: 50 });
    expect(p.LOW).toEqual({ total: 1, closed: 0, percent: 0 });
    expect(p.overall).toEqual({ total: 7, closed: 3, percent: 43 });
  });

  it('solo COMPLETED cuenta como cerrada: una vencida, pendiente o en progreso no', () => {
    const p = planProgress([
      plan('HIGH', 'OVERDUE'),
      plan('HIGH', 'PENDING'),
      plan('HIGH', 'IN_PROGRESS'),
      plan('HIGH', 'COMPLETED'),
    ]);

    expect(p.HIGH).toEqual({ total: 4, closed: 1, percent: 25 });
  });

  it('el porcentaje se redondea al entero', () => {
    // 1 de 3 = 33,33 %  y  2 de 3 = 66,67 %
    const one = planProgress([
      plan('HIGH', 'COMPLETED'),
      plan('HIGH', 'PENDING'),
      plan('HIGH', 'PENDING'),
    ]);
    const two = planProgress([
      plan('HIGH', 'COMPLETED'),
      plan('HIGH', 'COMPLETED'),
      plan('HIGH', 'PENDING'),
    ]);

    expect(one.HIGH.percent).toBe(33);
    expect(two.HIGH.percent).toBe(67);
  });

  it('completar una acción sube la barra de su prioridad y la global', () => {
    const before = planProgress([plan('HIGH', 'PENDING'), plan('LOW', 'PENDING')]);
    const after = planProgress([plan('HIGH', 'COMPLETED'), plan('LOW', 'PENDING')]);

    expect(before.HIGH.percent).toBe(0);
    expect(after.HIGH.percent).toBe(100);
    expect(after.overall.percent).toBe(50);
    expect(after.LOW.percent).toBe(0);
  });
});
