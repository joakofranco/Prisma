export type UserRole =
  'PRISMA_ADMIN' | 'ORG_RESPONSIBLE' | 'INTERNAL_EVALUATOR' | 'AUDITOR' | 'VIEWER';

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  roles: UserRole[];
  tenantId: string;
  /** Nombre de la organización (undefined/null si no tiene tenant, p.ej. PRISMA_ADMIN). */
  organizationName?: string | null;
  /** Organizaciones que puede auditar -- solo aplica si roles incluye AUDITOR. */
  auditedOrganizationIds?: string[];
  enabled: boolean;
  createdAt: string;
  /** false = todavía no tiene cuenta en Keycloak y por lo tanto no puede iniciar sesión. */
  canLogin: boolean;
}

export interface Organization {
  id: string;
  name: string;
  /** Uruguay usa "RUT" (Colombia y otros usan "NIT") -- opcional: no toda organización lo tiene
   * cargado al momento del alta. */
  rut?: string | null;
  sector: string;
  size: string;
  responsibleId?: string;
  enabled: boolean;
  createdAt: string;
}

/** Bitácora de acciones del sistema (append-only). Distinta de AuditObservation (hallazgos de
 * una evaluación MCU) -- ver el comentario en AuditLogDto del backend. */
export interface AuditLog {
  id: string;
  userId?: string | null;
  /** null = el usuario que hizo la acción ya no existe, o la acción no tuvo uno detrás. */
  userEmail?: string | null;
  userFullName?: string | null;
  tenantId?: string | null;
  /** null = la organización ya no existe, o la acción no tiene tenant (p.ej. un PRISMA_ADMIN
   * operando sobre algo global). */
  tenantName?: string | null;
  action: string;
  /** "tipo:id" crudo (p.ej. "user:38dab301-..."), tal cual lo graba el backend. */
  resource: string;
  /** Nombre real de la entidad afectada (p.ej. "Jane Doe" para un "user:<id>") -- null si el
   * tipo no tiene entidad propia (p.ej. "email-settings") o si ya no existe. */
  resourceName?: string | null;
  payload?: string | null;
  ipAddress?: string | null;
  userAgent?: string | null;
  createdAt: string;
}

/** Nivel dentro de un Requisito del catálogo real MCU 5.0 (1 = más básico, 4 = más avanzado).
 * La madurez de una Subcategoría se alcanza de forma acumulativa: nivel N cuando TODOS sus
 * controles de nivel <= N están marcados como cumplidos (ver EvaluationService en el backend). */
export type MaturityLevel = 1 | 2 | 3 | 4;

export interface MaturityCatalog {
  id: string;
  version: string;
  functions: MaturityFunction[];
}

export interface MaturityFunction {
  id: string;
  name: string;
  description: string;
  categories: MaturityCategory[];
}

export interface MaturityCategory {
  id: string;
  name: string;
  description: string;
  subcategories: MaturitySubcategory[];
}

export interface MaturitySubcategory {
  id: string;
  name: string;
  description: string;
  requirements: MaturityRequirement[];
}

export interface MaturityRequirement {
  id: string;
  code: string;
  description: string;
  controls: MaturityControl[];
}

export interface MaturityControl {
  id: string;
  code: string;
  description: string;
  targetLevel: MaturityLevel;
}

export type EvaluationStatus =
  'DRAFT' | 'IN_PROGRESS' | 'READY_FOR_AUDIT' | 'IN_AUDIT' | 'APPROVED' | 'RETURNED' | 'ARCHIVED';

export interface Evaluation {
  id: string;
  name: string;
  organizationId: string;
  organizationName?: string;
  catalogVersion: string;
  /** undefined/null = evaluación sobre el catálogo completo (sin perfil comunitario). */
  communityProfileId?: string;
  communityProfileName?: string;
  status: EvaluationStatus;
  /** 0/undefined = ningún nivel alcanzado todavía. */
  globalMaturity?: number;
  createdBy: string;
  /** Nombre de quien creó/evalúa (undefined si createdBy no se pudo resolver). */
  createdByName?: string | null;
  /** Auditores con esta organización asignada (no necesariamente quien ya la auditó). */
  assignedAuditorNames?: string[];
  createdAt: string;
  updatedAt: string;
}

/** Subconjunto curado de controles del catálogo para un sector/comunidad (p.ej. "PYME"). */
export interface CommunityProfileSummary {
  id: string;
  name: string;
  description?: string;
  catalogVersion: string;
  controlCount: number;
}

export interface CommunityProfile {
  id: string;
  name: string;
  description?: string;
  catalogVersion: string;
  controlIds: string[];
  createdAt: string;
}

export interface EvaluationResponse {
  id: string;
  evaluationId: string;
  controlId: string;
  /** ¿Se cumple este control puntual? Cada control es un ítem de checklist, no una
   * autoevaluación 1-5 -- ver MaturityLevel. */
  compliant: boolean;
  observations?: string;
  respondedBy: string;
  respondedAt: string;
}

