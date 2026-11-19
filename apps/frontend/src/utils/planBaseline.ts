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
