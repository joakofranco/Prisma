import type { EvaluationStatus } from '@/types';

/**
 * Reglas de estado de una evaluación: qué acciones admite cada estado. Reflejan lo que ya exige el
 * backend (`Evaluation.isSelfAssessmentEditable`, `EvaluationFlagService`, `EvidenceService`,
 * `AuditObservationService`, `AuditVerdictService`) para que la pantalla deshabilite lo que el
 * servidor rechazaría, en vez de dejar el botón activo y mostrar un error después.
 *
 * Todas aceptan `null`/`undefined` (estado todavía no cargado o desconocido) y en ese caso NO
 * bloquean: la pantalla no inventa restricciones y el backend sigue validando.
 */
type Status = EvaluationStatus | string | null | undefined;

/** Estados en que la organización puede cargar respuestas, comentarios y evidencias. */
const SELF_ASSESSMENT_EDITABLE: readonly string[] = ['DRAFT', 'IN_PROGRESS', 'RETURNED'];

/**
 * Respuestas, comentarios de subcategoría y evidencias (subir, borrar, reindexar con IA): solo
 * Borrador, En curso y Devuelta. Desde que se envía a auditoría quedan de solo lectura.
 */
export function isSelfAssessmentEditable(status: Status): boolean {
  return !status || SELF_ASSESSMENT_EDITABLE.includes(status);
}

/** «Marcar para revisar» un control: mientras se responde y durante la auditoría. */
export function canFlagControls(status: Status): boolean {
  return !status || SELF_ASSESSMENT_EDITABLE.includes(status) || status === 'IN_AUDIT';
}

/** Observaciones, dictámenes y búsqueda asistida de citas: solo mientras la evaluación está En auditoría. */
export function isAuditWorkOpen(status: Status): boolean {
  return !status || status === 'IN_AUDIT';
}

const STATUS_NAMES: Record<string, string> = {
  DRAFT: 'Borrador',
  IN_PROGRESS: 'En curso',
  READY_FOR_AUDIT: 'Lista para auditoría',
  IN_AUDIT: 'En auditoría',
  RETURNED: 'Devuelta',
  APPROVED: 'Aprobada',
  ARCHIVED: 'Archivada',
};

export function statusName(status: Status): string {
  return (status && STATUS_NAMES[status]) || String(status ?? '');
}

/** Explicación para el tooltip / aviso cuando la carga está cerrada por el estado. */
export function selfAssessmentLockedReason(status: Status): string {
  return `La evaluación está en estado «${statusName(status)}»: ya no admite cambios.`;
}
