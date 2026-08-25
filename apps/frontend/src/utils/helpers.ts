import { format, formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import {
  MATURITY_LEVELS,
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

export function getErrorMessage(err: unknown, fallback = 'Ocurrió un error inesperado'): string {
  if (err && typeof err === 'object' && 'response' in err) {
    const resp = (err as { response?: { data?: { message?: string } } }).response;
    if (resp?.data?.message) return resp.data.message;
  }
  if (err instanceof Error && err.message) return err.message;
  return fallback;
}
