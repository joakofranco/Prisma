import { addMonths, format } from 'date-fns';
import type { ImprovementPlan, PlanDeadlines } from '@/types';

// RF-PLN-03: mismas reglas que PlanDeadlineSettingsService en el backend.
export const MIN_DEADLINE_MONTHS = 1;
export const MAX_DEADLINE_MONTHS = 60;

/** Valores del requerimiento (los que deja la migración V28). */
export const DEFAULT_PLAN_DEADLINES: PlanDeadlines = {
  highMonths: 3,
  mediumMonths: 6,
  lowMonths: 12,
};

export function monthsFor(priority: ImprovementPlan['priority'], deadlines: PlanDeadlines): number {
  return {
    HIGH: deadlines.highMonths,
    MEDIUM: deadlines.mediumMonths,
    LOW: deadlines.lowMonths,
  }[priority];
}

/** Fecha límite sugerida (AAAA-MM-DD) para una acción creada en `from`. */
export function defaultDueDate(
  priority: ImprovementPlan['priority'],
  deadlines: PlanDeadlines,
  from: Date = new Date(),
): string {
  return format(addMonths(from, monthsFor(priority, deadlines)), 'yyyy-MM-dd');
}

export interface PlanDeadlineErrors {
  highMonths: string;
  mediumMonths: string;
  lowMonths: string;
}

const RANGE_ERROR = `Debe estar entre ${MIN_DEADLINE_MONTHS} y ${MAX_DEADLINE_MONTHS} meses`;

function inRange(value: number): boolean {
  return Number.isInteger(value) && value >= MIN_DEADLINE_MONTHS && value <= MAX_DEADLINE_MONTHS;
}

/** Errores por campo ('' = sin error): rango 1–60 y orden Alta ≤ Media ≤ Baja. */
export function validatePlanDeadlines(d: PlanDeadlines): PlanDeadlineErrors {
  const errors: PlanDeadlineErrors = { highMonths: '', mediumMonths: '', lowMonths: '' };
  (Object.keys(errors) as (keyof PlanDeadlineErrors)[]).forEach((key) => {
    if (!inRange(d[key])) errors[key] = RANGE_ERROR;
  });
  if (!errors.highMonths && !errors.mediumMonths && d.highMonths > d.mediumMonths) {
    errors.highMonths = `Alta no puede superar a Media (${d.mediumMonths} meses)`;
  }
  if (!errors.mediumMonths && !errors.lowMonths && d.mediumMonths > d.lowMonths) {
    errors.mediumMonths = `Media no puede superar a Baja (${d.lowMonths} meses)`;
  }
  return errors;
}
