package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AiJobQueue;
import uy.edu.prisma.application.AiJobRunner;
import uy.edu.prisma.application.AiJobService;
import uy.edu.prisma.application.AiTraceService;
import uy.edu.prisma.application.AuditObservationService;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EvidenceService;
import uy.edu.prisma.application.OrganizationService;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiTraceLink.ResourceType;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.AiTraceDto;
import uy.edu.prisma.web.dto.Dto.CitationSourceDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.GuideJobRequestDto;

/**
 * Modo investigación (C8, hito 5) contra un Postgres real (migración V45): con el modo apagado no
 * se registra NADA (D2), con el modo encendido se guarda la traza con su semilla y se enlaza al
 * recurso, las trazas de evidencia llevan solo ids y puntajes (D15), el acceso se verifica por la
 * organización del recurso y la exportación sale solo de {@code ai_trace}.
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {"prisma.ai.jobs.enabled=false", "prisma.ai.jobs.retry-backoff-seconds=0"})
class ResearchModeIT {

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
  @Autowired private AiTraceService traces;
  @Autowired private OrganizationService organizations;
  @Autowired private EvidenceService evidenceService;
  @Autowired private AuditObservationService observations;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private EvidenceRepository evidenceRepo;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper mapper;

  private UUID orgId;
  private UUID evaluationId;
  private UUID controlId;

  @BeforeEach
  void seed() {
    reset(aiClient);
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    when(aiClient.traceConfig()).thenReturn(Map.of("model", "llama3:8b", "model_digest", "abc"));
    jdbc.update("DELETE FROM prisma.ai_trace");
    jdbc.update("DELETE FROM prisma.audit_observations");
    jdbc.update("DELETE FROM prisma.evidence_citation_result");
    jdbc.update("DELETE FROM prisma.ai_generated_guide");
    jdbc.update("DELETE FROM prisma.ai_job");

    orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name) VALUES (?, ?)",
        orgId,
        "PRUEBA-hito5 " + orgId);
    evaluationId =
        evaluationRepo
            .save(
                Evaluation.builder()
                    .name("PRUEBA-hito5")
                    .organization(Organization.builder().id(orgId).build())
                    .status(Evaluation.Status.IN_AUDIT)
                    .build())
            .getId();
    controlId = jdbc.queryForObject("SELECT id FROM prisma.catalog_controls LIMIT 1", UUID.class);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private AiJob runGuideJob() {
    jobService.enqueueGuide(new GuideJobRequestDto(controlId, evaluationId, "F", "C", "S", 0, 2));
    AiJob job = queue.claimNext().get(0);
    runner.run(job);
    return job;
  }

  private GuideResult guideWithTrace() {
    return new GuideResult(
        "Resumen",
        List.of("Paso 1"),
        "llama3:8b",
        Map.of(),
        Map.of(
            "seed",
            99,
            "config",
            Map.of("model", "llama3:8b", "model_digest", "abc", "num_ctx", 16384),
            "prompt",
            "PROMPT",
            "response",
            "RESPUESTA",
            "thinking",
            "RAZONAMIENTO",
            "retrieval",
            List.of(Map.of("source", "mcu.pdf", "score", 0.4)),
            "metrics",
            Map.of("eval_count", 7)));
  }

  private Evidence globalEvidenceOfEvaluation() {
    return evidenceRepo.save(
        Evidence.builder()
            .evaluation(evaluationRepo.getReferenceById(evaluationId))
            .fileName("a.pdf")
            .fileType("application/pdf")
            .storageKey("evidence/" + UUID.randomUUID())
            .scanStatus(Evidence.ScanStatus.CLEAN)
            .build());
  }

  // ====================================================================== modo apagado (D2)

  @Test
  void withTheModeOffNothingIsRequestedNorRecorded() {
    when(aiClient.generateGuide(
            anyString(), anyString(), any(), any(), any(), anyInt(), anyInt(), anyBoolean(), any()))
        .thenReturn(guideWithTrace());

    AiJob job = runGuideJob();

    ArgumentCaptor<Boolean> trace = ArgumentCaptor.forClass(Boolean.class);
    ArgumentCaptor<Long> seed = ArgumentCaptor.forClass(Long.class);
    verify(aiClient)
        .generateGuide(
            any(), any(), any(), any(), any(), anyInt(), anyInt(), trace.capture(), seed.capture());
    assertFalse(trace.getValue(), "apagado: no se pide traza");
    assertNull(seed.getValue(), "apagado: no se fija semilla");
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_trace"));
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_trace_link"));
    assertEquals(AiJob.Status.SUCCEEDED, jobStatus(job));
    assertNull(jdbc.queryForObject("SELECT trace_id FROM prisma.ai_generated_guide", UUID.class));
  }

  @Test
  void withTheModeOffCitationsAndClassificationLeaveNoTrace() {
    Evidence e = globalEvidenceOfEvaluation();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(citation(e.getId(), "f1", 0.7)));

    evidenceService.refreshCitations(evaluationId, controlId);

    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_trace"));
  }

  // =================================================================== modo encendido (guía)

  @Test
  void withTheModeOnTheGuideTraceIsRecordedWithItsSeedAndLinkedToTheGuide() {
    organizations.setResearchMode(orgId, true);
    when(aiClient.generateGuide(
            anyString(), anyString(), any(), any(), any(), anyInt(), anyInt(), anyBoolean(), any()))
        .thenReturn(guideWithTrace());

    runGuideJob();

    ArgumentCaptor<Boolean> trace = ArgumentCaptor.forClass(Boolean.class);
    ArgumentCaptor<Long> seed = ArgumentCaptor.forClass(Long.class);
    verify(aiClient)
        .generateGuide(
            any(), any(), any(), any(), any(), anyInt(), anyInt(), trace.capture(), seed.capture());
    assertTrue(trace.getValue());
    assertNotNull(seed.getValue(), "se envía una semilla aleatoria por pedido");

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM prisma.ai_trace");
    assertEquals("GUIDE", row.get("kind"));
    assertEquals(seed.getValue(), ((Number) row.get("seed")).longValue(), "se guarda la enviada");
    assertEquals("PROMPT", row.get("prompt"));
    assertEquals("RAZONAMIENTO", row.get("thinking"));
    assertTrue(row.get("config").toString().contains("abc"), "digest del modelo");
    assertEquals(
        row.get("id"),
        jdbc.queryForObject("SELECT trace_id FROM prisma.ai_generated_guide", UUID.class));
  }

  @Test
  void theTraceTablesCarryNoOrganizationNorUser() {
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'prisma' AND"
                + " table_name = 'ai_trace' AND (column_name LIKE '%organization%' OR column_name"
                + " LIKE '%user%' OR column_name LIKE '%requested%')"));
  }

  // ================================================================ modo encendido (evidencia)

  @Test
  void citationTracesCarryOnlyIdsAndScoresAndAreLinkedToEachCitation() {
    organizations.setResearchMode(orgId, true);
    Evidence e = globalEvidenceOfEvaluation();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(citation(e.getId(), "f1", 0.71), citation(e.getId(), "f2", 0.52)));

    evidenceService.refreshCitations(evaluationId, controlId);

    assertEquals(1, count("SELECT count(*) FROM prisma.ai_trace WHERE kind = 'CITATIONS'"));
    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM prisma.ai_trace");
    assertNull(row.get("prompt"), "D15: sin texto");
    assertNull(row.get("response"));
    String retrieval = row.get("retrieval").toString();
    assertTrue(retrieval.contains("f1") && retrieval.contains("0.71"));
    assertFalse(retrieval.contains("TEXTO-DEL-ARCHIVO"), "el fragmento nunca se guarda");
    assertEquals(
        2, count("SELECT count(*) FROM prisma.ai_trace_link WHERE resource_type = 'CITATION'"));

    UUID citationId =
        jdbc.queryForObject(
            "SELECT id FROM prisma.evidence_citation_result WHERE fragment_id = 'f1'", UUID.class);
    AiTraceDto dto = traces.forResource(ResourceType.CITATION, citationId);
    assertEquals("CITATIONS", dto.kind());
    assertEquals(2, dto.retrieval().size());
  }

  @Test
  void anObservationCreatedFromACitationInheritsItsTrace() {
    organizations.setResearchMode(orgId, true);
    Evidence e = globalEvidenceOfEvaluation();
    when(aiClient.citations(any(), any(), any(), anyString()))
        .thenReturn(List.of(citation(e.getId(), "f1", 0.7)));
    evidenceService.refreshCitations(evaluationId, controlId);
    UUID citationId =
        jdbc.queryForObject("SELECT id FROM prisma.evidence_citation_result", UUID.class);

    var obs =
        observations.create(
            new CreateAuditObservationDto(
                evaluationId,
                controlId,
                null,
                "OBSERVATION",
                "texto",
                null,
                null,
                new CitationSourceDto(
                    "a.pdf", "p1", "TEXTO-DEL-ARCHIVO", 0.7, "texto", citationId)));

    AiTraceDto dto = traces.forResource(ResourceType.OBSERVATION, obs.id());
    assertEquals("CITATIONS", dto.kind());
  }

  @Test
  void theClassificationTraceIsLinkedToTheEvidenceWithControlIdsAndScores() {
    organizations.setResearchMode(orgId, true);
    Evidence e = globalEvidenceOfEvaluation();

    traces.recordClassification(
        orgId, e.getId(), List.of(new AiEvidenceClient.ControlSuggestion(controlId, 0.8)));

    AiTraceDto dto = traces.forResource(ResourceType.CLASSIFICATION, e.getId());
    assertEquals("CLASSIFY", dto.kind());
    assertEquals(0.8, ((Number) dto.retrieval().get(0).get("score")).doubleValue());
  }

  // ============================================================== consulta, export y retención

  @Test
  void aResourceWithoutTraceAnswersNotFoundAndTheAccessIsCheckedOnTheLinkedOrganization() {
    assertThrows(
        ResourceNotFoundException.class,
        () -> traces.forResource(ResourceType.IMPROVEMENT_PLAN, UUID.randomUUID()));

    organizations.setResearchMode(orgId, true);
    UUID traceId = traces.recordGuide(guideWithTrace().trace(), 99L);
    UUID planId = UUID.randomUUID();
    traces.link(traceId, ResourceType.IMPROVEMENT_PLAN, planId, orgId);
    assertEquals(traceId, traces.forResource(ResourceType.IMPROVEMENT_PLAN, planId).id());

    org.mockito.Mockito.doThrow(new SecurityException("sin acceso"))
        .when(currentUser)
        .assertOrganizationAccess(orgId);
    assertThrows(
        SecurityException.class, () -> traces.forResource(ResourceType.IMPROVEMENT_PLAN, planId));
    assertThrows(SecurityException.class, () -> traces.get(traceId));
  }

  @Test
  void theJsonlExportComesFromTheTraceTableOnlyWithoutOrganizationOrUser() throws Exception {
    organizations.setResearchMode(orgId, true);
    UUID traceId = traces.recordGuide(guideWithTrace().trace(), 99L);
    traces.link(traceId, ResourceType.IMPROVEMENT_PLAN, UUID.randomUUID(), orgId);
    traces.recordGuide(guideWithTrace().trace(), 100L);

    StringWriter out = new StringWriter();
    int n = traces.exportJsonl(out);

    assertEquals(2, n);
    String[] lines = out.toString().split("\n");
    assertEquals(2, lines.length);
    Map<?, ?> first = mapper.readValue(lines[0], Map.class);
    assertEquals("GUIDE", first.get("kind"));
    assertEquals(99, first.get("seed"));
    assertFalse(out.toString().contains(orgId.toString()), "sin organización");
    assertFalse(first.containsKey("organizationId"));
    assertFalse(first.containsKey("resourceId"));
  }

  @Test
  void retentionOnlyDeletesWhenADaysValueIsConfigured() {
    UUID old = traces.recordGuide(guideWithTrace().trace(), 1L);
    jdbc.update(
        "UPDATE prisma.ai_trace SET created_at = now() - interval '40 days' WHERE id = ?", old);
    traces.recordGuide(guideWithTrace().trace(), 2L);

    assertEquals(0, traces.purgeOlderThan(0), "sin plazo definido no se borra nada");
    assertEquals(2, count("SELECT count(*) FROM prisma.ai_trace"));
    assertEquals(1, traces.purgeOlderThan(30));
    assertEquals(1, count("SELECT count(*) FROM prisma.ai_trace"));
  }

  @Test
  void turningTheModeOffDoesNotDeleteExistingTracesAndEachChangeIsAudited() {
    organizations.setResearchMode(orgId, true);
    traces.recordGuide(guideWithTrace().trace(), 1L);
    organizations.setResearchMode(orgId, false);

    assertEquals(1, count("SELECT count(*) FROM prisma.ai_trace"));
    assertFalse(traces.isEnabled(orgId));
    // Apagado de nuevo: ya no se registra.
    traces.recordCitations(orgId, List.of());
    assertEquals(1, count("SELECT count(*) FROM prisma.ai_trace"));
    assertTrue(
        count(
                "SELECT count(*) FROM audit.audit_logs WHERE action IN ('RESEARCH_MODE_ON',"
                    + " 'RESEARCH_MODE_OFF') AND resource = ?",
                "organization:" + orgId)
            >= 2);
  }

  // ------------------------------------------------------------------------------- helpers

  private AiJob.Status jobStatus(AiJob job) {
    return AiJob.Status.valueOf(
        jdbc.queryForObject(
            "SELECT status FROM prisma.ai_job WHERE id = ?", String.class, job.getId()));
  }

  private EvidenceCitationDto citation(UUID evidenceId, String fragmentId, double score) {
    return new EvidenceCitationDto(
        evidenceId,
        "a.pdf",
        "p1",
        "TEXTO-DEL-ARCHIVO",
        score,
        fragmentId,
        null,
        null,
        null,
        null,
        false);
  }
}
