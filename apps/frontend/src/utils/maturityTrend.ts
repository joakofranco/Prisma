// RF-VIZ-07 (PRI-321): armado de la tendencia por perfil a partir de los puntos que devuelve
// /api/dashboard/maturity-trend (una sola organización). Funciones puras: las usa el bloque del
// Panel y se testean aparte.
import { formatDate } from '@/utils/helpers';
import type { MaturityTrendPoint, ProgressGranularity, ProgressPeriod } from '@/types';

export type TrendMetric = 'compliance' | 'maturity';

export interface TrendSeries {
  key: string;
  label: string;
  color: string;
  pointStyle: 'circle' | 'rectRot' | 'rect' | 'triangle';
  points: (MaturityTrendPoint | null)[];
}

export interface ProfileOption {
  key: string;
  label: string;
  count: number;
  color: string;
  pointStyle: TrendSeries['pointStyle'];
}

export interface ProfileSummary {
  key: string;
  label: string;
  count: number;
  first: MaturityTrendPoint;
  last: MaturityTrendPoint;
  /** Variación entre la primera y la última; null si hay una sola evaluación. */
  delta: number | null;
}

/** Clave del perfil de un punto: su id, o «full» para las evaluaciones sin perfil. */
export const FULL_CATALOG_KEY = 'full';
export const profileKey = (p: MaturityTrendPoint) => p.profileId ?? FULL_CATALOG_KEY;

// Color y forma por perfil (forma además de color: no depender solo del color, WCAG 1.4.1).
// Colores con contraste suficiente sobre blanco y sobre slate-800.
const KNOWN: Record<string, Pick<ProfileOption, 'color' | 'pointStyle'>> = {
  Básico: { color: '#ea580c', pointStyle: 'rectRot' },
  Estándar: { color: '#2563eb', pointStyle: 'circle' },
  Avanzado: { color: '#7c3aed', pointStyle: 'triangle' },
  'Catálogo completo': { color: '#0f766e', pointStyle: 'rect' },
};
const ORDER = ['Básico', 'Estándar', 'Avanzado', 'Catálogo completo'];
const FALLBACK: Pick<ProfileOption, 'color' | 'pointStyle'>[] = [
  { color: '#be185d', pointStyle: 'circle' },
  { color: '#4d7c0f', pointStyle: 'rectRot' },
  { color: '#b45309', pointStyle: 'triangle' },
];

const time = (p: MaturityTrendPoint) => new Date(p.date).getTime();

/** Perfiles que tienen evaluaciones en esta organización, con su cantidad, en orden fijo. */
export function profileOptions(points: MaturityTrendPoint[]): ProfileOption[] {
  const byKey = new Map<string, { label: string; count: number }>();
  for (const p of points) {
    const key = profileKey(p);
    const entry = byKey.get(key) ?? { label: p.profileName, count: 0 };
    entry.count++;
    byKey.set(key, entry);
  }
  const rank = (label: string) => {
    const i = ORDER.indexOf(label);
    return i === -1 ? ORDER.length : i;
  };
  let extra = 0;
  return [...byKey.entries()]
    .sort(([, a], [, b]) => rank(a.label) - rank(b.label) || a.label.localeCompare(b.label, 'es'))
    .map(([key, { label, count }]) => {
      const style = KNOWN[label] ?? FALLBACK[extra++ % FALLBACK.length]!;
      return { key, label, count, ...style };
    });
}

/**
 * Eje de fechas compartido y una serie por perfil elegido. Cada serie lleva el punto en la
 * posición de su fecha y null en las demás.
 */
export function buildSeries(
  points: MaturityTrendPoint[],
  options: ProfileOption[],
  selected: string[],
): { labels: string[]; series: TrendSeries[] } {
  const visible = points.filter((p) => selected.includes(profileKey(p)));
  const dates = [...new Set(visible.map((p) => p.date))].sort(
    (a, b) => new Date(a).getTime() - new Date(b).getTime(),
  );
  // Las provisorias comparten la fecha «ahora»: se rotulan «Hoy (en curso)».
  const provisionalDates = new Set(visible.filter((p) => p.provisional).map((p) => p.date));
  const labels = dates.map((d) => (provisionalDates.has(d) ? 'Hoy (en curso)' : formatDate(d)));
  const series = options
    .filter((o) => selected.includes(o.key))
    .map((o) => {
      const slots: (MaturityTrendPoint | null)[] = dates.map(() => null);
      for (const p of visible) {
        if (profileKey(p) === o.key) slots[dates.indexOf(p.date)] = p;
      }
      return {
        key: o.key,
        label: o.label,
        color: o.color,
        pointStyle: o.pointStyle,
        points: slots,
      };
    });
  return { labels, series };
}

