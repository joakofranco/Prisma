package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AiCitationFeedbackService;
import uy.edu.prisma.application.AiJobQueue;
import uy.edu.prisma.application.AiJobRunner;
import uy.edu.prisma.application.AuditVerdictService;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EvaluationStatusChangedEvent;
import uy.edu.prisma.application.EvidenceIndexService;
import uy.edu.prisma.application.EvidenceService;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackItemDto;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * Citas persistidas (C2) e indexación con estado (C7) contra un Postgres real (migración V43): lo
 * que importa lo hace el motor (índice único del fragmento, cascada al borrar la evidencia, CHECKs)
 * y la composición de transacciones, que los mocks no ven. El trabajador está apagado: la cola se
 * maneja a mano (claimNext + runner).
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {
      "prisma.ai.jobs.enabled=false",
      "prisma.ai.jobs.retry-backoff-seconds=0",
      "prisma.ai.jobs.max-attempts=2",
      "prisma.ai.jobs.concurrency=5"
    })
class CitationResultsIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("prisma_test")
          .withUsername("test")
          .withPassword("test")
          .withInitScript("db/init-test.sql");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
  }

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;
  @MockitoBean private AiEvidenceClient aiClient;

  @Autowired private EvidenceService evidenceService;
  @Autowired private EvidenceIndexService indexService;
  @Autowired private AiCitationFeedbackService feedbackService;
  @Autowired private AuditVerdictService verdictService;
  @Autowired private AiJobQueue queue;
  @Autowired private AiJobRunner runner;
  @Autowired private AiJobRepository jobRepo;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private EvidenceRepository evidenceRepo;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

  private UUID orgId;
  private UUID evaluationId;
  private UUID controlId;
  private UUID otherControlId;

  @BeforeEach
  void seed() {
    reset(aiClient);
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    jdbc.update("DELETE FROM prisma.evidence_citation_result");
    jdbc.update("DELETE FROM prisma.ai_citation_feedback");
    jdbc.update("DELETE FROM prisma.audit_control_verdicts");
    jdbc.update("DELETE FROM prisma.ai_job");

    orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name, identifier) VALUES (?, ?, ?)",
        orgId,
        "PRUEBA-hito3 " + orgId,
        null);
    Evaluation eval =
        evaluationRepo.save(
            Evaluation.builder()
                .name("PRUEBA-hito3")
                .organization(Organization.builder().id(orgId).build())
                .status(Evaluation.Status.IN_AUDIT)
                .build());
    evaluationId = eval.getId();
    List<UUID> controls =
        jdbc.queryForList("SELECT id FROM prisma.catalog_controls LIMIT 2", UUID.class);
    controlId = controls.get(0);
    otherControlId = controls.get(1);
  }

  private EvidenceCitationDto fresh(String fragmentId, double score, UUID evidenceId) {
    return new EvidenceCitationDto(
        evidenceId,
        "politica.pdf",
        "Página 1",
        "texto " + fragmentId,
        score,
        fragmentId,
        null,
        null,
        null,
        null,
        false);
  }

  private Evidence evidence(UUID control, String type) {
    return evidenceRepo.save(
        Evidence.builder()
            .evaluation(evaluationRepo.getReferenceById(evaluationId))
            .control(control == null ? null : controlRef(control))
            .fileName("politica.pdf")
            .fileType(type)
            .storageKey("evidence/" + UUID.randomUUID())
            .scanStatus(Evidence.ScanStatus.CLEAN)
            .build());
  }

  private uy.edu.prisma.domain.entity.CatalogControl controlRef(UUID id) {
    return uy.edu.prisma.domain.entity.CatalogControl.builder().id(id).build();
  }

  /** En producción enqueue() corre dentro de la transacción de la subida / del reintento. */
  private void enqueue(Evidence evidence) {
    new org.springframework.transaction.support.TransactionTemplate(txManager)
        .executeWithoutResult(
            status -> indexService.enqueue(evidenceRepo.findById(evidence.getId()).orElseThrow()));
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  // ===================================================================== citas guardadas

  @Test
  void theFirstQuerySearchesAndSavesTheSecondReturnsWhatIsSaved() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev), fresh("e::4", 0.6, ev)));

    List<EvidenceCitationDto> first = evidenceService.getCitations(evaluationId, controlId);
    List<EvidenceCitationDto> second = evidenceService.getCitations(evaluationId, controlId);

    assertEquals(2, first.size());
    assertEquals("PENDING", first.get(0).status());
    assertNotNull(first.get(0).citationId());
    assertEquals(first.get(0).citationId(), second.get(0).citationId());
    verify(aiClient, org.mockito.Mockito.times(1)).citations(any(), any(), any(), anyString());
    assertEquals(2, count("SELECT count(*) FROM prisma.evidence_citation_result"));
  }

  @Test
  void refreshingKeepsTheDecisionsOfTheFragmentsThatStillAppear() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev), fresh("e::4", 0.6, ev)));
    List<EvidenceCitationDto> first = evidenceService.getCitations(evaluationId, controlId);
    UUID acceptedId = first.get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(
                    controlId,
                    "politica.pdf",
                    "Página 1",
                    "texto e::0",
                    0.9,
                    "ACCEPTED",
                    ev,
                    acceptedId))));

    // La búsqueda nueva reordena, suma un fragmento y ya no trae e::4.
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::7", 0.95, ev), fresh("e::0", 0.8, ev)));
    List<EvidenceCitationDto> refreshed = evidenceService.refreshCitations(evaluationId, controlId);

    assertEquals(2, refreshed.size());
    assertEquals("e::7", refreshed.get(0).fragmentId());
    assertEquals("PENDING", refreshed.get(0).status());
    assertEquals("e::0", refreshed.get(1).fragmentId());
    assertEquals("ACCEPTED", refreshed.get(1).status(), "la decisión sobrevive");
    assertEquals(acceptedId, refreshed.get(1).citationId(), "es la misma fila");
    assertEquals(
        0,
        count("SELECT count(*) FROM prisma.evidence_citation_result WHERE fragment_id = 'e::4'"));
  }

  @Test
  void decisionsSurviveAReload() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    UUID id = evidenceService.getCitations(evaluationId, controlId).get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(
                    controlId, "politica.pdf", "Página 1", "t", 0.9, "REJECTED", ev, id))));

    // «Recargar la pantalla»: no hay búsqueda, solo lo guardado de la evaluación.
    List<EvidenceCitationDto> saved = evidenceService.savedCitations(evaluationId);

    assertEquals(1, saved.size());
    assertEquals("REJECTED", saved.get(0).status());
    assertEquals(controlId, saved.get(0).controlId());
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, decided_by, decided_at FROM prisma.evidence_citation_result");
    assertEquals("REJECTED", row.get("status"));
    assertNotNull(row.get("decided_at"));
  }

  @Test
  void theDatabaseDoesNotAllowTheSameFragmentTwiceForAControl() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    evidenceService.getCitations(evaluationId, controlId);

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO prisma.evidence_citation_result (evaluation_id, control_id,"
                    + " fragment_id, snippet, position) VALUES (?, ?, 'e::0', 'x', 9)",
                evaluationId,
                controlId));
  }

  @Test
  void theSameFragmentIdInAnotherControlIsFine() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));

    evidenceService.getCitations(evaluationId, controlId);
    evidenceService.getCitations(evaluationId, otherControlId);

    assertEquals(2, count("SELECT count(*) FROM prisma.evidence_citation_result"));
  }

  @Test
  void deletingTheEvidenceDeletesItsSavedCitations() {
    Evidence ev = evidence(controlId, "application/pdf");
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev.getId())));
    evidenceService.getCitations(evaluationId, controlId);

    jdbc.update("DELETE FROM prisma.evidence WHERE id = ?", ev.getId());

    assertEquals(0, count("SELECT count(*) FROM prisma.evidence_citation_result"));
  }

  @Test
  void whenTheEvidenceChangesTheDecidedCitationsAreMarkedStaleAndRefreshedOnTheNextQuery() {
    Evidence ev = evidence(controlId, "application/pdf");
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev.getId()), fresh("e::1", 0.8, ev.getId())));
    List<EvidenceCitationDto> first = evidenceService.getCitations(evaluationId, controlId);
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(
                    controlId,
                    "p",
                    "l",
                    "t",
                    0.9,
                    "REJECTED",
                    ev.getId(),
                    first.get(0).citationId()))));

    // Se indexa evidencia nueva del control: lo pendiente se descarta, lo decidido queda viejo.
    indexService.markIndexed(ev.getId());

    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM prisma.evidence_citation_result WHERE stale AND status = 'REJECTED'"));

    // La próxima consulta vuelve a buscar y conserva la decisión del fragmento que sigue.
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev.getId()), fresh("e::9", 0.7, ev.getId())));
    List<EvidenceCitationDto> after = evidenceService.getCitations(evaluationId, controlId);

    assertEquals(2, after.size());
    assertEquals("REJECTED", after.get(0).status());
    assertFalse(after.get(0).stale());
    assertEquals("PENDING", after.get(1).status());
  }

  @Test
  void ifTheAssistantIsDownTheStaleSavedCitationsAreStillShown() {
    Evidence ev = evidence(controlId, "application/pdf");
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev.getId())));
    List<EvidenceCitationDto> first = evidenceService.getCitations(evaluationId, controlId);
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(
                    controlId,
                    "p",
                    "l",
                    "t",
                    0.9,
                    "REJECTED",
                    ev.getId(),
                    first.get(0).citationId()))));
    indexService.markIndexed(ev.getId());
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenThrow(new uy.edu.prisma.domain.exception.AiUnavailableException("caída", null));

    List<EvidenceCitationDto> shown = evidenceService.getCitations(evaluationId, controlId);

    assertEquals(1, shown.size());
    assertTrue(shown.get(0).stale());
  }

  // ===================================================================== revertir y deshacer

  @Test
  void rejectingTheOnlyBackingCitationRevertsTheVerdictThatCameFromIt() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    UUID id = evidenceService.getCitations(evaluationId, controlId).get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(controlId, "p", "l", "t", 0.9, "ACCEPTED", ev, id))));
    assertEquals(1, count("SELECT count(*) FROM prisma.audit_control_verdicts"));

    var result =
        feedbackService.record(
            new CitationFeedbackBatchDto(
                evaluationId,
                List.of(
                    new CitationFeedbackItemDto(
                        controlId, "p", "l", "t", 0.9, "REJECTED", ev, id))));

    assertNull(result.verdicts().get(0).verdict());
    assertEquals(0, count("SELECT count(*) FROM prisma.audit_control_verdicts"));
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM audit.audit_logs WHERE action = 'SET_CONTROL_VERDICT' AND"
                + " resource = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            "evaluation:" + evaluationId);
    assertTrue(
        payload.contains("\"reverted\": true") || payload.contains("\"reverted\":true"), payload);
  }

  @Test
  void aVerdictConfirmedByHandSurvivesRejectingTheCitation() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    UUID id = evidenceService.getCitations(evaluationId, controlId).get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(controlId, "p", "l", "t", 0.9, "ACCEPTED", ev, id))));
    // El auditor lo confirma a mano: pasa a MANUAL.
    verdictService.set(evaluationId, controlId, "VALIDATED");

    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(controlId, "p", "l", "t", 0.9, "REJECTED", ev, id))));

    assertEquals(
        "VALIDATED",
        jdbc.queryForObject("SELECT verdict FROM prisma.audit_control_verdicts", String.class));
  }

  @Test
  void undoRemovesTheVerdictAndLeavesTheCitationUndecided() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    UUID id = evidenceService.getCitations(evaluationId, controlId).get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(controlId, "p", "l", "t", 0.9, "ACCEPTED", ev, id))));

    verdictService.undoCitationVerdict(evaluationId, controlId);

    assertEquals(0, count("SELECT count(*) FROM prisma.audit_control_verdicts"));
    assertEquals(
        "PENDING",
        jdbc.queryForObject("SELECT status FROM prisma.evidence_citation_result", String.class));
  }

  // ===================================================================== ciclo de vida

  @Test
  void leavingTheAuditDropsTheSavedCitationsButKeepsTheVerdict() {
    UUID ev = evidence(controlId, "application/pdf").getId();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev)));
    UUID id = evidenceService.getCitations(evaluationId, controlId).get(0).citationId();
    feedbackService.record(
        new CitationFeedbackBatchDto(
            evaluationId,
            List.of(
                new CitationFeedbackItemDto(controlId, "p", "l", "t", 0.9, "ACCEPTED", ev, id))));

    events.publishEvent(
        new EvaluationStatusChangedEvent(
            evaluationId, orgId, Evaluation.Status.IN_AUDIT, Evaluation.Status.RETURNED));

    assertEquals(0, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    assertEquals(1, count("SELECT count(*) FROM prisma.audit_control_verdicts"));
    String ref =
        jdbc.queryForObject(
            "SELECT source_ref::text FROM prisma.audit_control_verdicts", String.class);
    assertTrue(ref.contains("citationId"), "el dictamen conserva su cita de respaldo: " + ref);
  }

  @Test
  void enteringTheAuditQueuesOnePrecomputeJobAndItSavesTheCitations() {
    Evidence ev = evidence(controlId, "application/pdf");
    indexService.markIndexed(ev.getId());
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(fresh("e::0", 0.9, ev.getId())));

    events.publishEvent(
        new EvaluationStatusChangedEvent(
            evaluationId, orgId, Evaluation.Status.READY_FOR_AUDIT, Evaluation.Status.IN_AUDIT));
    events.publishEvent(
        new EvaluationStatusChangedEvent(
            evaluationId, orgId, Evaluation.Status.READY_FOR_AUDIT, Evaluation.Status.IN_AUDIT));

    assertEquals(
        1,
        count(
            "SELECT count(*) FROM prisma.ai_job WHERE type = 'CITATIONS_PRECOMPUTE' AND"
                + " status = 'QUEUED'"));
    AiJob job = queue.claimNext().get(0);
    assertEquals(AiJob.Type.CITATIONS_PRECOMPUTE, job.getType());
    assertEquals(AiJob.PRIORITY_BATCH, job.getPriority());

    runner.run(job);

    assertEquals(AiJob.Status.SUCCEEDED, jobRepo.findById(job.getId()).orElseThrow().getStatus());
    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    // El auditor las encuentra listas: la consulta no vuelve a buscar.
    reset(aiClient);
    assertEquals(1, evidenceService.getCitations(evaluationId, controlId).size());
    verify(aiClient, never()).citations(any(), any(), any(), anyString());
  }

  // ===================================================================== indexación con estado

  private AiJob claimOne() {
    List<AiJob> claimed = queue.claimNext();
    assertEquals(1, claimed.size());
    return claimed.get(0);
  }

  @Test
  void aSuccessfulIndexingGoesQueuedIndexingIndexed() {
    Evidence ev = evidence(controlId, "application/pdf");
    enqueue(ev);
    assertEquals(
        "QUEUED",
        jdbc.queryForObject(
            "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
    when(aiClient.ingest(any(), any(), any(), any(), anyString(), anyString())).thenReturn(true);

    AiJob job = claimOne();
    assertEquals(AiJob.Type.EVIDENCE_INDEX, job.getType());
    runner.run(job);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT index_status, ai_indexed, indexed_at, index_error FROM prisma.evidence WHERE id = ?",
            ev.getId());
    assertEquals("INDEXED", row.get("index_status"));
    assertEquals(true, row.get("ai_indexed"));
    assertNotNull(row.get("indexed_at"));
    assertNull(row.get("index_error"));
    assertEquals(AiJob.Status.SUCCEEDED, jobRepo.findById(job.getId()).orElseThrow().getStatus());
  }

  @Test
  void aFailedIndexingRetriesAndThenEndsInErrorWithTheReason() {
    Evidence ev = evidence(controlId, "application/pdf");
    enqueue(ev);
    when(aiClient.ingest(any(), any(), any(), any(), anyString(), anyString())).thenReturn(false);

    runner.run(claimOne()); // 1.er intento: vuelve a la cola
    assertEquals(
        "QUEUED",
        jdbc.queryForObject(
            "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
    runner.run(claimOne()); // 2.º intento: se acabaron

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT index_status, ai_indexed, index_error FROM prisma.evidence WHERE id = ?",
            ev.getId());
    assertEquals("ERROR", row.get("index_status"));
    assertEquals(false, row.get("ai_indexed"));
    assertTrue(row.get("index_error").toString().contains("No se pudo indexar"));
    assertEquals(2, count("SELECT attempts FROM prisma.ai_job WHERE type = 'EVIDENCE_INDEX'"));
  }

  @Test
  void retryingAnErroredEvidenceQueuesANewJobAndCanSucceed() {
    Evidence ev = evidence(controlId, "application/pdf");
    enqueue(ev);
    when(aiClient.ingest(any(), any(), any(), any(), anyString(), anyString())).thenReturn(false);
    runner.run(claimOne());
    runner.run(claimOne());

    // El usuario toca «Reintentar»: reindex() vuelve a encolar (aquí se llama a enqueue() directo
    // porque con la evaluación en auditoría la organización ya no puede tocar la evidencia).
    enqueue(ev);
    assertEquals(
        "QUEUED",
        jdbc.queryForObject(
            "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
    assertNull(
        jdbc.queryForObject(
            "SELECT index_error FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
    when(aiClient.ingest(any(), any(), any(), any(), anyString(), anyString())).thenReturn(true);
    runner.run(claimOne());

    assertEquals(
        "INDEXED",
        jdbc.queryForObject(
            "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
  }

  @Test
  void aZipIsNotIndexableAndGetsNoJob() {
    Evidence ev = evidence(controlId, "application/zip");

    enqueue(ev);

    assertEquals(
        "NOT_INDEXABLE",
        jdbc.queryForObject(
            "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, ev.getId()));
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_job WHERE type = 'EVIDENCE_INDEX'"));
  }

  @Test
  void evidenceIndexJobsAreSystemJobsAndDoNotAppearInTheUsersTaskList() {
    Evidence ev = evidence(controlId, "application/pdf");
    enqueue(ev);

    assertNull(
        jdbc.queryForObject(
            "SELECT requested_by FROM prisma.ai_job WHERE type = 'EVIDENCE_INDEX'", UUID.class));
  }

  @Test
  void theBackfillOfExistingEvidenceIsConsistent() {
    // Tras V43 ninguna fila puede quedar con ai_indexed = true y un estado distinto de INDEXED.
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM prisma.evidence WHERE ai_indexed AND index_status <> 'INDEXED'"));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM prisma.evidence WHERE index_status = 'INDEXED' AND NOT ai_indexed"));
  }
}
