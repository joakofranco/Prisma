import apiClient from './api';
import type {
  User,
  Organization,
  Evaluation,
  EvaluationResponse,
  Evidence,
  EvidenceCitation,
  AuditObservation,
  AuditLog,
  ImprovementPlan,
  SuggestedImprovement,
  RemediationTips,
  MaturityResult,
  DashboardStats,
  PaginatedResponse,
  CommunityProfile,
  CommunityProfileSummary,
  EmailSettings,
  UpdateEmailSettings,
  ChangePasswordPayload,
  CatalogImportPayload,
} from '@/types';

export const usersService = {
  getAll(params?: { page?: number; pageSize?: number; search?: string }) {
    return apiClient.get<PaginatedResponse<User>>('/users', { params });
  },
  getById(id: string) {
    return apiClient.get<User>(`/users/${id}`);
  },
  create(data: Partial<User> & { password?: string }) {
    return apiClient.post<User>('/users', data);
  },
  update(id: string, data: Partial<User>) {
    return apiClient.put<User>(`/users/${id}`, data);
  },
  delete(id: string) {
    return apiClient.delete(`/users/${id}`);
  },
};

export const organizationsService = {
  getAll(params?: { page?: number; pageSize?: number; search?: string }) {
    return apiClient.get<PaginatedResponse<Organization>>('/organizations', { params });
  },
  getById(id: string) {
    return apiClient.get<Organization>(`/organizations/${id}`);
  },
  create(data: Partial<Organization>) {
    return apiClient.post<Organization>('/organizations', data);
  },
  update(id: string, data: Partial<Organization>) {
    return apiClient.put<Organization>(`/organizations/${id}`, data);
  },
  delete(id: string) {
    return apiClient.delete(`/organizations/${id}`);
  },
};

export const evaluationsService = {
  getAll(params?: { page?: number; pageSize?: number; status?: string; organizationId?: string }) {
    return apiClient.get<PaginatedResponse<Evaluation>>('/evaluations', { params });
  },
  getById(id: string) {
    return apiClient.get<Evaluation>(`/evaluations/${id}`);
  },
  create(data: {
    name: string;
    organizationId: string;
    catalogVersion: string;
    communityProfileId?: string | null;
  }) {
    return apiClient.post<Evaluation>('/evaluations', data);
  },
  updateStatus(id: string, status: string) {
    return apiClient.patch<Evaluation>(`/evaluations/${id}/status`, { status });
  },
  delete(id: string) {
    return apiClient.delete(`/evaluations/${id}`);
  },
  getResponses(evaluationId: string) {
    return apiClient.get<EvaluationResponse[]>(`/evaluations/${evaluationId}/responses`);
  },
  saveResponse(evaluationId: string, data: Partial<EvaluationResponse>) {
    return apiClient.post<EvaluationResponse>(`/evaluations/${evaluationId}/responses`, data);
  },
  getResults(evaluationId: string) {
    return apiClient.get<MaturityResult[]>(`/evaluations/${evaluationId}/results`);
  },
  calculateMaturity(evaluationId: string) {
    return apiClient.post(`/evaluations/${evaluationId}/calculate`);
  },
};

export const catalogService = {
  getVersions() {
    return apiClient.get<{ versions: string[] }>('/catalog/versions');
  },
  /** profileId opcional: si se pasa, el árbol viene podado a solo los controles de ese perfil. */
  getByVersion(version: string, profileId?: string) {
    return apiClient.get(`/catalog/${version}`, { params: profileId ? { profileId } : undefined });
  },
  importCatalog(data: CatalogImportPayload) {
    return apiClient.post<{ version: string; label: string }>('/catalog/import', data);
  },
  deleteVersion(version: string) {
    return apiClient.delete(`/catalog/${version}`);
  },
};

export const accountService = {
  updateProfile(data: { firstName: string; lastName: string }) {
    return apiClient.put<void>('/account/profile', data);
  },
  changePassword(data: ChangePasswordPayload) {
    return apiClient.put<void>('/account/password', data);
  },
  // Keycloak maneja el login/logout en sí (ver services/auth.ts); esto es el aviso que manda el
  // store de auth para que quede una entrada en la bitácora (AuditLog) -- ver el comentario en
  // AccountService.recordSessionEvent del backend.
  recordSessionEvent(event: 'LOGIN' | 'LOGOUT') {
    return apiClient.post<void>('/account/session-event', { event });
  },
};

