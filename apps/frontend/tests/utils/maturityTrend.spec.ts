import { describe, it, expect } from 'vitest';
import {
  buildSeries,
  functionBreakdown,
  answeredPercent,
  compliantPercent,
  periodIndexOf,
  periodLabel,
  periodTitle,
  formatDelta,
  formatMeasure,
  profileOptions,
  summaries,
  FULL_CATALOG_KEY,
} from '@/utils/maturityTrend';
import type { ControlChange, MaturityTrendPoint, ProgressPeriod } from '@/types';

const point = (
  id: string,
  approvedAt: string,
  profile: { id: string | null; name: string },
  compliancePercent: number,
  maturityAverage: number,
): MaturityTrendPoint => ({
  evaluationId: id,
  evaluationName: `Evaluación ${id}`,
  status: 'APPROVED',
  provisional: false,
  date: approvedAt,
  approvedAt,
  profileId: profile.id,
  profileName: profile.name,
  compliantControls: Math.round(compliancePercent),
  totalControls: 100,
  compliancePercent,
  maturityAverage,
  globalMaturity: Math.round(maturityAverage),
});

const BASICO = { id: 'p-basico', name: 'Básico' };
const ESTANDAR = { id: 'p-estandar', name: 'Estándar' };
const FULL = { id: null, name: 'Catálogo completo' };

const points = [
  point('e3', '2025-06-02T10:00:00Z', ESTANDAR, 48, 1.21),
  point('e1', '2024-03-14T10:00:00Z', BASICO, 40, 0.7),
  point('e2', '2024-11-20T10:00:00Z', ESTANDAR, 35, 0.95),
  point('e4', '2026-04-05T10:00:00Z', BASICO, 71, 1.6),
  point('e5', '2025-09-10T10:00:00Z', FULL, 30, 0.5),
];

