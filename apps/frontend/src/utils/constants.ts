export const APP_NAME = 'PRISMA';
export const APP_DESCRIPTION =
  'Plataforma de Revisión Integral de Seguridad y Marcos de Auditorías';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081/api';
export const AI_API_BASE_URL =
  import.meta.env.VITE_AI_API_BASE_URL || 'http://localhost:8000/api/v1';
// El "/auth" es obligatorio: infra/keycloak (docker-compose.yml) fija
// KC_HTTP_RELATIVE_PATH=/auth, así que Keycloak nunca sirve nada en la raíz del host.
export const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180/auth';
export const KEYCLOAK_REALM = import.meta.env.VITE_KEYCLOAK_REALM || 'prisma';
export const KEYCLOAK_CLIENT_ID = import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'prisma-frontend';

// Escala acumulativa 0-4 del catálogo real MCU 5.0 (0 = ni siquiera el nivel 1 está completo).
export const MATURITY_LEVELS: Record<number, { label: string; color: string }> = {
  0: { label: 'Sin nivel', color: '#ef4444' },
  1: { label: 'Inicial', color: '#f97316' },
  2: { label: 'Repetible', color: '#eab308' },
  3: { label: 'Definido', color: '#84cc16' },
  4: { label: 'Gestionado', color: '#22c55e' },
};

export const EVALUATION_STATUS_CONFIG: Record<
  string,
  {
    label: string;
    color: string;
    bgColor: string;
    /** Qué significa este estado, en una frase — se muestra en el stepper y en su glosario. */
    description: string;
    /**
     * Rol que tiene la pelota en este estado (quién debe actuar para que avance). `null` en los
     * estados terminales/sin acción pendiente (APPROVED en reposo, ARCHIVED).
     */
    responsibleRole: string | null;
  }
> = {
  DRAFT: {
    label: 'Borrador',
    color: '#6b7280',
    bgColor: '#f3f4f6',
    description: 'Se creó la evaluación pero todavía no se respondió ningún control.',
    responsibleRole: 'ORG_RESPONSIBLE',
  },
  IN_PROGRESS: {
    label: 'En Curso',
    color: '#3b82f6',
    bgColor: '#dbeafe',
    description: 'Se están respondiendo los controles y cargando evidencias de respaldo.',
    responsibleRole: 'INTERNAL_EVALUATOR',
  },
  READY_FOR_AUDIT: {
    label: 'Lista para Auditoría',
    color: '#8b5cf6',
    bgColor: '#ede9fe',
    description: 'El responsable la envió a revisión; espera que un auditor la tome.',
    responsibleRole: 'AUDITOR',
  },
  IN_AUDIT: {
    label: 'En Auditoría',
    color: '#f59e0b',
    bgColor: '#fef3c7',
    description: 'Un auditor está revisando respuestas y evidencias, y registrando observaciones.',
    responsibleRole: 'AUDITOR',
  },
  APPROVED: {
    label: 'Aprobada',
    color: '#22c55e',
    bgColor: '#dcfce7',
    description:
      'El auditor validó el resultado. Ya está disponible el plan de mejora y los reportes.',
    responsibleRole: null,
  },
  RETURNED: {
    label: 'Devuelta',
    color: '#ef4444',
    bgColor: '#fee2e2',
    description: 'El auditor la devolvió con observaciones: hay que corregirlas y reenviarla.',
    responsibleRole: 'ORG_RESPONSIBLE',
  },
  ARCHIVED: {
    label: 'Archivada',
    color: '#6b7280',
    bgColor: '#f9fafb',
    description:
      'Evaluación cerrada, se conserva como referencia histórica y ya no admite cambios.',
    responsibleRole: null,
  },
};

// Camino principal del ciclo de vida, en orden — usado por el stepper para saber qué posición
// pintar como "completada" / "actual" / "pendiente". RETURNED no tiene posición propia: mientras
// dura, el trabajo pendiente es el mismo que en IN_PROGRESS (corregir y reenviar), así que el
// stepper la posiciona ahí y la señaliza aparte con una alerta (ver EvaluationLifecycleStepper).
export const EVALUATION_LIFECYCLE_STEPS = [
  'DRAFT',
  'IN_PROGRESS',
  'READY_FOR_AUDIT',
  'IN_AUDIT',
  'APPROVED',
  'ARCHIVED',
] as const;

