package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
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
import uy.edu.prisma.application.EvaluationService;
import uy.edu.prisma.application.EvidenceCitationStore;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * Ciclo de vida de las citas contra un Postgres real. El listener {@code AFTER_COMMIT} corre cuando
 * la transacción del cambio de estado ya se confirmó: si escribe sin abrir una transacción propia,
 * sus cambios se pierden en silencio (el precálculo nunca se encola y las citas no se descartan al
 * salir de auditoría).
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {"prisma.ai.jobs.enabled=false"})
class CitationLifecycleIT {

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

  @Autowired private EvaluationService evaluationService;
  @Autowired private EvidenceCitationStore citationStore;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private EvidenceRepository evidenceRepo;
  @Autowired private CatalogControlRepository controlRepo;
  @Autowired private JdbcTemplate jdbc;

  private UUID orgId;

  @BeforeEach
  void seed() {
    reset(currentUser);
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    when(currentUser.isAuditor()).thenReturn(true);
    jdbc.update("DELETE FROM prisma.evidence_citation_result");
    jdbc.update("DELETE FROM prisma.audit_control_verdicts");
    jdbc.update("DELETE FROM prisma.ai_job");
    orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name) VALUES (?, ?)",
        orgId,
        "PRUEBA-ciclo " + orgId);
  }

  private Evaluation evaluation(Evaluation.Status status) {
    return evaluationRepo.save(
        Evaluation.builder()
            .name("PRUEBA-ciclo")
            .organization(Organization.builder().id(orgId).build())
            .status(status)
            .build());
  }

  private int count(String sql) {
    Integer n = jdbc.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }

  @Test
  void enteringTheAuditReallyQueuesThePrecompute() {
    Evaluation eval = evaluation(Evaluation.Status.READY_FOR_AUDIT);

    evaluationService.updateStatus(eval.getId(), "IN_AUDIT");

    assertEquals(
        1,
        count("SELECT count(*) FROM prisma.ai_job WHERE type = 'CITATIONS_PRECOMPUTE'"),
        "el trabajo tiene que quedar confirmado en la base, no solo 'guardado'");
  }

  @Test
  void leavingTheAuditReallyDropsTheSavedCitations() {
    Evaluation eval = evaluation(Evaluation.Status.IN_AUDIT);
    CatalogControl control = controlRepo.findAll().get(0);
    Evidence evidence =
        evidenceRepo.save(
            Evidence.builder()
                .evaluation(eval)
                .control(control)
                .fileName("a.pdf")
                .fileType("application/pdf")
                .storageKey("evidence/" + UUID.randomUUID())
                .scanStatus(Evidence.ScanStatus.CLEAN)
                .build());
    citationStore.merge(
        eval,
        control,
        List.of(
            new EvidenceCitationDto(
                evidence.getId(),
                "a.pdf",
                "p1",
                "texto",
                0.7,
                "f1",
                null,
                null,
                null,
                null,
                false)));
    assertEquals(1, count("SELECT count(*) FROM prisma.evidence_citation_result"));
    jdbc.update(
        "INSERT INTO prisma.audit_control_verdicts (evaluation_id, control_id, verdict) VALUES (?, ?,"
            + " 'VALIDATED')",
        eval.getId(),
        control.getId());

    evaluationService.updateStatus(eval.getId(), "RETURNED");

    assertEquals(
        0,
        count("SELECT count(*) FROM prisma.evidence_citation_result"),
        "al salir de auditoría las citas pendientes se descartan");
    assertEquals(
        1,
        count("SELECT count(*) FROM prisma.audit_control_verdicts"),
        "el dictamen ya fijado no se toca");
  }
}
