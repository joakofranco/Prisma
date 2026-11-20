import type { AiJob, AiJobStatus } from '@/types';

/**
 * Trabajos de IA asíncronos (cola): sondeo con espera creciente, textos de estado y de error, y
 * los ids de trabajo que sobreviven a cerrar el modal o recargar la página.
 */

const FIRST_DELAY_MS = 3000;
const MAX_DELAY_MS = 8000;
const GROWTH = 1.25;

/** Espera antes del sondeo número `attempt` (0 = el primero): 3 s y crece hasta 8 s. */
export function nextPollDelay(attempt: number): number {
  const delay = FIRST_DELAY_MS * Math.pow(GROWTH, Math.max(0, attempt));
  return Math.min(Math.round(delay), MAX_DELAY_MS);
}

export function isActive(status: AiJobStatus | undefined): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

/** Texto de estado de un trabajo en curso («en cola», «generando»). */
export function jobProgressLabel(job: Pick<AiJob, 'status' | 'queuePosition'>): string {
  if (job.status === 'QUEUED') {
    return job.queuePosition && job.queuePosition > 1
      ? `En cola (posición ${job.queuePosition})…`
      : 'En cola…';
  }
  return 'Generando sugerencia…';
}

const ERROR_MESSAGES: Record<string, string> = {
  AI_UNAVAILABLE:
    'El asistente de IA no está disponible en este momento. Probá de nuevo en unos minutos.',
  AI_ERROR: 'El asistente de IA tuvo un error al generar la sugerencia. Probá de nuevo.',
  AI_REJECTED: 'El asistente de IA no aceptó el pedido. Redactá la acción a mano.',
  PROMPT_TOO_LARGE:
    'Este control tiene demasiado contenido para el modelo de IA. Redactá la acción a mano.',
  WORKER_LOST: 'La generación se interrumpió antes de terminar. Volvé a pedirla.',
};

/** Qué pasó y cómo seguir, según el código de error del trabajo. */
export function jobErrorMessage(job: Pick<AiJob, 'errorCode' | 'errorMessage'>): string {
  if (job.errorCode && ERROR_MESSAGES[job.errorCode]) return ERROR_MESSAGES[job.errorCode];
  return job.errorMessage || 'No se pudo generar la sugerencia. Probá de nuevo.';
}

// ---- ids de trabajo persistidos (por evaluación) ---------------------------------------------

const STORAGE_PREFIX = 'prisma.aiGuideJobs.';

type StoredJobs = Record<string, string>;

/** localStorage puede no existir o fallar (ventana privada, datos bloqueados): nunca rompe. */
export function loadStoredJobs(evaluationId: string): StoredJobs {
  try {
    const raw = window.localStorage.getItem(STORAGE_PREFIX + evaluationId);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.fromEntries(
        Object.entries(parsed as Record<string, unknown>).filter(
          (entry): entry is [string, string] => typeof entry[1] === 'string',
        ),
      );
    }
  } catch {
    // sin almacenamiento o contenido corrupto: se parte de cero
  }
  return {};
}

export function saveStoredJobs(evaluationId: string, jobs: StoredJobs): void {
  try {
    if (Object.keys(jobs).length === 0) {
      window.localStorage.removeItem(STORAGE_PREFIX + evaluationId);
    } else {
      window.localStorage.setItem(STORAGE_PREFIX + evaluationId, JSON.stringify(jobs));
    }
  } catch {
    // sin almacenamiento: el trabajo sigue corriendo, solo no se retoma tras recargar
  }
}
