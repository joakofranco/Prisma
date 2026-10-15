import { describe, it, expect } from 'vitest';
import {
  loginSchema,
  changePasswordSchema,
  personNameSchema,
  emailSchema,
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
      expect(result.error.issues[0]?.message).toBe(
        'La contraseña debe tener al menos 8 caracteres',
      );
    }
  });
});

describe('personNameSchema', () => {
  const nombre = personNameSchema('nombre');

  it.each([
    'Ana',
    'María José',
    "O'Brien",
    'O’Brien',
    'Pérez-Gómez',
    'De la Cruz',
    'Carlos 4',
    'Carlos IV',
    'Jr.',
  ])('acepta "%s"', (value) => {
    expect(nombre.safeParse(value).success).toBe(true);
  });

  it.each(['12345', '4', '---', '@#$%', '1 2 3', 'Ana_Maria', 'Ana<script>'])(
    'rechaza "%s" (sin letras o con caracteres no permitidos)',
    (value) => {
      expect(nombre.safeParse(value).success).toBe(false);
    },
  );

  it('rechaza vacío, solo espacios, 1 caracter y más de 100', () => {
    expect(nombre.safeParse('').success).toBe(false);
    expect(nombre.safeParse('   ').success).toBe(false);
    expect(nombre.safeParse('A').success).toBe(false);
    expect(nombre.safeParse('A'.repeat(101)).success).toBe(false);
    expect(nombre.safeParse('A'.repeat(100)).success).toBe(true);
  });

  it('recorta espacios sobrantes', () => {
    expect(nombre.parse('  Ana ')).toBe('Ana');
  });

  it('usa el rótulo correcto en el mensaje', () => {
    const result = personNameSchema('apellido').safeParse('123');
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toContain('El apellido solo puede contener letras');
    }
  });

  it('userSchema valida nombre y apellido por separado', () => {
    const base = { email: 'a@b.com', firstName: 'Ana', lastName: 'Pérez', roles: ['VIEWER'] };
    expect(userSchema.safeParse(base).success).toBe(true);
    const result = userSchema.safeParse({ ...base, firstName: '123', lastName: '456' });
    expect(result.success).toBe(false);
    if (!result.success) {
      const paths = result.error.issues.map((i) => i.path[0]);
      expect(paths).toContain('firstName');
      expect(paths).toContain('lastName');
    }
  });
});

describe('emailSchema', () => {
  it.each(['ana@prisma.local', 'ana.perez+tag@sub.dominio.com.uy'])('acepta "%s"', (value) => {
    expect(emailSchema.safeParse(value).success).toBe(true);
  });

  it.each(['sin-arroba', 'ana@', '@dominio.com', 'ana@dominio', 'ana@@dominio.com', 'a b@c.com'])(
    'rechaza "%s" con un mensaje específico',
    (value) => {
      const result = emailSchema.safeParse(value);
      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.issues[0]?.message).toBe(
          'El email no es válido (ej.: usuario@dominio.com)',
        );
      }
    },
  );

  it('rechaza vacío y demasiado largo, y recorta espacios', () => {
    expect(emailSchema.safeParse('').success).toBe(false);
    expect(emailSchema.safeParse('a'.repeat(250) + '@b.com').success).toBe(false);
    expect(emailSchema.parse('  ana@prisma.local ')).toBe('ana@prisma.local');
  });
});

describe('changePasswordSchema', () => {
  const valid = {
    currentPassword: 'ClaveVieja1!',
    newPassword: 'ClaveNueva1!',
    confirmPassword: 'ClaveNueva1!',
  };

  it('acepta una contraseña nueva distinta, confirmada y de 8+ caracteres', () => {
    expect(changePasswordSchema.safeParse(valid).success).toBe(true);
  });

  it('rechaza cuando la confirmación no coincide', () => {
    const result = changePasswordSchema.safeParse({ ...valid, confirmPassword: 'OtraClave1!' });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.path).toEqual(['confirmPassword']);
      expect(result.error.issues[0]?.message).toBe('Las contraseñas no coinciden');
    }
  });

  it('rechaza cuando la nueva es igual a la actual', () => {
    const result = changePasswordSchema.safeParse({
      currentPassword: 'MismaClave1!',
      newPassword: 'MismaClave1!',
      confirmPassword: 'MismaClave1!',
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.path).toEqual(['newPassword']);
      expect(result.error.issues[0]?.message).toBe(
        'La contraseña nueva debe ser distinta a la actual',
      );
    }
  });

  it('rechaza una contraseña nueva de menos de 8 caracteres', () => {
    const result = changePasswordSchema.safeParse({
      ...valid,
      newPassword: '1234567',
      confirmPassword: '1234567',
    });
    expect(result.success).toBe(false);
  });

  it('rechaza cuando falta la contraseña actual', () => {
    expect(changePasswordSchema.safeParse({ ...valid, currentPassword: '' }).success).toBe(false);
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
    const withoutOrg: Record<string, unknown> = { ...valid };
    delete withoutOrg.organizationId;
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
