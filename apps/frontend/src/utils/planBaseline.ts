import type { PlanBaselineItem, PlanCompliance } from '@/types';

export function signedDays(days: number): string {
  const unit = Math.abs(days) === 1 ? 'día' : 'días';
  return `${days > 0 ? '+' : ''}${days} ${unit}`;
}

export function signedPoints(points: number): string {
  return `${points > 0 ? '+' : ''}${points} pts`;
}

export function shiftLabel(item: Pick<PlanBaselineItem, 'replanned' | 'dueDateShiftDays'>): string {
  if (!item.replanned) return 'Sin cambios';
  return item.dueDateShiftDays === null ? 'Replanificada' : signedDays(item.dueDateShiftDays);
}

export function complianceLabel(item: Pick<PlanBaselineItem, 'compliance' | 'delayDays'>): string {
  const days = item.delayDays ?? 0;
  const unit = days === 1 ? 'día' : 'días';
  const labels: Record<PlanCompliance, string> = {
    ON_TIME: 'En plazo',
    LATE: `Tarde (${days} ${unit})`,
    DELAYED: `Atrasada (${days} ${unit})`,
    IN_TERM: 'En curso',
    NO_CLOSE_DATE: 'Sin fecha de cierre',
    NO_DATE: 'Sin fecha',
  };
  return labels[item.compliance];
}

export function complianceClass(compliance: PlanCompliance): string {
  const neutral = 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
  const classes: Record<PlanCompliance, string> = {
    ON_TIME: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900 dark:text-emerald-300',
    LATE: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
    DELAYED: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300',
    IN_TERM: 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300',
    NO_CLOSE_DATE: neutral,
    NO_DATE: neutral,
  };
  return classes[compliance];
}

export const SHIFT_CHART_LIMIT = 8;

export interface ComplianceSlice {
  key: string;
  label: string;
  color: string;
  count: number;
}

export function complianceSlices(
  items: readonly Pick<PlanBaselineItem, 'compliance'>[],
): ComplianceSlice[] {
  const defs: { key: string; label: string; color: string; match: PlanCompliance[] }[] = [
    { key: 'onTime', label: 'En plazo', color: '#059669', match: ['ON_TIME'] },
    { key: 'late', label: 'Tarde', color: '#d97706', match: ['LATE'] },
    { key: 'delayed', label: 'Atrasadas', color: '#dc2626', match: ['DELAYED'] },
    { key: 'inTerm', label: 'En curso', color: '#2563eb', match: ['IN_TERM'] },
    { key: 'noDate', label: 'Sin fecha', color: '#94a3b8', match: ['NO_CLOSE_DATE', 'NO_DATE'] },
  ];
  return defs
    .map((def) => ({
      key: def.key,
      label: def.label,
      color: def.color,
      count: items.filter((i) => def.match.includes(i.compliance)).length,
    }))
    .filter((slice) => slice.count > 0);
}

export interface ShiftBar {
  id: string;
  label: string;
  days: number;
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

export function shiftBars(
  items: readonly Pick<
    PlanBaselineItem,
    'id' | 'controlCode' | 'action' | 'replanned' | 'dueDateShiftDays'
  >[],
  limit = SHIFT_CHART_LIMIT,
): { bars: ShiftBar[]; hidden: number } {
  const moved: ShiftBar[] = items
    .filter((i) => i.replanned && i.dueDateShiftDays !== null && i.dueDateShiftDays !== 0)
    .map((i) => ({
      id: i.id,
      label: i.controlCode ?? truncate(i.action, 28),
      days: i.dueDateShiftDays as number,
    }))
    .sort((a, b) => Math.abs(b.days) - Math.abs(a.days));
  return { bars: moved.slice(0, limit), hidden: Math.max(0, moved.length - limit) };
}
