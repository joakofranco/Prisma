import { z } from 'zod';

export const loginSchema = z.object({
  email: z.string().email('Email inválido'),
  password: z.string().min(8, 'La contraseña debe tener al menos 8 caracteres'),
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
  email: z.string().email('Email inválido'),
  firstName: z.string().min(2, 'El nombre debe tener al menos 2 caracteres'),
  lastName: z.string().min(2, 'El apellido debe tener al menos 2 caracteres'),
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
