import { format, formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import {
  MATURITY_LEVELS,
  MATURITY_TEXT_COLORS,
  STATUS_BADGE_COLORS,
  EVALUATION_STATUS_CONFIG,
  PRIORITY_CONFIG,
  ROLE_LABELS,
} from './constants';

export function formatDate(date: string | Date): string {
  return format(new Date(date), 'dd/MM/yyyy', { locale: es });
}

export function formatDateTime(date: string | Date): string {
  return format(new Date(date), 'dd/MM/yyyy HH:mm', { locale: es });
}

export function formatRelativeTime(date: string | Date): string {
  return formatDistanceToNow(new Date(date), { addSuffix: true, locale: es });
}

export function getMaturityLabel(level: number): string {
  return MATURITY_LEVELS[level]?.label || 'Sin definir';
}

export function getMaturityColor(level: number): string {
  return MATURITY_LEVELS[level]?.color || '#6b7280';
}

export function getStatusLabel(status: string): string {
  return EVALUATION_STATUS_CONFIG[status]?.label || status;
}

export function getStatusColor(status: string): string {
  return EVALUATION_STATUS_CONFIG[status]?.color || '#6b7280';
}

export function getStatusBgColor(status: string): string {
  return EVALUATION_STATUS_CONFIG[status]?.bgColor || '#f3f4f6';
}

/**
 * Estilo de una insignia de estado accesible: variables CSS que la clase `.badge-themed` (main.css)
 * convierte en color/fondo según el tema. Usar junto con `class="badge-themed"`.
 */
export function getStatusBadgeStyle(status: string): Record<string, string> {
  const c = STATUS_BADGE_COLORS[status] ?? STATUS_BADGE_COLORS.DRAFT;
  return {
    '--badge-fg': c.fg,
    '--badge-bg': c.bg,
    '--badge-fg-dark': c.fgDark,
    '--badge-bg-dark': c.bgDark,
  };
}

/** Estilo de texto accesible para un nivel de madurez; usar con `class="maturity-text"`. */
export function getMaturityTextStyle(level: number): Record<string, string> {
  const c = MATURITY_TEXT_COLORS[level] ?? MATURITY_TEXT_COLORS[0];
  return { '--maturity-fg': c.fg, '--maturity-fg-dark': c.fgDark };
}

export function getStatusDescription(status: string): string {
  return EVALUATION_STATUS_CONFIG[status]?.description || '';
}

/** Nombre legible de quién debe actuar en este estado, o null en estados sin acción pendiente. */
export function getStatusResponsibleLabel(status: string): string | null {
  const role = EVALUATION_STATUS_CONFIG[status]?.responsibleRole;
  return role ? ROLE_LABELS[role] || role : null;
}

export function getPriorityLabel(priority: string): string {
  return PRIORITY_CONFIG[priority]?.label || priority;
}

export function getPriorityColor(priority: string): string {
  return PRIORITY_CONFIG[priority]?.color || '#6b7280';
}

/** Formatea un número con N decimales fijos ("1.38"); "—" si no es finito (p. ej. media de 0 items). */
/**
 * Alternativa textual de un gráfico (Chart.js dibuja en un <canvas>, que un lector de pantalla no
 * puede interpretar): "Título. Serie: etiqueta valor, etiqueta valor". WCAG 1.1.1.
 */
export function describeChart(
  title: string,
  labels: readonly string[],
  datasets: readonly { label?: string; data: readonly number[] }[],
): string {
  const series = datasets
    .map((ds) => {
      const points = labels.map((l, i) => `${l} ${ds.data[i] ?? 0}`).join(', ');
      return ds.label ? `${ds.label}: ${points}` : points;
    })
    .join('. ');
  return series ? `${title}. ${series}` : `${title}. Sin datos`;
}

export function formatDecimals(value: number, decimals = 2): string {
  return Number.isFinite(value) ? value.toFixed(decimals) : '—';
}

export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

export function truncate(str: string, length: number): string {
  if (str.length <= length) return str;
  return str.slice(0, length) + '...';
}

export function classNames(...classes: (string | boolean | undefined | null)[]): string {
  return classes.filter(Boolean).join(' ');
}

export function generateId(): string {
  return Math.random().toString(36).substring(2, 15);
}

/**
 * Mensaje de error de un campo puntual que mandó el backend en `details` (p.ej. un 409 por RUT
 * duplicado trae `details.rut`), o undefined. Sirve para marcar ese campo del formulario.
 */
export function getFieldError(err: unknown, field: string): string | undefined {
  if (err && typeof err === 'object' && 'response' in err) {
    const resp = (err as { response?: { data?: { details?: Record<string, string[]> } } }).response;
    return resp?.data?.details?.[field]?.[0];
  }
  return undefined;
}

export function getErrorMessage(err: unknown, fallback = 'Ocurrió un error inesperado'): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const resp = (
      err as { response?: { data?: { message?: string; details?: Record<string, string[]> } } }
    ).response;
    // En un 400 de validación el backend manda un `message` genérico ("Error de validación") y el
    // motivo concreto por campo en `details`: se muestra ese (el primero) para que el usuario sepa
    // qué corregir.
    const detail = Object.values(resp?.data?.details ?? {}).find((msgs) => msgs?.length)?.[0];
    if (detail) return detail;
    if (resp?.data?.message) return resp.data.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
