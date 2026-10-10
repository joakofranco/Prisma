import { z } from 'zod';

// Email de usuario. Misma idea que el backend (CreateUserDto.email): formato válido, con punto en
// el dominio ("a@b" lo rechaza Keycloak), y como máximo 254 caracteres.
export const emailSchema = z
  .string()
  .trim()
  .min(1, 'El email es requerido')
  .max(254, 'El email no puede superar los 254 caracteres')
  .email('El email no es válido (ej.: usuario@dominio.com)')
  .regex(/^[^@\s]+@[^@\s]+\.[^@\s.]+$/, 'El email no es válido (ej.: usuario@dominio.com)');

export const loginSchema = z.object({
  email: z.string().email('Email inválido'),
  password: z.string().min(8, 'La contraseña debe tener al menos 8 caracteres'),
});

// Nombre / apellido de una persona. Misma regla que el backend (Dto.PERSON_NAME_REGEX): letras
// (con tildes, ñ...), dígitos, espacios, punto, apóstrofe y guion, pero con al menos UNA letra --
// "Carlos 4" y "O'Brien" pasan, "12345" y "---" no. Cada campo se valida por separado.
export const PERSON_NAME_REGEX = /^(?=.*\p{L})[\p{L}\p{Nd} .'’-]+$/u;

export function personNameSchema(label: 'nombre' | 'apellido') {
  return z
    .string()
    .trim()
    .min(1, `El ${label} es requerido`)
    .min(2, `El ${label} debe tener entre 2 y 100 caracteres`)
    .max(100, `El ${label} debe tener entre 2 y 100 caracteres`)
    .regex(
      PERSON_NAME_REGEX,
      `El ${label} solo puede contener letras, números, espacios, puntos, guiones y apóstrofes, y debe incluir al menos una letra`,
    );
}

// Cambio de contraseña desde "Mi Cuenta" (AccountView). Los `path` de cada refine son los campos
// del formulario a los que se les muestra el error.
export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Ingresá tu contraseña actual'),
    newPassword: z.string().min(8, 'La contraseña nueva debe tener al menos 8 caracteres'),
    confirmPassword: z.string(),
  })
  .refine((d) => d.newPassword === d.confirmPassword, {
    message: 'Las contraseñas no coinciden',
    path: ['confirmPassword'],
  })
  .refine((d) => !d.currentPassword || d.newPassword !== d.currentPassword, {
    message: 'La contraseña nueva debe ser distinta a la actual',
    path: ['newPassword'],
  });

export const organizationSchema = z.object({
  name: z.string().min(2, 'El nombre debe tener al menos 2 caracteres'),
  // Opcional: no toda organización tiene el RUT cargado al momento del alta (ver
  // apps/backend-core Organization.rut).
  rut: z.string().min(8, 'RUT inválido').optional().or(z.literal('')),
  sector: z.string().min(1, 'Seleccione un sector'),
  size: z.string().min(1, 'Seleccione un tamaño'),
});

export const userSchema = z.object({
  email: emailSchema,
  firstName: personNameSchema('nombre'),
  lastName: personNameSchema('apellido'),
  roles: z.array(z.string()).min(1, 'Seleccione al menos un rol'),
  organizationId: z.string().optional(),
});

export const evaluationSchema = z.object({
  name: z.string().min(3, 'El nombre debe tener al menos 3 caracteres'),
  organizationId: z.string().min(1, 'Seleccione una organización'),
  catalogVersion: z.string().min(1, 'Seleccione la versión del catálogo'),
});

export const evidenceSchema = z.object({
  controlId: z.string().min(1, 'Seleccione un control'),
  description: z.string().optional(),
  file: z.instanceof(File, { message: 'Seleccione un archivo' }),
});

export const improvementPlanSchema = z.object({
  controlId: z.string().min(1, 'Seleccione un control'),
  action: z.string().min(5, 'La acción debe tener al menos 5 caracteres'),
  responsible: z.string().min(1, 'Asigne un responsable'),
  priority: z.enum(['HIGH', 'MEDIUM', 'LOW']),
  dueDate: z.string().min(1, 'Seleccione una fecha'),
});

export type LoginFormData = z.infer<typeof loginSchema>;
export type OrganizationFormData = z.infer<typeof organizationSchema>;
export type UserFormData = z.infer<typeof userSchema>;
export type EvaluationFormData = z.infer<typeof evaluationSchema>;
export type EvidenceFormData = z.infer<typeof evidenceSchema>;
export type ImprovementPlanFormData = z.infer<typeof improvementPlanSchema>;
