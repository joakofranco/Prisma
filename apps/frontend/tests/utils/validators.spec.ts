import { describe, it, expect } from 'vitest';
import {
  loginSchema,
  organizationSchema,
  userSchema,
  evaluationSchema,
  evidenceSchema,
  improvementPlanSchema,
} from '../../src/utils/validators';

describe('loginSchema', () => {
  it('acepta un email y contraseña válidos', () => {
    const result = loginSchema.safeParse({ email: 'admin@prisma.local', password: 'Admin1234!' });
    expect(result.success).toBe(true);
  });

  it('rechaza un email mal formado', () => {
    const result = loginSchema.safeParse({ email: 'no-es-un-email', password: 'Admin1234!' });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe('Email inválido');
    }
  });

  it('rechaza una contraseña de menos de 8 caracteres', () => {
    const result = loginSchema.safeParse({ email: 'admin@prisma.local', password: '1234567' });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe('La contraseña debe tener al menos 8 caracteres');
    }
  });
});

describe('organizationSchema', () => {
  const valid = { name: 'QA Test Org', rut: '123456789', sector: 'Tecnología', size: 'Mediana' };

  it('acepta una organización completa', () => {
    expect(organizationSchema.safeParse(valid).success).toBe(true);
  });

  it('acepta una organización sin RUT (opcional)', () => {
    const withoutRut = { name: valid.name, sector: valid.sector, size: valid.size };
    expect(organizationSchema.safeParse(withoutRut).success).toBe(true);
    expect(organizationSchema.safeParse({ ...valid, rut: '' }).success).toBe(true);
  });

  it('rechaza un nombre de un solo caracter', () => {
    const result = organizationSchema.safeParse({ ...valid, name: 'X' });
    expect(result.success).toBe(false);
  });

  it('rechaza un RUT demasiado corto cuando se lo carga', () => {
    const result = organizationSchema.safeParse({ ...valid, rut: '123' });
    expect(result.success).toBe(false);
  });

  it('rechaza sector y tamaño vacíos', () => {
    const result = organizationSchema.safeParse({ ...valid, sector: '', size: '' });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues.length).toBeGreaterThanOrEqual(2);
    }
  });
});

describe('userSchema', () => {
  const valid = {
    email: 'evaluator@qa.test',
    firstName: 'Eval',
    lastName: 'Uno',
    roles: ['INTERNAL_EVALUATOR'],
  };

  it('acepta un usuario con al menos un rol', () => {
    expect(userSchema.safeParse(valid).success).toBe(true);
  });

  it('rechaza un usuario sin roles', () => {
    const result = userSchema.safeParse({ ...valid, roles: [] });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe('Seleccione al menos un rol');
    }
  });

  it('organizationId es opcional (p.ej. un AUDITOR sin tenant propio)', () => {
    const { organizationId: _unused, ...withoutOrg } = valid as typeof valid & {
      organizationId?: string;
    };
    expect(userSchema.safeParse(withoutOrg).success).toBe(true);
  });
});

describe('evaluationSchema', () => {
  it('acepta una evaluación con nombre, organización y versión de catálogo', () => {
    const result = evaluationSchema.safeParse({
      name: 'Autoevaluación 2026',
      organizationId: '868b6d63-af57-44dd-9ae3-c79ba443658e',
      catalogVersion: '5.0',
    });
    expect(result.success).toBe(true);
  });

  it('rechaza un nombre de menos de 3 caracteres', () => {
    const result = evaluationSchema.safeParse({
      name: 'Ab',
      organizationId: 'org-1',
      catalogVersion: '5.0',
    });
    expect(result.success).toBe(false);
  });
});

describe('evidenceSchema', () => {
  it('acepta un archivo real', () => {
    const file = new File(['contenido'], 'evidencia.pdf', { type: 'application/pdf' });
    const result = evidenceSchema.safeParse({ controlId: 'control-1', file });
    expect(result.success).toBe(true);
  });

  it('rechaza cuando no se seleccionó ningún archivo', () => {
    const result = evidenceSchema.safeParse({ controlId: 'control-1', file: null });
    expect(result.success).toBe(false);
  });
});

describe('improvementPlanSchema', () => {
  const valid = {
    controlId: 'control-1',
    action: 'Implementar MFA en todos los sistemas críticos',
    responsible: 'IT',
    priority: 'HIGH' as const,
    dueDate: '2026-12-31',
  };

  it('acepta un plan con prioridad HIGH/MEDIUM/LOW', () => {
    expect(improvementPlanSchema.safeParse(valid).success).toBe(true);
    expect(improvementPlanSchema.safeParse({ ...valid, priority: 'MEDIUM' }).success).toBe(true);
    expect(improvementPlanSchema.safeParse({ ...valid, priority: 'LOW' }).success).toBe(true);
  });

  it('rechaza una prioridad en español (ver EJ. bug real evitado: "ALTA" en vez de "HIGH")', () => {
    const result = improvementPlanSchema.safeParse({ ...valid, priority: 'ALTA' });
    expect(result.success).toBe(false);
  });

  it('rechaza una acción demasiado corta', () => {
    const result = improvementPlanSchema.safeParse({ ...valid, action: 'MFA' });
    expect(result.success).toBe(false);
  });
});
