package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EmbeddingReindexService;
import uy.edu.prisma.application.EvidenceCitationStore;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * Reindexación al cambiar el modelo de embedding, contra un Postgres real (migración V47): se
 * encola una indexación por evidencia, los resultados de citas guardados se invalidan y la
 * reindexación se dispara una sola vez por cambio de modelo.
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {
      "prisma.ai.jobs.enabled=false",
      // El vigía programado no debe correr solo durante la prueba.
      "prisma.ai.embedding-check-initial-ms=3600000"
    })
class EmbeddingReindexIT {

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

  @Autowired private EmbeddingReindexService reindex;
  @Autowired private EvidenceCitationStore citationStore;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private EvidenceRepository evidenceRepo;
  @Autowired private CatalogControlRepository controlRepo;
  @Autowired private JdbcTemplate jdbc;

  private Evaluation inAudit;
  private Evidence withControl;
  private Evidence global;
  private Evidence zip;
  private CatalogControl control;

  @BeforeEach
  void seed() {
    reset(aiClient);
    jdbc.update("DELETE FROM prisma.evidence_citation_result");
    jdbc.update("DELETE FROM prisma.evidence_control_suggestion");
    jdbc.update("DELETE FROM prisma.evidence");
    jdbc.update("DELETE FROM prisma.evaluations WHERE name LIKE 'PRUEBA-reindex%'");
    jdbc.update("DELETE FROM prisma.ai_job");
    jdbc.update("DELETE FROM prisma.ai_index_state");
    UUID orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name) VALUES (?, ?)",
        orgId,
        "PRUEBA-reindex " + orgId);
    inAudit =
        evaluationRepo.save(
            Evaluation.builder()
                .name("PRUEBA-reindex")
                .organization(Organization.builder().id(orgId).build())
                .status(Evaluation.Status.IN_AUDIT)
                .build());
    control = controlRepo.findAll().get(0);
    withControl = evidence("a.pdf", "application/pdf", control);
    global = evidence("global.pdf", "application/pdf", null);
    zip = evidence("todo.zip", "application/zip", null);
    for (Evidence e : List.of(withControl, global)) {
      jdbc.update(
          "UPDATE prisma.evidence SET index_status = 'INDEXED', ai_indexed = true WHERE id = ?",
          e.getId());
    }
  }

  private Evidence evidence(String name, String type, CatalogControl control) {
    return evidenceRepo.save(
        Evidence.builder()
            .evaluation(inAudit)
            .control(control)
            .fileName(name)
            .fileType(type)
            .storageKey("evidence/" + UUID.randomUUID())
            .scanStatus(Evidence.ScanStatus.CLEAN)
            .build());
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  private String status(Evidence e) {
    return jdbc.queryForObject(
        "SELECT index_status FROM prisma.evidence WHERE id = ?", String.class, e.getId());
  }

  private void savedCitation(String fragmentId) {
    citationStore.merge(
        inAudit,
        control,
        List.of(
            new EvidenceCitationDto(
                withControl.getId(),
                "a.pdf",
                "p1",
                "texto",
                0.7,
                fragmentId,
                null,
                null,
                null,
                null,
                false)));
  }

  @Test
  void theFirstCheckReindexesEverythingAndRemembersTheModel() {
    when(aiClient.embeddingModel()).thenReturn(Optional.of("bge-m3"));
    savedCitation("f1");
    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));

    var result = reindex.check().orElseThrow();

    assertTrue(result.changed());
    assertEquals("bge-m3", result.model());
    assertEquals("QUEUED", status(withControl));
    assertEquals("QUEUED", status(global));
    assertEquals("NOT_INDEXABLE", status(zip), "lo que no se indexa sigue sin indexarse");
    assertEquals(
        2,
        count("SELECT count(*) FROM prisma.ai_job WHERE type = 'EVIDENCE_INDEX'"),
        "un trabajo por evidencia indexable");
    assertEquals(
        0,
        count("SELECT count(*) FROM prisma.evidence_citation_result"),
        "ninguna cita vieja sobrevive");
    assertEquals(
        1,
        count(
            "SELECT count(*) FROM prisma.ai_job WHERE type = 'CITATIONS_PRECOMPUTE' AND priority"
                + " = 0"),
        "la evaluación en auditoría recalcula sus citas después de indexar (prioridad de lote)");
    assertEquals(
        "bge-m3",
        jdbc.queryForObject(
            "SELECT value FROM prisma.ai_index_state WHERE key = 'embedding_model'", String.class));
  }

  @Test
  void decidedCitationsAreKeptButMarkedStaleSoTheyAreRedone() {
    when(aiClient.embeddingModel()).thenReturn(Optional.of("bge-m3"));
    savedCitation("f1");
    jdbc.update("UPDATE prisma.evidence_citation_result SET status = 'ACCEPTED'");

    reindex.check();

    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    assertEquals(
        1, count("SELECT count(*) FROM prisma.evidence_citation_result WHERE stale = true"));
  }

  @Test
  void theSameModelDoesNotReindexAgainEvenWithTheLatestTag() {
    when(aiClient.embeddingModel()).thenReturn(Optional.of("bge-m3"));
    reindex.check();
    jdbc.update("DELETE FROM prisma.ai_job");

    when(aiClient.embeddingModel()).thenReturn(Optional.of("bge-m3:latest"));
    var second = reindex.check().orElseThrow();

    assertFalse(second.changed());
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_job"));
  }

  @Test
  void changingTheModelAgainReindexesAgain() {
    when(aiClient.embeddingModel()).thenReturn(Optional.of("bge-m3"));
    reindex.check();
    jdbc.update("DELETE FROM prisma.ai_job");

    when(aiClient.embeddingModel()).thenReturn(Optional.of("nomic-embed-text"));
    var back = reindex.check().orElseThrow();

    assertTrue(back.changed());
    assertEquals(2, count("SELECT count(*) FROM prisma.ai_job WHERE type = 'EVIDENCE_INDEX'"));
  }

  @Test
  void ifBackendAiDoesNotAnswerNothingChangesAndItIsRetriedLater() {
    when(aiClient.embeddingModel()).thenReturn(Optional.empty());

    assertTrue(reindex.check().isEmpty());

    assertEquals(0, count("SELECT count(*) FROM prisma.ai_job"));
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_index_state"));
    assertEquals("INDEXED", status(withControl));
  }
}
