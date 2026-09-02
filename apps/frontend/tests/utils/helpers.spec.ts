import { describe, it, expect } from 'vitest';
import {
  formatDate,
  formatDateTime,
  getMaturityLabel,
  getMaturityColor,
  getStatusLabel,
  getStatusColor,
  getStatusBgColor,
  getStatusDescription,
  getStatusResponsibleLabel,
  getPriorityLabel,
  getPriorityColor,
  formatFileSize,
  truncate,
  classNames,
  generateId,
  getErrorMessage,
} from '../../src/utils/helpers';

describe('formatDate / formatDateTime', () => {
  it('formatea una fecha ISO a dd/MM/yyyy', () => {
    expect(formatDate('2026-09-06T12:00:00Z')).toBe('06/09/2026');
  });

  it('formatea fecha y hora a dd/MM/yyyy HH:mm', () => {
    // formatDateTime usa la hora LOCAL del entorno donde corre, así que solo se valida el
    // segmento de fecha (estable) y que la hora tenga el formato HH:mm esperado.
    const result = formatDateTime('2026-09-06T12:00:00Z');
    expect(result).toMatch(/^06\/09\/2026 \d{2}:\d{2}$/);
  });
});

describe('getMaturityLabel / getMaturityColor', () => {
  it('devuelve la etiqueta y color de cada nivel 0-4 del catálogo MCU 5.0', () => {
    expect(getMaturityLabel(0)).toBe('Sin nivel');
    expect(getMaturityLabel(4)).toBe('Gestionado');
    expect(getMaturityColor(2)).toBe('#eab308');
  });

  it('devuelve un fallback para un nivel fuera de rango', () => {
    expect(getMaturityLabel(99)).toBe('Sin definir');
    expect(getMaturityColor(99)).toBe('#6b7280');
  });
});

describe('estado de evaluación (getStatus*)', () => {
  it('expone label, color, bgColor y descripción de cada estado del ciclo de vida', () => {
    expect(getStatusLabel('DRAFT')).toBe('Borrador');
    expect(getStatusColor('APPROVED')).toBe('#22c55e');
    expect(getStatusBgColor('IN_AUDIT')).toBe('#fef3c7');
    expect(getStatusDescription('ARCHIVED')).toContain('ya no admite cambios');
  });

  it('devuelve el propio valor como fallback para un estado desconocido', () => {
    expect(getStatusLabel('BOGUS')).toBe('BOGUS');
  });

  it('resuelve quién tiene la pelota en cada estado, o null en los que no aplica', () => {
    expect(getStatusResponsibleLabel('DRAFT')).toBe('Responsable de Organización');
    expect(getStatusResponsibleLabel('IN_PROGRESS')).toBe('Evaluador Interno');
    expect(getStatusResponsibleLabel('READY_FOR_AUDIT')).toBe('Auditor');
    expect(getStatusResponsibleLabel('APPROVED')).toBeNull();
    expect(getStatusResponsibleLabel('ARCHIVED')).toBeNull();
  });
});

describe('getPriorityLabel / getPriorityColor', () => {
  it('traduce las prioridades HIGH/MEDIUM/LOW del plan de mejora', () => {
    expect(getPriorityLabel('HIGH')).toBe('Alta');
    expect(getPriorityLabel('MEDIUM')).toBe('Media');
    expect(getPriorityLabel('LOW')).toBe('Baja');
    expect(getPriorityColor('HIGH')).toBe('#ef4444');
  });
});

describe('formatFileSize', () => {
  it('formatea 0 bytes como caso especial', () => {
    expect(formatFileSize(0)).toBe('0 Bytes');
  });

  it('elige la unidad correcta según la magnitud', () => {
    expect(formatFileSize(500)).toBe('500 Bytes');
    expect(formatFileSize(2048)).toBe('2 KB');
    expect(formatFileSize(5 * 1024 * 1024)).toBe('5 MB');
  });
});

describe('truncate', () => {
  it('no toca strings más cortos que el límite', () => {
    expect(truncate('PRISMA', 10)).toBe('PRISMA');
  });

  it('corta y agrega elipsis a strings más largos que el límite', () => {
    expect(truncate('Plataforma de Revisión Integral de Seguridad', 12)).toBe(
      'Plataforma d...',
    );
  });
});

describe('classNames', () => {
  it('une clases truthy con espacio y descarta falsy/null/undefined', () => {
    expect(classNames('a', false, 'b', undefined, null, '', 'c')).toBe('a b c');
  });
});

describe('generateId', () => {
  it('genera ids no vacíos y distintos entre sí', () => {
    const a = generateId();
    const b = generateId();
    expect(a).not.toBe('');
    expect(a).not.toBe(b);
  });
});

describe('getErrorMessage', () => {
  it('prioriza el mensaje que manda el backend en response.data.message', () => {
    const err = { response: { data: { message: 'El NIT es obligatorio' } } };
    expect(getErrorMessage(err)).toBe('El NIT es obligatorio');
  });

  it('usa el mensaje de un Error nativo cuando no hay response', () => {
    expect(getErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('cae al fallback cuando no reconoce la forma del error', () => {
    expect(getErrorMessage('algo raro')).toBe('Ocurrió un error inesperado');
    expect(getErrorMessage(null, 'mensaje custom')).toBe('mensaje custom');
  });
});
