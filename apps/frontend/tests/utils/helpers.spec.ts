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
  getFieldError,
  describeChart,
  getStatusBadgeStyle,
  getMaturityTextStyle,
} from '../../src/utils/helpers';
import {
  STATUS_BADGE_COLORS,
  MATURITY_TEXT_COLORS,
  EVALUATION_STATUS_CONFIG,
} from '../../src/utils/constants';

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
    expect(truncate('Plataforma de Revisión Integral de Seguridad', 12)).toBe('Plataforma d...');
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

describe('getFieldError', () => {
  it('devuelve el primer mensaje del campo pedido', () => {
    const err = {
      response: { data: { details: { rut: ['Ya existe una organización con ese RUT'] } } },
    };
    expect(getFieldError(err, 'rut')).toBe('Ya existe una organización con ese RUT');
  });

  it('devuelve undefined si el campo no viene, no hay details o el error no es de red', () => {
    expect(
      getFieldError({ response: { data: { details: { name: ['x'] } } } }, 'rut'),
    ).toBeUndefined();
    expect(getFieldError({ response: { data: {} } }, 'rut')).toBeUndefined();
    expect(getFieldError(new Error('boom'), 'rut')).toBeUndefined();
    expect(getFieldError(null, 'rut')).toBeUndefined();
  });
});

describe('getErrorMessage', () => {
  it('prioriza el mensaje que manda el backend en response.data.message', () => {
    const err = { response: { data: { message: 'El NIT es obligatorio' } } };
    expect(getErrorMessage(err)).toBe('El NIT es obligatorio');
  });

  it('prioriza el detalle por campo (details) sobre el message genérico de validación', () => {
    const err = {
      response: {
        data: {
          message: 'Error de validación',
          details: { email: ['El email no es válido (ej.: usuario@dominio.com)'] },
        },
      },
    };
    expect(getErrorMessage(err)).toBe('El email no es válido (ej.: usuario@dominio.com)');
  });

  it('ignora details vacío y usa message', () => {
    const err = { response: { data: { message: 'Conflicto', details: {} } } };
    expect(getErrorMessage(err)).toBe('Conflicto');
  });

  it('usa el mensaje de un Error nativo cuando no hay response', () => {
    expect(getErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('cae al fallback cuando no reconoce la forma del error', () => {
    expect(getErrorMessage('algo raro')).toBe('Ocurrió un error inesperado');
    expect(getErrorMessage(null, 'mensaje custom')).toBe('mensaje custom');
  });
});

describe('describeChart (alternativa textual de los gráficos)', () => {
  it('describe cada serie con sus etiquetas y valores', () => {
    expect(
      describeChart(
        'Madurez por función',
        ['Gobernar', 'Proteger'],
        [{ label: 'Nivel', data: [2, 3] }],
      ),
    ).toBe('Madurez por función. Nivel: Gobernar 2, Proteger 3');
  });

  it('une varias series y omite el prefijo si la serie no tiene nombre', () => {
    const text = describeChart('Radar', ['A'], [{ label: 'Actual', data: [1] }, { data: [4] }]);
    expect(text).toBe('Radar. Actual: A 1. A 4');
  });

  it('avisa cuando no hay datos', () => {
    expect(describeChart('Vacío', [], [])).toBe('Vacío. Sin datos');
  });
});

// WCAG 1.4.3: texto normal >= 4.5:1. Los pares de las insignias y del texto de madurez se validan
// acá para que nadie los vuelva a "aclarar" y rompa el contraste sin que lo note.
function luminance(hex: string): number {
  const c = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
function contrast(a: string, b: string): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

describe('colores accesibles de insignias y madurez (WCAG 1.4.3)', () => {
  it('cada estado tiene par de insignia y todos llegan a 4.5:1 en claro y en oscuro', () => {
    for (const status of Object.keys(EVALUATION_STATUS_CONFIG)) {
      const c = STATUS_BADGE_COLORS[status];
      expect(c, status).toBeDefined();
      expect(contrast(c.fg, c.bg), `${status} claro`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c.fgDark, c.bgDark), `${status} oscuro`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('el texto de cada nivel de madurez llega a 4.5:1 sobre blanco (claro) y sobre slate-800 (oscuro)', () => {
    for (const [level, c] of Object.entries(MATURITY_TEXT_COLORS)) {
      expect(contrast(c.fg, '#ffffff'), `nivel ${level} claro`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(c.fgDark, '#1d293d'), `nivel ${level} oscuro`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('getStatusBadgeStyle / getMaturityTextStyle devuelven variables CSS y caen a un valor por defecto', () => {
    expect(getStatusBadgeStyle('APPROVED')).toEqual({
      '--badge-fg': STATUS_BADGE_COLORS.APPROVED.fg,
      '--badge-bg': STATUS_BADGE_COLORS.APPROVED.bg,
      '--badge-fg-dark': STATUS_BADGE_COLORS.APPROVED.fgDark,
      '--badge-bg-dark': STATUS_BADGE_COLORS.APPROVED.bgDark,
    });
    expect(getStatusBadgeStyle('NO_EXISTE')['--badge-fg']).toBe(STATUS_BADGE_COLORS.DRAFT.fg);
    expect(getMaturityTextStyle(3)['--maturity-fg']).toBe(MATURITY_TEXT_COLORS[3].fg);
    expect(getMaturityTextStyle(99)['--maturity-fg']).toBe(MATURITY_TEXT_COLORS[0].fg);
  });
});
