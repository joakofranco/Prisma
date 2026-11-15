import type { ImprovementPlan } from '@/types';

// Avance de remediación (RF-PLN-06): porcentaje de acciones cerradas por prioridad. Se calcula acá,
// con la lista de planes que la pantalla ya carga, para que se actualice al crear, editar o
// completar una acción sin pedir nada más al servidor.

export interface Progress {
  total: number;
  closed: number;
  /** Entero de 0 a 100, o `null` si no hay acciones (se muestra «Sin acciones», no «0 %»). */
  percent: number | null;
}

export interface PlanProgress {
  overall: Progress;
  HIGH: Progress;
  MEDIUM: Progress;
  LOW: Progress;
}

function progressOf(plans: Pick<ImprovementPlan, 'status'>[]): Progress {
  const total = plans.length;
  // «Cerrada» es solo COMPLETED: las pendientes, en progreso y vencidas siguen abiertas.
  const closed = plans.filter((p) => p.status === 'COMPLETED').length;
  return { total, closed, percent: total === 0 ? null : Math.round((closed / total) * 100) };
}

export function planProgress(plans: Pick<ImprovementPlan, 'priority' | 'status'>[]): PlanProgress {
  return {
    overall: progressOf(plans),
    HIGH: progressOf(plans.filter((p) => p.priority === 'HIGH')),
    MEDIUM: progressOf(plans.filter((p) => p.priority === 'MEDIUM')),
    LOW: progressOf(plans.filter((p) => p.priority === 'LOW')),
  };
}
