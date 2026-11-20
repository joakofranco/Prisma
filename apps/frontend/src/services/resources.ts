import apiClient from './api';
import type {
  User,
  Organization,
  Evaluation,
  EvaluationOverview,
  EvaluationResponse,
  EvaluationAuditCycleEvent,
  SubcategoryComment,
  ControlVerdict,
  ControlFlag,
  AuditVerdict,
  Evidence,
  EvidenceCitation,
  CitationFeedbackItem,
  CitationFeedbackResult,
  EvidenceRules,
  AuditObservation,
  AuditLog,
  ImprovementPlan,
  SuggestedImprovement,
  RemediationTips,
  AiJob,
  AiJobAccepted,
  GuideBatchAccepted,
  EvidenceSuggestion,
  CitationSource,
  AiSuggestionFeedback,
  MaturityResult,
  DashboardStats,
  PaginatedResponse,
  CommunityProfile,
  CommunityProfileSummary,
  EmailSettings,
  AlertSettings,
  UpdateAlertSettings,
  AlertTestResult,
  UpdateEmailSettings,
  PlanDeadlines,
  PlatformService,
  ChangePasswordPayload,
  CatalogImportPayload,
  CatalogControlFlat,
  ControlGuideItem,
  ControlGuideImportResult,
  OrganizationReactivation,
  InviteUserPayload,
  MaturityTrend,
  EvaluationProgress,
  ProgressGranularity,
  ComparisonCandidate,
  EvaluationComparison,
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
  /** Desbloquea la cuenta bloqueada por intentos fallidos de login (solo PRISMA_ADMIN, RF-AUTH-08). */
  unlock(id: string) {
    return apiClient.post<{ wasLocked: boolean }>(`/users/${id}/unlock`);
  },
  /** Invita a un evaluador interno por correo (RF-USR-05). */
  invite(data: InviteUserPayload) {
    return apiClient.post<User>('/users/invitations', data);
  },
  /** Reenvía la invitación de quien todavía no ingresó. */
  resendInvitation(id: string) {
    return apiClient.post<User>(`/users/${id}/invitation/resend`);
  },
  /** Si hay servidor de correo para invitar. */
  invitationAvailability() {
    return apiClient.get<{ emailConfigured: boolean }>('/users/invitations/availability');
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
  reactivate(id: string) {
    return apiClient.put<OrganizationReactivation>(`/organizations/${id}/enable`);
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
  // Ids (de los pedidos) de las evaluaciones que se pueden eliminar ahora (accesibles y sin datos).
  getDeletable(ids: string[]) {
    return apiClient.get<string[]>('/evaluations/deletable', { params: { ids: ids.join(',') } });
  },
  changeProfile(id: string, communityProfileId: string | null) {
    return apiClient.patch<Evaluation>(`/evaluations/${id}/profile`, { communityProfileId });
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
  getFlags(evaluationId: string) {
    return apiClient.get<ControlFlag[]>(`/evaluations/${evaluationId}/flags`);
  },
  /** Marca o desmarca «para revisar»; se guarda en el momento, no con «Guardar progreso». */
  setFlag(evaluationId: string, controlId: string, flagged: boolean) {
    const url = `/evaluations/${evaluationId}/flags/${controlId}`;
    return flagged ? apiClient.put<void>(url) : apiClient.delete<void>(url);
  },
  getSubcategoryComments(evaluationId: string) {
    return apiClient.get<SubcategoryComment[]>(`/evaluations/${evaluationId}/subcategory-comments`);
  },
  saveSubcategoryComment(evaluationId: string, subcategoryId: string, comment: string) {
    return apiClient.put<SubcategoryComment>(
      `/evaluations/${evaluationId}/subcategory-comments/${subcategoryId}`,
      { comment },
    );
  },
  getOverview(evaluationId: string) {
    return apiClient.get<EvaluationOverview>(`/evaluations/${evaluationId}/overview`);
  },
  getResults(evaluationId: string) {
    return apiClient.get<MaturityResult[]>(`/evaluations/${evaluationId}/results`);
  },
  calculateMaturity(evaluationId: string) {
    return apiClient.post(`/evaluations/${evaluationId}/calculate`);
  },
  // RF-AUD-10: ciclos de auditoría (envíos/devoluciones) de esta evaluación puntual.
  getAuditTrail(evaluationId: string) {
    return apiClient.get<EvaluationAuditCycleEvent[]>(`/evaluations/${evaluationId}/audit-trail`);
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
  /** Lista PLANA de controles de una versión (sin el árbol), para el selector de perfiles. */
  getControls(version: string) {
    return apiClient.get<CatalogControlFlat[]>(`/catalog/${version}/controls`);
  },
  importCatalog(data: CatalogImportPayload) {
    return apiClient.post<{ version: string; label: string }>('/catalog/import', data);
  },
  deleteVersion(version: string) {
    return apiClient.delete(`/catalog/${version}`);
  },
  getGuideItems(version: string) {
    return apiClient.get<ControlGuideItem[]>(`/catalog/${version}/guide/items`);
  },
  exportGuide(version: string) {
    return apiClient.get<Blob>(`/catalog/${version}/guide/export`, { responseType: 'blob' });
  },
  importGuide(version: string, file: File) {
    const data = new FormData();
    data.append('file', file);
    return apiClient.post<ControlGuideImportResult>(`/catalog/${version}/guide`, data, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  saveGuide(
    controlId: string,
    data: { interpretation: string; example: string; evidence: string },
  ) {
    return apiClient.put<ControlGuideItem>(`/catalog/controls/${controlId}/guide`, data);
  },
  deleteGuides(controlIds: string[]) {
    return apiClient.post<{ removed: number }>('/catalog/guide/delete', { controlIds });
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
  /** Reglas de carga vigentes: extensiones permitidas, sin punto (RF-EVI-02). */
  getAllowedFormats() {
    return apiClient.get<EvidenceRules>('/evidence/allowed-formats');
  },
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
  /** Descarga el archivo: el backend lo sirve con el JWT en cada petición (PRI-286). */
  download(id: string) {
    return apiClient.get<Blob>(`/evidence/${id}/download`, { responseType: 'blob' });
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
  /** Sugerencias de a qué controles podría pertenecer cada evidencia sin control (solo sugerencias). */
  getSuggestions(evaluationId: string) {
    return apiClient.get<EvidenceSuggestion[]>(`/evidence/evaluation/${evaluationId}/suggestions`);
  },
  /** Acepta o rechaza una sugerencia: NO asigna la evidencia; deja feedback anónimo. */
  decideSuggestion(suggestionId: string, decision: 'ACCEPTED' | 'REJECTED') {
    return apiClient.post<EvidenceSuggestion>(`/evidence/suggestions/${suggestionId}/decision`, {
      decision,
    });
  },
  /** Todas las citas ya guardadas de la evaluación (no busca): la pantalla las muestra al abrir. */
  getSavedCitations(evaluationId: string) {
    return apiClient.get<EvidenceCitation[]>(`/evidence/evaluation/${evaluationId}/citations`);
  },
  /** «Actualizar»: busca de nuevo y conserva las decisiones de los fragmentos que siguen. */
  refreshCitations(evaluationId: string, controlId: string) {
    return apiClient.post<EvidenceCitation[]>(
      `/evidence/evaluation/${evaluationId}/control/${controlId}/citations/refresh`,
    );
  },
  /**
   * El auditor acepta o rechaza las citas de la IA. Aceptar una cita valida el control (C4a): la
   * respuesta dice qué pasó con el dictamen. Si habría que pisar un dictamen puesto a mano, el
   * servidor responde 409 `VERDICT_OVERRIDE_REQUIRED` y hay que repetir con
   * `confirmVerdictOverride = true`.
   */
  sendCitationFeedback(
    evaluationId: string,
    items: CitationFeedbackItem[],
    confirmVerdictOverride = false,
  ) {
    return apiClient.post<CitationFeedbackResult>('/evidence/citations/feedback', {
      evaluationId,
      items,
      ...(confirmVerdictOverride ? { confirmVerdictOverride: true } : {}),
    });
  },
};

export const auditService = {
  getObservations(evaluationId: string) {
    return apiClient.get<AuditObservation[]>(`/audit/observations/${evaluationId}`);
  },
  createObservation(data: Partial<AuditObservation> & { citation?: CitationSource }) {
    return apiClient.post<AuditObservation>('/audit/observations', data);
  },
  updateObservation(id: string, data: Partial<AuditObservation>) {
    return apiClient.put<AuditObservation>(`/audit/observations/${id}`, data);
  },
  getVerdicts(evaluationId: string) {
    return apiClient.get<ControlVerdict[]>(`/audit/verdicts/${evaluationId}`);
  },
  /** «Deshacer» un dictamen que salió de aceptar una cita (uno fijado a mano responde 409). */
  undoCitationVerdict(evaluationId: string, controlId: string) {
    return apiClient.delete<void>(
      `/audit/verdicts/${evaluationId}/controls/${controlId}/citation-verdict`,
    );
  },
  setVerdict(evaluationId: string, controlId: string, verdict: AuditVerdict) {
    return apiClient.put<ControlVerdict>(`/audit/verdicts/${evaluationId}/controls/${controlId}`, {
      verdict,
    });
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
  exportCsv(params?: { search?: string; action?: string }) {
    return apiClient.get('/audit-logs/export', { params, responseType: 'blob' });
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
  /** Versión asíncrona de la guía: encola un trabajo (202) y se consulta con aiJobsService. El
   * código y la descripción del control los resuelve el servidor a partir de controlId. */
  requestTipsAsync(data: {
    controlId: string;
    evaluationId?: string;
    functionName?: string;
    categoryName?: string;
    subcategoryName?: string;
    currentLevel: number;
    targetLevel: number;
  }) {
    return apiClient.post<AiJobAccepted>('/improvement/suggestions/tips/async', data);
  },
  /**
   * Lote de guías (F3): una por control, en segundo plano y con prioridad de lote. Responde con el
   * lote y el trabajo de cada control; el avance se consulta con `aiJobsService.getMany`.
   */
  requestTipsBatch(
    items: {
      controlId: string;
      evaluationId?: string;
      functionName?: string;
      categoryName?: string;
      subcategoryName?: string;
      currentLevel: number;
      targetLevel: number;
    }[],
  ) {
    return apiClient.post<GuideBatchAccepted>('/improvement/suggestions/tips/batch', { items });
  },
  /** Registra qué se hizo con las guías de IA (usada, editada o descartada). */
  sendSuggestionFeedback(items: AiSuggestionFeedback[]) {
    return apiClient.post<void>('/improvement/suggestions/feedback', { items });
  },
  create(data: Partial<ImprovementPlan> & { guideId?: string; guideEdited?: boolean }) {
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
  /** Reporte ejecutivo gerencial (RF-REP-01): 1 a 2 páginas, solo con la evaluación aprobada. */
  generateExecutivePDF(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/executive/pdf`, { responseType: 'blob' });
  },
  /** Reporte técnico detallado (RF-REP-02): control por control, con veredictos, observaciones y
   * evidencias. Mismos estados que el reporte final (aprobada, o archivada que fue aprobada). */
  generateTechnicalPDF(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/technical/pdf`, { responseType: 'blob' });
  },
  generatePreliminaryPDF(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/preliminary/pdf`, { responseType: 'blob' });
  },
  generatePreliminaryExcel(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/preliminary/excel`, { responseType: 'blob' });
  },
  exportImprovementPlanPDF(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/improvement-plan/pdf`, {
      responseType: 'blob',
    });
  },
  exportImprovementPlanExcel(evaluationId: string) {
    return apiClient.get(`/reports/${evaluationId}/improvement-plan/excel`, {
      responseType: 'blob',
    });
  },
};

// RF-VIZ-06 (PRI-320): comparativo entre dos evaluaciones finalizadas de la misma organización.
export const comparisonService = {
  /** Evaluaciones finalizadas de la organización (sin organización: la del responsable). */
  candidates(organizationId?: string) {
    return apiClient.get<ComparisonCandidate[]>('/evaluations/compare/candidates', {
      params: organizationId ? { organizationId } : {},
    });
  },
  compare(a: string, b: string) {
    return apiClient.get<EvaluationComparison>('/evaluations/compare', { params: { a, b } });
  },
  downloadPdf(a: string, b: string) {
    return apiClient.get<Blob>('/evaluations/compare/pdf', {
      params: { a, b },
      responseType: 'blob',
    });
  },
};

export const dashboardService = {
  getStats(tenantId?: string) {
    return apiClient.get<DashboardStats>('/dashboard/stats', { params: { tenantId } });
  },
  /** RF-VIZ-07: tendencia de una organización. El responsable no la elige (es la suya). */
  getMaturityTrend(organizationId?: string) {
    return apiClient.get<MaturityTrend>('/dashboard/maturity-trend', {
      params: organizationId ? { organizationId } : {},
    });
  },
  /** Avance de una evaluación por día, semana o mes (sin escala: la elige el backend). */
  getEvaluationProgress(evaluationId: string, granularity?: ProgressGranularity) {
    return apiClient.get<EvaluationProgress>(`/dashboard/evaluations/${evaluationId}/progress`, {
      params: granularity ? { granularity } : {},
    });
  },
};

// RF-PLN-03: leerlos puede cualquier usuario autenticado (el alta de una acción precarga la fecha);
// cambiarlos, solo PRISMA_ADMIN.
export const planDeadlinesService = {
  get() {
    return apiClient.get<PlanDeadlines>('/settings/plan-deadlines');
  },
  update(data: PlanDeadlines) {
    return apiClient.put<PlanDeadlines>('/settings/plan-deadlines', data);
  },
};

export const platformServicesService = {
  getAll() {
    return apiClient.get<PlatformService[]>('/admin/services');
  },
  updateUrl(id: string, url: string) {
    return apiClient.put<PlatformService>(`/admin/services/${id}`, { url });
  },
  reset(id: string) {
    return apiClient.post<PlatformService>(`/admin/services/${id}/reset`);
  },
  replaceHost(host: string) {
    return apiClient.post<PlatformService[]>('/admin/services/replace-host', { host });
  },
};

export const alertSettingsService = {
  get() {
    return apiClient.get<AlertSettings>('/admin/alert-settings');
  },
  update(data: UpdateAlertSettings) {
    return apiClient.put<AlertSettings>('/admin/alert-settings', data);
  },
  test() {
    return apiClient.post<AlertTestResult>('/admin/alert-settings/test');
  },
};

export const emailSettingsService = {
  get() {
    return apiClient.get<EmailSettings>('/admin/email-settings');
  },
  update(data: UpdateEmailSettings) {
    return apiClient.put<EmailSettings>('/admin/email-settings', data);
  },
  test(recipientEmail: string) {
    return apiClient.post<void>('/admin/email-settings/test', { recipientEmail });
  },
};

/** Trabajos de IA asíncronos (cola): consultar, listar los propios y cancelar. */
export const aiJobsService = {
  get(id: string) {
    return apiClient.get<AiJob>(`/ai/jobs/${id}`);
  },
  listMine() {
    return apiClient.get<AiJob[]>('/ai/jobs', { params: { mine: true } });
  },
  /** Solo cancela si sigue en cola. */
  cancel(id: string) {
    return apiClient.delete<AiJob>(`/ai/jobs/${id}`);
  },
  /** Estado de varios trabajos de una vez (avance de un lote), con la guía de los que terminaron. */
  getMany(ids: string[]) {
    return apiClient.get<AiJob[]>(`/ai/jobs?ids=${ids.join(',')}`);
  },
  /** Cancela de un lote lo que sigue en cola; lo que ya corre termina. */
  cancelBatch(batchId: string) {
    return apiClient.delete<{ cancelled: number }>(`/ai/jobs/batches/${batchId}`);
  },
};