/** Primera, última y variación de cada perfil elegido, según la medida que se muestra. */
export function summaries(
  points: MaturityTrendPoint[],
  options: ProfileOption[],
  selected: string[],
  metric: TrendMetric,
): ProfileSummary[] {
  const measure = (p: MaturityTrendPoint) =>
    metric === 'compliance' ? p.compliancePercent : p.maturityAverage;
  return options
    .filter((o) => selected.includes(o.key))
    .map((o) => {
      const own = points.filter((p) => profileKey(p) === o.key).sort((a, b) => time(a) - time(b));
      const first = own[0]!;
      const last = own[own.length - 1]!;
      const delta = own.length > 1 ? round(measure(last) - measure(first), metric) : null;
      return { key: o.key, label: o.label, count: own.length, first, last, delta };
    });
}

function round(v: number, metric: TrendMetric): number {
  const f = metric === 'compliance' ? 10 : 100;
  return Math.round(v * f) / f;
}

/** «62 %» o «1,48», con coma decimal. */
export function formatMeasure(v: number, metric: TrendMetric): string {
  return metric === 'compliance'
    ? `${v.toLocaleString('es-UY', { maximumFractionDigits: 1 })} %`
    : v.toLocaleString('es-UY', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** «▲ +27 puntos», «▼ −0,30» o «= sin cambios». */
export function formatDelta(delta: number, metric: TrendMetric): string {
  if (delta === 0) return '= sin cambios';
  const abs = Math.abs(delta);
  const n =
    metric === 'compliance'
      ? `${abs.toLocaleString('es-UY', { maximumFractionDigits: 1 })} ${abs === 1 ? 'punto' : 'puntos'}`
      : abs.toLocaleString('es-UY', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return delta > 0 ? `▲ +${n}` : `▼ −${n}`;
}

// ---- Avance de una evaluación por día, semana o mes ----

/** Cambio neto por función en un período (ganados − perdidos), de mayor a menor movimiento. */
export function functionBreakdown(period: ProgressPeriod): { name: string; delta: number }[] {
  const delta = new Map<string, number>();
  for (const c of period.gained) delta.set(c.functionName, (delta.get(c.functionName) ?? 0) + 1);
  for (const c of period.lost) delta.set(c.functionName, (delta.get(c.functionName) ?? 0) - 1);
  return [...delta.entries()]
    .filter(([, d]) => d !== 0)
    .map(([name, d]) => ({ name, delta: d }))
    .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta) || a.name.localeCompare(b.name, 'es'));
}

const pct = (n: number, total: number) => (total === 0 ? 0 : Math.round((n * 1000) / total) / 10);

/** % de controles que cumplen al cierre del período (el resultado), con un decimal. */
export function compliantPercent(period: ProgressPeriod): number {
  return pct(period.compliantControls, period.totalControls);
}

/** % de controles respondidos al cierre del período (el trabajo), con un decimal. */
export function answeredPercent(period: ProgressPeriod): number {
  return pct(period.answeredControls, period.totalControls);
}

const day = (d: string) => new Date(`${d}T12:00:00`);
const short = (d: string) => formatDate(day(d)).slice(0, 5);

/** Rótulo corto del eje: «18/11» para días y semanas, «nov. 2026» para meses. */
export function periodLabel(period: ProgressPeriod, granularity: ProgressGranularity): string {
  return granularity === 'MONTH'
    ? day(period.periodStart).toLocaleDateString('es-UY', { month: 'short', year: 'numeric' })
    : short(period.periodStart);
}

/** «Día 18/11/2026», «Semana del 27/10 al 02/11» o «Noviembre de 2026». */
export function periodTitle(period: ProgressPeriod, granularity: ProgressGranularity): string {
  if (granularity === 'DAY') return `Día ${formatDate(day(period.periodStart))}`;
  if (granularity === 'MONTH') {
    const text = day(period.periodStart).toLocaleDateString('es-UY', {
      month: 'long',
      year: 'numeric',
    });
    return text.charAt(0).toUpperCase() + text.slice(1);
  }
  return `Semana del ${short(period.periodStart)} al ${short(period.periodEnd)}`;
}

/** Índice del período que contiene la fecha (o -1 si cae fuera). */
export function periodIndexOf(periods: ProgressPeriod[], isoDate: string): number {
  const local = new Date(isoDate);
  const key = Number.isNaN(local.getTime())
    ? isoDate.slice(0, 10)
    : `${local.getFullYear()}-${String(local.getMonth() + 1).padStart(2, '0')}-${String(local.getDate()).padStart(2, '0')}`;
  return periods.findIndex((p) => p.periodStart <= key && key <= p.periodEnd);
}
