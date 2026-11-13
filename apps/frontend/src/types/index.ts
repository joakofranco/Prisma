export type UserRole =
  'PRISMA_ADMIN' | 'ORG_RESPONSIBLE' | 'INTERNAL_EVALUATOR' | 'AUDITOR' | 'VIEWER';

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  roles: UserRole[];
  /** Organizaciones a las que accede el usuario (membresía M:N). ORG_RESPONSIBLE y VIEWER tienen
   * exactamente una, AUDITOR al menos una, INTERNAL_EVALUATOR una o varias; sin ninguna, un
   * evaluador es "general" (accede a todas) y un PRISMA_ADMIN es global. */
  organizationIds: string[];
  /** Nombres de esas organizaciones, ordenados. */
  organizationNames: string[];
  enabled: boolean;
  createdAt: string;
  /** false = todavía no tiene cuenta en Keycloak y por lo tanto no puede iniciar sesión. */
  canLogin: boolean;
  /** RF-USR-05 (PRI-240): última invitación por correo enviada; null si se dio de alta con
   * contraseña temporal. */
  invitedAt?: string | null;
  /** Primer ingreso del invitado. Con invitedAt y sin esto, la invitación está pendiente. */
  invitationAcceptedAt?: string | null;
  /** RF-AUTH-08: estado real de la cuenta en Keycloak. true = bloqueada ahora por demasiados
   * intentos fallidos; false = no lo está; null/ausente = no se sabe (sin cuenta o Keycloak no
   * respondió) y la pantalla no muestra nada. */
  locked?: boolean | null;
  /** Hasta cuándo dura el bloqueo (solo si `locked`). */
  lockedUntil?: string | null;
}

/** Alta por invitación: el rol es siempre Evaluador interno y lo fija el backend. */
export interface InviteUserPayload {
  email: string;
  firstName: string;
  lastName: string;
  /** Solo lo elige PRISMA_ADMIN; para un responsable el backend usa su propia organización. */
  organizationId?: string;
}

export interface Organization {
  id: string;
  name: string;
  /** Identificador de la organización (RUT u otro) -- opcional: no toda organización lo tiene
   * cargado al momento del alta. Si `identifierIsRut`, el servidor lo validó y lo guardó
   * normalizado (`AA-BBBBBB-001-D`). */
  identifier?: string | null;
  identifierIsRut: boolean;
  sector: string;
  size: string;
  responsibleId?: string;
  /** Nombre (o email) del responsable; null si no tiene (PRI-367, lo muestra Reportes). */
  responsibleName?: string | null;
  enabled: boolean;
  createdAt: string;
}

/** Respuesta de reactivar una organización: `disabledUsers` = usuarios suyos que siguen
 * deshabilitados (la reactivación no los rehabilita, el admin lo hace desde Usuarios). */