describe('maturityTrend (PRI-321)', () => {
  it('lista los perfiles de la organización con su cantidad, en orden fijo', () => {
    const opts = profileOptions(points);

    expect(opts.map((o) => [o.label, o.count])).toEqual([
      ['Básico', 2],
      ['Estándar', 2],
      ['Catálogo completo', 1],
    ]);
    expect(opts[2]!.key).toBe(FULL_CATALOG_KEY);
    expect(new Set(opts.map((o) => o.pointStyle)).size).toBe(3);
  });

  it('un perfil desconocido recibe un estilo de reserva', () => {
    const opts = profileOptions([
      point('x', '2025-01-01T00:00:00Z', { id: 'p-z', name: 'Zeta' }, 1, 1),
    ]);

    expect(opts[0]!.label).toBe('Zeta');
    expect(opts[0]!.color).toMatch(/^#/);
  });

  it('arma un eje de fechas compartido y una serie por perfil elegido', () => {
    const opts = profileOptions(points);
    const { labels, series } = buildSeries(points, opts, ['p-basico', 'p-estandar']);

    expect(labels).toEqual(['14/03/2024', '20/11/2024', '02/06/2025', '05/04/2026']);
    expect(series.map((s) => s.label)).toEqual(['Básico', 'Estándar']);
    expect(series[0]!.points.map((p) => p?.evaluationId ?? null)).toEqual(['e1', null, null, 'e4']);
    expect(series[1]!.points.map((p) => p?.evaluationId ?? null)).toEqual([null, 'e2', 'e3', null]);
  });

  it('sin perfiles elegidos no hay series ni fechas', () => {
    expect(buildSeries(points, profileOptions(points), [])).toEqual({ labels: [], series: [] });
  });

  it('resume primera, última y variación según la medida', () => {
    const opts = profileOptions(points);
    const all = opts.map((o) => o.key);

    const byCompliance = summaries(points, opts, all, 'compliance');
    expect(byCompliance[0]).toMatchObject({ label: 'Básico', count: 2, delta: 31 });
    expect(byCompliance[0]!.first.evaluationId).toBe('e1');
    expect(byCompliance[0]!.last.evaluationId).toBe('e4');
    expect(byCompliance[2]).toMatchObject({ label: 'Catálogo completo', delta: null });

    const byMaturity = summaries(points, opts, all, 'maturity');
    expect(byMaturity[1]!.delta).toBe(0.26);
  });

  it('formatea medidas y variaciones en español', () => {
    expect(formatMeasure(61.7, 'compliance')).toBe('61,7 %');
    expect(formatMeasure(1.5, 'maturity')).toBe('1,50');
    expect(formatDelta(27, 'compliance')).toBe('▲ +27 puntos');
    expect(formatDelta(1, 'compliance')).toBe('▲ +1 punto');
    expect(formatDelta(-0.3, 'maturity')).toBe('▼ −0,30');
    expect(formatDelta(0, 'maturity')).toBe('= sin cambios');
  });

  it('las provisorias se rotulan «Hoy (en curso)»', () => {
    const live = { ...point('e9', '2026-11-18T10:00:00Z', ESTANDAR, 70, 1.6), provisional: true };
    const all = [...points, live];
    const { labels } = buildSeries(all, profileOptions(all), ['p-estandar']);

    expect(labels[labels.length - 1]).toBe('Hoy (en curso)');
  });
});

const change = (code: string, functionName: string): ControlChange => ({
  controlId: code,
  code,
  description: `Control ${code}`,
  functionName,
  changedAt: '2026-10-29T12:00:00Z',
  changedBy: 'Ana Pérez',
});

const week = (over: Partial<ProgressPeriod> = {}): ProgressPeriod => ({
  periodStart: '2026-10-26',
  periodEnd: '2026-11-01',
  compliantControls: 142,
  answeredControls: 200,
  totalControls: 230,
  responseActivity: 12,
  evidenceActivity: 3,
  gained: [],
  lost: [],
  ...over,
});

describe('avance por período (PRI-321)', () => {
  it('resume el cambio neto por función, de mayor a menor', () => {
    const w = week({
      gained: [
        change('PR.AA-1', 'Proteger'),
        change('PR.DS-2', 'Proteger'),
        change('ID.AM-1', 'Identificar'),
        change('RS.MA-1', 'Responder'),
      ],
      lost: [change('RS.MA-2', 'Responder'), change('DE.CM-1', 'Detectar')],
    });

    expect(functionBreakdown(w)).toEqual([
      { name: 'Proteger', delta: 2 },
      { name: 'Detectar', delta: -1 },
      { name: 'Identificar', delta: 1 },
    ]);
  });

  it('separa el % respondido (trabajo) del % que cumple (resultado)', () => {
    expect(compliantPercent(week())).toBe(61.7);
    expect(answeredPercent(week())).toBe(87);
    expect(compliantPercent(week({ totalControls: 0 }))).toBe(0);
  });

  it('rotula el período según la escala', () => {
    expect(periodLabel(week(), 'WEEK')).toBe('26/10');
    expect(periodLabel(week(), 'DAY')).toBe('26/10');
    expect(periodLabel(week({ periodStart: '2026-11-01' }), 'MONTH')).toMatch(/nov/i);
    expect(periodTitle(week(), 'WEEK')).toBe('Semana del 26/10 al 01/11');
    expect(periodTitle(week(), 'DAY')).toBe('Día 26/10/2026');
    expect(periodTitle(week({ periodStart: '2026-11-01' }), 'MONTH')).toMatch(/^Noviembre/);
  });

  it('ubica una fecha en su período', () => {
    const weeks = [week(), week({ periodStart: '2026-11-02', periodEnd: '2026-11-08' })];

    expect(periodIndexOf(weeks, '2026-11-03T12:00:00')).toBe(1);
    expect(periodIndexOf(weeks, '2026-10-26T12:00:00')).toBe(0);
    expect(periodIndexOf(weeks, '2025-01-01T12:00:00')).toBe(-1);
  });
});
