package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AiJobQueue;
import uy.edu.prisma.application.AiJobRunner;
import uy.edu.prisma.application.AiJobService;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EvidenceClassificationService;
import uy.edu.prisma.application.EvidenceIndexService;
import uy.edu.prisma.application.EvidenceService;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.TooManyRequestsException;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ControlSuggestion;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.GuideBatchRequestDto;
import uy.edu.prisma.web.dto.Dto.GuideJobRequestDto;

/**
 * Lote de guías (F3) y evidencia global (F4) contra un Postgres real (migración V44): la prioridad
 * de lote frente a un pedido interactivo en la cola real, la restricción de trabajos activos, la
 * preclasificación (que NO asigna la evidencia) y el feedback anónimo. Trabajador apagado: la cola
 * se maneja a mano. Tope global de concurrencia 1 para observar el orden de reclamo.
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {
      "prisma.ai.jobs.enabled=false",
      "prisma.ai.jobs.concurrency=1",
      "prisma.ai.jobs.max-batch-size=5",
      "prisma.ai.jobs.max-pending-batch-per-org=8",
      "prisma.ai.jobs.retry-backoff-seconds=0"
    })
class BatchAndGlobalEvidenceIT {

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

  @Autowired private AiJobService jobService;
  @Autowired private AiJobQueue queue;
  @Autowired private AiJobRunner runner;
  @Autowired private AiJobRepository jobRepo;
  @Autowired private EvidenceIndexService indexService;
  @Autowired private EvidenceClassificationService classification;
  @Autowired private EvidenceService evidenceService;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private EvidenceRepository evidenceRepo;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private JdbcTemplate jdbc;

  private UUID orgId;
  private UUID evaluationId;
  private List<UUID> controls;

  @BeforeEach
  void seed() {
    reset(aiClient);
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    jdbc.update("DELETE FROM prisma.ai_evidence_classification_feedback");
    jdbc.update("DELETE FROM prisma.evidence_control_suggestion");
    jdbc.update("DELETE FROM prisma.evidence_citation_result");
    jdbc.update("DELETE FROM prisma.ai_generated_guide");
    jdbc.update("DELETE FROM prisma.ai_job");

    orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name, identifier) VALUES (?, ?, ?)",
        orgId,
        "PRUEBA-hito4 " + orgId,
        null);
    Evaluation eval =
        evaluationRepo.save(
            Evaluation.builder()
                .name("PRUEBA-hito4")
                .organization(Organization.builder().id(orgId).build())
                .status(Evaluation.Status.IN_AUDIT)
                .build());
    evaluationId = eval.getId();
    controls = jdbc.queryForList("SELECT id FROM prisma.catalog_controls LIMIT 6", UUID.class);
  }

  private GuideJobRequestDto item(UUID controlId) {
    return new GuideJobRequestDto(controlId, evaluationId, "F", "C", "S", 0, 2);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  // ======================================================================= lote de guías

  @Test
  void aBatchCreatesOneBatchPriorityJobPerControlAndTheMigrationAllowsIt() {
    var accepted =
        jobService.enqueueGuideBatch(
            new GuideBatchRequestDto(
                List.of(item(controls.get(0)), item(controls.get(1)), item(controls.get(2)))));

    assertEquals(3, accepted.jobs().size());
    assertEquals(
        3,
        count(
            "SELECT count(*) FROM prisma.ai_job WHERE batch_id = ? AND priority = 0 AND type ="
                + " 'GUIDE' AND status = 'QUEUED'",
            accepted.batchId()));
  }

  @Test
  void anInteractiveRequestIsClaimedBeforeTheQueuedBatch() {
    jobService.enqueueGuideBatch(
        new GuideBatchRequestDto(List.of(item(controls.get(0)), item(controls.get(1)))));
    // Después del lote, alguien pide una guía suelta (interactiva): pasa adelante.
    var single = jobService.enqueueGuide(item(controls.get(3)));

    List<AiJob> claimed = queue.claimNext();

    assertEquals(1, claimed.size(), "tope global 1");
    assertEquals(single.jobId(), claimed.get(0).getId());
    assertEquals(AiJob.PRIORITY_INTERACTIVE, claimed.get(0).getPriority());
  }

  @Test
  void aControlWithAnActiveJobIsReusedByTheBatch() {
    var single = jobService.enqueueGuide(item(controls.get(0)));

    var accepted =
        jobService.enqueueGuideBatch(
            new GuideBatchRequestDto(List.of(item(controls.get(0)), item(controls.get(1)))));

    assertEquals(single.jobId(), accepted.jobs().get(0).jobId());
    assertEquals(2, count("SELECT count(*) FROM prisma.ai_job WHERE type = 'GUIDE'"));
    assertEquals(
        1,
        count("SELECT count(*) FROM prisma.ai_job WHERE batch_id = ?", accepted.batchId()),
        "solo el control nuevo entra al lote");
  }

  @Test
  void theSameControlTwiceInTheSameBatchIsOneJob() {
    var accepted =
        jobService.enqueueGuideBatch(
            new GuideBatchRequestDto(List.of(item(controls.get(0)), item(controls.get(0)))));

    assertEquals(accepted.jobs().get(0).jobId(), accepted.jobs().get(1).jobId());
    assertEquals(1, count("SELECT count(*) FROM prisma.ai_job"));
  }

  @Test
  void theBatchLimitsAreEnforcedAgainstTheDatabase() {
    assertThrows(
        InvalidRequestException.class,
        () ->
            jobService.enqueueGuideBatch(
                new GuideBatchRequestDto(controls.stream().map(this::item).toList()))); // 6 > 5

    jobService.enqueueGuideBatch(
        new GuideBatchRequestDto(controls.subList(0, 5).stream().map(this::item).toList()));
    // Ya hay 5 de lote activos y el tope por organización es 8: otros 5 no entran.
    assertThrows(
        TooManyRequestsException.class,
        () ->
            jobService.enqueueGuideBatch(
                new GuideBatchRequestDto(
                    List.of(
                        new GuideJobRequestDto(
                            controls.get(0), evaluationId, null, null, null, 1, 3),
                        new GuideJobRequestDto(
                            controls.get(1), evaluationId, null, null, null, 1, 3),
                        new GuideJobRequestDto(
                            controls.get(2), evaluationId, null, null, null, 1, 3),
                        new GuideJobRequestDto(
                            controls.get(3), evaluationId, null, null, null, 1, 3)))));
  }

  @Test
  void cancellingTheBatchCancelsOnlyWhatIsStillQueuedAndKeepsWhatRuns() {
    var accepted =
        jobService.enqueueGuideBatch(
            new GuideBatchRequestDto(
                List.of(item(controls.get(0)), item(controls.get(1)), item(controls.get(2)))));
    AiJob running = queue.claimNext().get(0); // pasa a RUNNING (tope 1)

    int cancelled = jobService.cancelBatch(accepted.batchId());

    assertEquals(2, cancelled);
    assertEquals(AiJob.Status.RUNNING, jobRepo.findById(running.getId()).orElseThrow().getStatus());
    assertEquals(
        2,
        count(
            "SELECT count(*) FROM prisma.ai_job WHERE batch_id = ? AND status = 'CANCELLED'",
            accepted.batchId()));
  }

  @Test
  void theProgressOfTheBatchComesFromGetManyWithTheGuidesOfTheFinishedOnes() {
    var accepted =
        jobService.enqueueGuideBatch(
            new GuideBatchRequestDto(List.of(item(controls.get(0)), item(controls.get(1)))));
    AiJob first = queue.claimNext().get(0);
    queue.complete(
        first.getId(), new GuideResult("Resumen", List.of("Paso"), "llama3:8b", Map.of()), 1000);

    var many = jobService.getMany(accepted.jobs().stream().map(j -> j.jobId()).toList());

    assertEquals(2, many.size());
    var done = many.stream().filter(j -> j.status().equals("SUCCEEDED")).findFirst().orElseThrow();
    assertEquals("Resumen", done.guide().summary());
    assertEquals(first.getParams().get("controlId").toString(), done.controlId().toString());
    assertEquals(accepted.batchId(), done.batchId());
    var queued = many.stream().filter(j -> j.status().equals("QUEUED")).findFirst().orElseThrow();
    assertNotNull(queued.queuePosition());
    assertNull(queued.guide());
  }

  // ===================================================== evidencia global y preclasificación

  private Evidence globalEvidence() {
    return evidenceRepo.save(
        Evidence.builder()
            .evaluation(evaluationRepo.getReferenceById(evaluationId))
            .fileName("politica-general.pdf")
            .fileType("application/pdf")
            .storageKey("evidence/" + UUID.randomUUID())
            .scanStatus(Evidence.ScanStatus.CLEAN)
            .build());
  }

  private void markIndexed(Evidence e) {
    new TransactionTemplate(txManager)
        .executeWithoutResult(s -> indexService.markIndexed(e.getId()));
  }

  @Test
  void whenAGlobalEvidenceIsIndexedItsClassificationIsQueuedAsABatchSystemJob() {
    Evidence e = globalEvidence();

    markIndexed(e);

    assertEquals(
        1,
        count(
            "SELECT count(*) FROM prisma.ai_job WHERE type = 'EVIDENCE_CLASSIFY' AND priority = 0"
                + " AND requested_by IS NULL AND status = 'QUEUED'"));
  }

  @Test
  void anEvidenceWithAControlIsNotClassified() {
    Evidence e =
        evidenceRepo.save(
            Evidence.builder()
                .evaluation(evaluationRepo.getReferenceById(evaluationId))
                .control(CatalogControl.builder().id(controls.get(0)).build())
                .fileName("p.pdf")
                .fileType("application/pdf")
                .storageKey("evidence/" + UUID.randomUUID())
                .scanStatus(Evidence.ScanStatus.CLEAN)
                .build());

    markIndexed(e);

    assertEquals(0, count("SELECT count(*) FROM prisma.ai_job WHERE type = 'EVIDENCE_CLASSIFY'"));
  }

  @Test
  void theClassificationSavesTheTopSuggestionsAndAcceptingOneNeverAssignsTheEvidence() {
    Evidence e = globalEvidence();
    markIndexed(e);
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ControlSuggestion(controls.get(2), 0.81),
                new ControlSuggestion(controls.get(0), 0.55),
                new ControlSuggestion(controls.get(4), 0.4)));

    AiJob job = queue.claimNext().get(0);
    assertEquals(AiJob.Type.EVIDENCE_CLASSIFY, job.getType());
    runner.run(job);

    assertEquals(AiJob.Status.SUCCEEDED, jobRepo.findById(job.getId()).orElseThrow().getStatus());
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT control_id, score, rank, status FROM prisma.evidence_control_suggestion ORDER BY"
                + " rank");
    assertEquals(3, rows.size());
    assertEquals(controls.get(2), rows.get(0).get("control_id"));
    assertEquals("PENDING", rows.get(0).get("status"));
    // Los candidatos son los controles reales del catálogo de la evaluación (> 1 candidato).
    org.mockito.ArgumentCaptor<List<AiEvidenceClient.ClassifyCandidate>> cands =
        org.mockito.ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(aiClient).classify(any(), any(), any(), cands.capture(), anyInt());
    assertTrue(cands.getValue().size() > 100, "candidatos: " + cands.getValue().size());

    UUID suggestionId =
        jdbc.queryForObject(
            "SELECT id FROM prisma.evidence_control_suggestion WHERE rank = 1", UUID.class);
    var dto = classification.decide(suggestionId, "ACCEPTED");

    assertEquals("ACCEPTED", dto.status());
    // D7: sigue sin control (solo una sugerencia).
    assertNull(
        jdbc.queryForObject(
            "SELECT control_id FROM prisma.evidence WHERE id = ?", UUID.class, e.getId()));
    Map<String, Object> feedback =
        jdbc.queryForMap(
            "SELECT control_id, score, rank, decision FROM prisma.ai_evidence_classification_feedback");
    assertEquals(controls.get(2), feedback.get("control_id"));
    assertEquals("ACCEPTED", feedback.get("decision"));
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                + " 'ai_evidence_classification_feedback' AND (column_name LIKE '%organization%' OR"
                + " column_name LIKE '%evaluation%' OR column_name LIKE '%user%' OR column_name LIKE"
                + " '%file%' OR column_name LIKE '%snippet%')"));
  }

  @Test
  void classifyingAgainKeepsTheDecidedSuggestionsAndRebuildsThePendingOnes() {
    Evidence e = globalEvidence();
    markIndexed(e);
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ControlSuggestion(controls.get(0), 0.8),
                new ControlSuggestion(controls.get(1), 0.5)));
    runner.run(queue.claimNext().get(0));
    UUID first =
        jdbc.queryForObject(
            "SELECT id FROM prisma.evidence_control_suggestion WHERE rank = 1", UUID.class);
    classification.decide(first, "REJECTED");

    // Se reindexa la evidencia: se vuelve a clasificar.
    markIndexed(e);
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ControlSuggestion(controls.get(0), 0.9),
                new ControlSuggestion(controls.get(3), 0.6)));
    runner.run(queue.claimNext().get(0));

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT control_id, status FROM prisma.evidence_control_suggestion ORDER BY control_id");
    assertEquals(
        2,
        rows.size(),
        "la rechazada se conserva y no se vuelve a sugerir; la pendiente se rehizo");
    assertTrue(
        rows.stream()
            .anyMatch(
                r ->
                    r.get("control_id").equals(controls.get(0))
                        && r.get("status").equals("REJECTED")));
    assertTrue(
        rows.stream()
            .anyMatch(
                r ->
                    r.get("control_id").equals(controls.get(3))
                        && r.get("status").equals("PENDING")));
  }

  @Test
  void ifTheAssistantIsDownTheClassificationRetriesThenFailsCleanly() {
    Evidence e = globalEvidence();
    markIndexed(e);
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenThrow(new uy.edu.prisma.domain.exception.AiUnavailableException("caída", null));

    runner.run(queue.claimNext().get(0)); // 1.er intento: vuelve a la cola
    runner.run(queue.claimNext().get(0)); // 2.º: FAILED

    assertEquals(
        "FAILED",
        jdbc.queryForObject(
            "SELECT status FROM prisma.ai_job WHERE type = 'EVIDENCE_CLASSIFY'", String.class));
    assertEquals(0, count("SELECT count(*) FROM prisma.evidence_control_suggestion"));
  }

  @Test
  void citationsFromGlobalEvidenceArePersistedAsUnassigned() {
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(
            List.of(
                new EvidenceCitationDto(
                    null,
                    "politica-general.pdf",
                    "p1",
                    "texto",
                    0.7,
                    "g::1",
                    null,
                    null,
                    null,
                    null,
                    false,
                    true),
                new EvidenceCitationDto(
                    null,
                    "del-control.pdf",
                    "p2",
                    "texto",
                    0.6,
                    "c::1",
                    null,
                    null,
                    null,
                    null,
                    false,
                    false)));

    List<EvidenceCitationDto> result = evidenceService.getCitations(evaluationId, controls.get(0));

    assertTrue(result.get(0).unassigned());
    assertEquals(false, result.get(1).unassigned());
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM prisma.evidence_citation_result WHERE unassigned AND fragment_id = 'g::1'"));
    // Al reconsultar (de lo guardado) la marca se conserva.
    assertTrue(evidenceService.getCitations(evaluationId, controls.get(0)).get(0).unassigned());
  }
}