export const communityProfilesService = {
  getAll(catalogVersion: string) {
    return apiClient.get<CommunityProfileSummary[]>('/community-profiles', {
      params: { catalogVersion },
    });
  },
  getById(id: string) {
    return apiClient.get<CommunityProfile>(`/community-profiles/${id}`);
  },
  create(data: {
    name: string;
    description?: string;
    catalogVersion: string;
    controlIds: string[];
  }) {
    return apiClient.post<CommunityProfile>('/community-profiles', data);
  },
  update(
    id: string,
    data: { name: string; description?: string; catalogVersion: string; controlIds: string[] },
  ) {
    return apiClient.put<CommunityProfile>(`/community-profiles/${id}`, data);
  },
  delete(id: string) {
    return apiClient.delete(`/community-profiles/${id}`);
  },
};

export const evidenceService = {
  getByEvaluation(evaluationId: string) {
    return apiClient.get<Evidence[]>(`/evidence/evaluation/${evaluationId}`);
  },
  upload(data: FormData) {
    return apiClient.post<Evidence>('/evidence', data, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  delete(id: string) {
    return apiClient.delete(`/evidence/${id}`);
  },
  getSignedUrl(id: string) {
    return apiClient.get<{ url: string }>(`/evidence/${id}/download`);
  },
  /** Reintenta indexar la evidencia en el asistente de IA (p.ej. si falló al subirla). */
  reindex(id: string) {
    return apiClient.post<Evidence>(`/evidence/${id}/index`);
  },
  /** Citas de evidencia (de la organización de la evaluación) relevantes para un control. */
  getCitations(evaluationId: string, controlId: string) {
    return apiClient.get<EvidenceCitation[]>(
      `/evidence/evaluation/${evaluationId}/control/${controlId}/citations`,
    );
  },
};

export const auditService = {
  getObservations(evaluationId: string) {
    return apiClient.get<AuditObservation[]>(`/audit/observations/${evaluationId}`);
  },
  createObservation(data: Partial<AuditObservation>) {
    return apiClient.post<AuditObservation>('/audit/observations', data);
  },
  updateObservation(id: string, data: Partial<AuditObservation>) {
    return apiClient.put<AuditObservation>(`/audit/observations/${id}`, data);
  },
};

// Distinto de `auditService` (arriba): ese es la Auditoría MCU (AuditObservation, hallazgos de
// una evaluación); esto es la bitácora de acciones del sistema (quién hizo qué, cuándo). El
// backend ya acota la consulta al tenant del usuario para todo rol que no sea PRISMA_ADMIN (ver
// AuditLogService.list), así que acá no hace falta -- ni se puede -- pasar un tenantId.
export const auditLogsService = {
  getAll(params?: { page?: number; pageSize?: number; search?: string; action?: string }) {
    return apiClient.get<PaginatedResponse<AuditLog>>('/audit-logs', { params });
  },
};

export const improvementService = {
  getByEvaluation(evaluationId: string) {
    return apiClient.get<ImprovementPlan[]>(`/improvement/${evaluationId}`);
  },
  getSuggestions(evaluationId: string) {
    return apiClient.get<SuggestedImprovement[]>(`/improvement/${evaluationId}/suggestions`);
  },
  /** Guía práctica automática (LLM) para cerrar una brecha puntual -- se pide on-demand, una
   * sugerencia a la vez, nunca para toda la lista junta. */
  getRemediationTips(data: {
    controlCode: string;
    description: string;
    functionName?: string;
    categoryName?: string;
    subcategoryName?: string;
    currentLevel: number;
    targetLevel: number;
  }) {
    return apiClient.post<RemediationTips>('/improvement/suggestions/tips', data);
  },
  create(data: Partial<ImprovementPlan>) {
    return apiClient.post<ImprovementPlan>('/improvement', data);
  },
  update(id: string, data: Partial<ImprovementPlan>) {
    return apiClient.put<ImprovementPlan>(`/improvement/${id}`, data);
  },
};

export const reportsService = {
  generatePDF(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/pdf`, { responseType: 'blob' });
  },
  generateExcel(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/excel`, { responseType: 'blob' });
  },
};

export const dashboardService = {
  getStats(tenantId?: string) {
    return apiClient.get<DashboardStats>('/dashboard/stats', { params: { tenantId } });
  },
};

export const emailSettingsService = {
  get() {
    return apiClient.get<EmailSettings>('/admin/email-settings');
  },
  update(data: UpdateEmailSettings) {
    return apiClient.put<EmailSettings>('/admin/email-settings', data);
  },
};