export interface MaturityResult {
  functionId: string;
  functionName: string;
  categoryId: string;
  categoryName: string;
  subcategoryId: string;
  subcategoryName: string;
  /** 0-4: nivel acumulativo alcanzado (0 = ni siquiera el nivel 1 está completo). */
  currentLevel: number;
  targetLevel: MaturityLevel;
  gap: number;
}

export interface Evidence {
  id: string;
  evaluationId: string;
  controlId?: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  uploadedBy: string;
  uploadedAt: string;
  description?: string;
  url?: string;
  /** true si el asistente de IA pudo extraer e indexar el contenido para búsqueda de citas. */
  aiIndexed: boolean;
}

/**
 * Fragmento de evidencia recuperado por el asistente RAG para un control. Es sólo un puntero a
 * dónde mirar (archivo/ubicación/texto) — nunca un veredicto: el auditor valida manualmente.
 */
export interface EvidenceCitation {
  evidenceId: string | null;
  fileName: string | null;
  location: string | null;
  snippet: string;
  score: number;
}

export interface AuditObservation {
  id: string;
  evaluationId: string;
  controlId?: string;
  type: 'OBSERVATION' | 'NON_CONFORMITY' | 'RECOMMENDATION';
  description: string;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
  createdBy: string;
  createdAt: string;
  /** Nombre de quien la registró -- la "estampa" de qué auditor trabajó esta auditoría. */
  createdByName?: string | null;
}

export interface ImprovementPlan {
  id: string;
  evaluationId: string;
  controlId: string;
  action: string;
  responsible: string;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'OVERDUE';
  dueDate: string;
  createdAt: string;
}

// Sugerencia generada por el motor de reglas (RF-PLN-01). Todavía no es un ImprovementPlan
// persistido: el usuario la revisa/edita y recién ahí se crea vía improvementService.create().
export interface SuggestedImprovement {
  controlId: string;
  subcategoryId: string;
  functionName: string;
  categoryName: string;
  subcategoryName: string;
  controlCode: string;
  action: string;
  currentLevel: number;
  targetLevel: number;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  suggestedDueDate: string;
}

/** Guía práctica automática (LLM) para cerrar una brecha puntual -- pedida on-demand. */
export interface RemediationTips {
  summary: string;
  tips: string[];
}

export interface DashboardStats {
  totalEvaluations: number;
  activeOrganizations: number;
  avgMaturityLevel: number;
  pendingImprovements: number;
  evaluationsByStatus: Record<EvaluationStatus, number>;
  maturityByFunction: { name: string; level: number }[];
}

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ApiError {
  message: string;
  code: string;
  details?: Record<string, string[]>;
}

/** Configuración SMTP del realm de Keycloak (envía el email de "¿Olvidaste tu contraseña?").
 * El backend nunca devuelve la contraseña guardada -- `configured` indica si ya hay algo
 * guardado (host+from), no si las credenciales son correctas. */
export interface EmailSettings {
  host: string;
  port: number | null;
  from: string;
  fromDisplayName: string | null;
  authEnabled: boolean;
  username: string | null;
  starttls: boolean;
  ssl: boolean;
  configured: boolean;
}

/** Body de actualización: `password` es obligatorio si `authEnabled` es true (Keycloak nunca
 * devuelve el valor guardado, así que hay que reingresarlo en cada guardado). */
export interface UpdateEmailSettings {
  host: string;
  port: number;
  from: string;
  fromDisplayName?: string | null;
  authEnabled: boolean;
  username?: string | null;
  password?: string | null;
  starttls: boolean;
  ssl: boolean;
}

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

/**
 * Árbol editable para dar de alta una versión de catálogo (POST /api/catalog/import). `id` no
 * existe todavía (se genera al guardar) -- se usa solo del lado del cliente como key de Vue para
 * las filas del editor, nunca se manda al backend.
 */
export interface CatalogImportControl {
  clientId: string;
  code: string;
  description: string;
  targetLevel: MaturityLevel;
}

export interface CatalogImportRequirement {
  clientId: string;
  code: string;
  description: string;
  controls: CatalogImportControl[];
}

export interface CatalogImportSubcategory {
  clientId: string;
  code: string;
  name: string;
  description: string;
  requirements: CatalogImportRequirement[];
}

export interface CatalogImportCategory {
  clientId: string;
  code: string;
  name: string;
  description: string;
  subcategories: CatalogImportSubcategory[];
}

export interface CatalogImportFunction {
  clientId: string;
  code: string;
  name: string;
  description: string;
  categories: CatalogImportCategory[];
}

export interface CatalogImportPayload {
  version: string;
  label: string;
  functions: {
    code: string;
    name: string;
    description: string;
    categories: {
      code: string;
      name: string;
      description: string;
      subcategories: {
        code: string;
        name: string;
        description: string;
        requirements: {
          code: string;
          description: string;
          controls: { code: string; description: string; targetLevel: number }[];
        }[];
      }[];
    }[];
  }[];
}