export const ROLE_LABELS: Record<string, string> = {
  PRISMA_ADMIN: 'Administrador',
  ORG_RESPONSIBLE: 'Responsable de Organización',
  INTERNAL_EVALUATOR: 'Evaluador Interno',
  AUDITOR: 'Auditor',
  VIEWER: 'Visualizador',
};

export const ROLE_COLORS: Record<string, string> = {
  PRISMA_ADMIN: '#ef4444',
  ORG_RESPONSIBLE: '#3b82f6',
  INTERNAL_EVALUATOR: '#22c55e',
  AUDITOR: '#f59e0b',
  VIEWER: '#6b7280',
};

// Acciones que efectivamente registra AuditLogService.record en el backend -- ver los usos ahí
// (uy.edu.prisma.application.*). Una acción nueva que se agregue del lado del backend sigue
// viéndose igual en la bitácora (ActivityLogView cae al código crudo si no está acá, ver
// ACTION_LABELS abajo), sólo no tiene traducción hasta que se sume también en este mapa.
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  CREATE: 'Creación',
  UPDATE: 'Actualización',
  DELETE: 'Eliminación',
  UPDATE_STATUS: 'Cambio de Estado',
  UPDATE_PROFILE: 'Actualización de Perfil',
  CHANGE_PASSWORD: 'Cambio de Contraseña',
  SAVE_RESPONSE: 'Respuesta Guardada',
  LOGIN: 'Inicio de Sesión',
  LOGOUT: 'Cierre de Sesión',
  // A diferencia del resto, esto NO lo registra AuditLogService.record desde un request HTTP --
  // lo trae LoginFailureAuditSyncService desde los eventos LOGIN_ERROR que Keycloak ya audita
  // solo (backend-core nunca ve el POST del login en sí). Ver el comentario ahí.
  LOGIN_FAILED: 'Inicio de Sesión Fallido',
};

// Prefijo de tipo de uy.edu.prisma.application.AuditLogService.record -- el "resource" que
// registra siempre viene como "tipo:id" (p.ej. "user:38dab301-...") salvo los recursos singleton
// sin id propio (p.ej. "email-settings"). Ver ActivityLogView.vue#resourceLabel: sin esto la
// columna "Recurso" mostraba el string crudo, con el UUID completo, poco legible en una tabla.
export const AUDIT_RESOURCE_TYPE_LABELS: Record<string, string> = {
  user: 'Usuario',
  organization: 'Organización',
  evaluation: 'Evaluación',
  catalog_version: 'Versión de Catálogo',
  'email-settings': 'Configuración de Email',
};

export const PRIORITY_CONFIG: Record<string, { label: string; color: string }> = {
  HIGH: { label: 'Alta', color: '#ef4444' },
  MEDIUM: { label: 'Media', color: '#f59e0b' },
  LOW: { label: 'Baja', color: '#22c55e' },
};

// Nombre "lindo" de cada dominio de control = el prefijo del código del requisito (p.ej. "AD" en
// "AD.2-1"). Son las familias de requisitos del MCU. Si un prefijo no está acá, el selector de
// perfiles cae al prefijo crudo -- agregar la entrada acá alcanza para que se vea el nombre.
export const MCU_DOMAIN_LABELS: Record<string, string> = {
  PL: 'Planificación',
  GR: 'Gestión de Riesgos',
  CN: 'Cumplimiento Normativo',
  PD: 'Protección de Datos Personales',
  CO: 'Continuidad de las Operaciones',
  SO: 'Seguridad en las Operaciones',
  OR: 'Organización',
  PS: 'Política de Seguridad',
  GI: 'Gestión de Incidentes',
  GA: 'Gestión de Activos',
  GH: 'Gestión Humana',
  RP: 'Relación con Proveedores',
  AD: 'Adquisición y Desarrollo',
  CA: 'Control de Acceso',
  SC: 'Seguridad en las Comunicaciones',
  SF: 'Seguridad Física',
};

export const ITEMS_PER_PAGE = 10;
export const DATE_FORMAT = 'dd/MM/yyyy';
export const DATETIME_FORMAT = 'dd/MM/yyyy HH:mm';