export interface OrganizationReactivation {
  organization: Organization;
  disabledUsers: number;
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

// RF-AUD-10: historial de ciclos de auditoría de UNA evaluación puntual (envíos y devoluciones),
// a diferencia de AuditLog (bitácora global, solo para PRISMA_ADMIN/ORG_RESPONSIBLE) -- este lo
// puede consultar cualquiera con acceso a la organización de la evaluación, en particular AUDITOR.
export interface EvaluationAuditCycleEvent {
  id: string;
  action: string;
  /** Estado al que pasó la evaluación en este evento; null para eventos que no son de cambio de
   * estado (p.ej. "CERTIFY"). */
  status: string | null;
  performedBy?: string | null;
  performedByName?: string | null;
  occurredAt: string;
  /** 0 = autoevaluación inicial, antes del primer envío a auditoría. Se incrementa cada vez que
   * la evaluación vuelve a READY_FOR_AUDIT -- un reenvío tras ser devuelta es un ciclo nuevo. */
  cycleNumber: number;
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
  /** Sigla del catálogo (p. ej. "GV"). El backend la manda en GET /catalog/{version}. */
  code?: string;
  name: string;
  description: string;
  categories: MaturityCategory[];
}

export interface MaturityCategory {
  id: string;
  /** Sigla del catálogo (p. ej. "GV.OC"). */
  code?: string;
  name: string;
  description: string;
  subcategories: MaturitySubcategory[];
}

export interface MaturitySubcategory {
  id: string;
  /** Sigla del catálogo (p. ej. "GV.OC-01"). */
  code?: string;
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

/** Vista plana de un control del catálogo (GET /catalog/{version}/controls). La usa el selector
 * de controles de los perfiles comunitarios: agrupa por `domain` (prefijo del código del
 * requisito, p.ej. "AD" en "AD.2-1"). Un control aparece una sola vez. */
export interface CatalogControlFlat {
  id: string;
  code: string;
  description: string;
  targetLevel: MaturityLevel;
  requirementCode: string;
  requirementDescription: string;
  domain: string;
}

export type OverviewPriority = 'ALTA' | 'MEDIA' | 'BAJA' | null;

export interface OverviewRequirement {
  id: string;
  code: string;
  description: string;
}

export interface OverviewControl {
  id: string;
  code: string;
  description: string;
  targetLevel: MaturityLevel;
  requirementCode: string;
}

export interface OverviewSubcategory {
  id: string;
  code: string;
  name: string;
  description: string | null;
  priority: OverviewPriority;
  requirements: OverviewRequirement[];
  controls: OverviewControl[];
}

export interface OverviewCategory {
  id: string;
  code: string;
  name: string;
  subcategories: OverviewSubcategory[];
}

export interface OverviewFunction {
  id: string;
  code: string;
  name: string;
  categories: OverviewCategory[];
}

export interface OverviewPriorityCounts {
  alta: number;
  media: number;
  baja: number;
  sinPrioridad: number;
}

export interface EvaluationOverview {
  evaluationId: string;
  evaluationName: string;
  catalogVersion: string;
  catalogLabel: string;
  communityProfileId: string | null;
  communityProfileName: string | null;
  subcategoryCount: number;
  requirementCount: number;
  controlCount: number;
  priorityCounts: OverviewPriorityCounts;
  functions: OverviewFunction[];
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
  // RF-AUD-09: sello de certificación, completado solo una vez que status === 'APPROVED'.
  approvedBy?: string | null;
  approvedAt?: string | null;
  /** Firma HMAC-SHA256 (hex) de los metadatos de cierre -- ver EvaluationService.sealApproval. */
  certificationSeal?: string | null;
  approvedByName?: string | null;
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

/** Comentario del evaluador sobre una subcategoría completa (RF-EVA-06). */
export interface SubcategoryComment {
  subcategoryId: string;
  comment: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
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
  nextLevel: number | null;
  missingControls: MissingControl[];
}

export interface MissingControl {
  id: string;
  code: string;
  description: string;
  targetLevel: number;
  status: 'NON_COMPLIANT' | 'UNANSWERED';
}

/** Reglas de carga de evidencias que define el backend (RF-EVI-02). */
export interface EvidenceRules {
  /** Extensiones permitidas, en minúscula y sin punto. */
  allowedExtensions: string[];
  /** Tamaño máximo de una evidencia, en bytes (RF-EVI-04). */
  maxSizeBytes: number;
}

export interface Evidence {
  id: string;
  evaluationId: string;
  controlId?: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  /** Hash SHA-256 del contenido del archivo, calculado por el backend al subirlo (RF-EVI-05). */
  fileHash?: string;
  uploadedBy: string;
  uploadedAt: string;
  description?: string;
  /** true si el asistente de IA pudo extraer e indexar el contenido para búsqueda de citas. */
  aiIndexed: boolean;
  /** Resultado del antivirus (RNF-09): CLEAN, QUARANTINED (a la espera del análisis) o INFECTED. */
  scanStatus?: 'CLEAN' | 'QUARANTINED' | 'INFECTED';
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

/** Decisión del auditor sobre una cita de la IA (PRI-359): alimenta la medición de la búsqueda. */
export interface CitationFeedbackItem {
  controlId: string;
  fileName: string | null;
  location: string | null;
  snippet: string;
  score: number | null;
  decision: 'ACCEPTED' | 'REJECTED';
}

export interface AuditObservation {
  id: string;
  evaluationId: string;
  controlId?: string;
  /** Subcategoría del catálogo a la que está atada la observación (RF-AUD-05), si corresponde. */
  subcategoryId?: string;
  type: 'OBSERVATION' | 'NON_CONFORMITY' | 'RECOMMENDATION';
  description: string;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
  createdBy: string;
  createdAt: string;
  /** Nombre de quien la registró -- la "estampa" de qué auditor trabajó esta auditoría. */
  createdByName?: string | null;
  /** Código del control y de la subcategoría a los que está atada (PRI-354), si corresponde. */
  controlCode?: string | null;
  subcategoryCode?: string | null;
  /** Evidencia citada de la que sale la observación (RF-EVI-15) y su nombre de archivo. */
  evidenceId?: string | null;
  evidenceFileName?: string | null;
}

/** Marca «para revisar» de un control dentro de una evaluación (PRI-352). */
export interface ControlFlag {
  controlId: string;
  flaggedBy: string | null;
  flaggedByName: string | null;
  flaggedAt: string | null;
}

/** Dictamen del auditor sobre un control (RF-AUD-06). */
export type AuditVerdict = 'VALIDATED' | 'NOT_VALIDATED' | 'NEEDS_MORE_EVIDENCE';

export interface ControlVerdict {
  controlId: string;
  verdict: AuditVerdict;
  setBy: string | null;
  setByName: string | null;
  updatedAt: string | null;
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
  /** Comentarios libres del ítem (RF-PLN-02), hasta 2000 caracteres. */
  comments?: string | null;
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
  /** Todas las subcategorías donde el control cuenta (la primera es subcategoryName). */
  subcategoryNames: string[];
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
  /** false = texto de reemplazo porque la IA falló: no se ofrece para usar ni se registra. */
  generated?: boolean;
}

/** Qué se hizo con una guía de IA (PRI-370): alimenta el dataset de fine-tuning. */
export interface AiSuggestionFeedback {
  controlId: string;
  currentLevel: number;
  targetLevel: number;
  originalSummary: string;
  originalTips: string[];
  outcome: 'ACCEPTED_UNEDITED' | 'ACCEPTED_EDITED' | 'REJECTED';
  editedText?: string;
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

/** RF-PLN-03: plazos por defecto (en meses) con los que se sugiere la fecha límite de una acción
 * del plan de mejora según su prioridad. Los configura PRISMA_ADMIN (1–60, Alta ≤ Media ≤ Baja). */
export interface PlanDeadlines {
  highMonths: number;
  mediumMonths: number;
  lowMonths: number;
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
