package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AiCitationFeedbackService;
import uy.edu.prisma.application.AuditObservationService;
import uy.edu.prisma.application.AuditVerdictService;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.VerdictOverrideRequiredException;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackItemDto;
import uy.edu.prisma.web.dto.Dto.CitationSourceDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;

/**
 * Aceptar una cita valida el control (C4a) y «Registrar como observación» deja feedback (C4b),
 * contra un Postgres real (migración V42): lo que importa es la TRANSACCIÓN (la decisión sobre la
 * cita y el dictamen se escriben juntos o no se escribe nada), el CHECK de origen/cita y el mapeo
 * JSONB, que los tests con mocks no ven.
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
class CitationVerdictIT {

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

  @Autowired private AiCitationFeedbackService citationService;
  @Autowired private AuditVerdictService verdictService;
  @Autowired private AuditObservationService observationService;
  @Autowired private EvaluationRepository evaluationRepo;
  @Autowired private JdbcTemplate jdbc;

  private UUID evaluationId;
  private UUID controlId;
  private UUID evidenceId;

  @BeforeEach
  void seed() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    jdbc.update("DELETE FROM prisma.ai_citation_validation_feedback");
    jdbc.update("DELETE FROM prisma.ai_citation_feedback");
    jdbc.update("DELETE FROM prisma.audit_observations");
    jdbc.update("DELETE FROM prisma.audit_control_verdicts");

    UUID orgId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name, identifier) VALUES (?, ?, ?)",
        orgId,
        "PRUEBA-hito2 " + orgId,
        null);
    Evaluation eval =
        evaluationRepo.save(
            Evaluation.builder()
                .name("PRUEBA-hito2")
                .organization(Organization.builder().id(orgId).build())
                .status(Evaluation.Status.IN_AUDIT)
                .build());
    evaluationId = eval.getId();
    controlId = jdbc.queryForObject("SELECT id FROM prisma.catalog_controls LIMIT 1", UUID.class);
    evidenceId = UUID.randomUUID();
  }

  private CitationFeedbackItemDto item(String decision) {
    return new CitationFeedbackItemDto(
        controlId, "politica.pdf", "Página 2", "texto citado", 0.71, decision, evidenceId);
  }

  private Map<String, Object> verdictRow() {
    return jdbc.queryForMap(
        "SELECT verdict, source, source_ref::text AS source_ref FROM prisma.audit_control_verdicts"
            + " WHERE evaluation_id = ? AND control_id = ?",
        evaluationId,
        controlId);
  }

  private int count(String sql, Object... args) {
    Integer n = jdbc.queryForObject(sql, Integer.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void acceptingACitationValidatesTheControlAndStoresTheBackup() {
    var result =
        citationService.record(
            new CitationFeedbackBatchDto(evaluationId, List.of(item("ACCEPTED"))));

    assertEquals("VALIDATED", result.verdicts().get(0).verdict());
    Map<String, Object> row = verdictRow();
    assertEquals("VALIDATED", row.get("verdict"));
    assertEquals("CITATION", row.get("source"));
    String ref = row.get("source_ref").toString();
    assertTrue(ref.contains("politica.pdf"), ref);
    assertTrue(ref.contains(evidenceId.toString()), ref);
    assertEquals(
        1, count("SELECT count(*) FROM prisma.ai_citation_feedback WHERE decision='ACCEPTED'"));
    // Registro de auditoría con el origen y la cita.
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM audit.audit_logs WHERE action = 'SET_CONTROL_VERDICT'"
                + " AND resource = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            "evaluation:" + evaluationId);
    assertNotNull(payload);
    assertTrue(payload.contains("CITATION") && payload.contains("politica.pdf"), payload);
  }

  @Test
  void rejectingCitationsLeavesTheControlWithoutVerdict() {
    citationService.record(
        new CitationFeedbackBatchDto(evaluationId, List.of(item("REJECTED"), item("REJECTED"))));

    assertEquals(0, count("SELECT count(*) FROM prisma.audit_control_verdicts"));
    assertEquals(
        2, count("SELECT count(*) FROM prisma.ai_citation_feedback WHERE decision='REJECTED'"));
  }

  @Test
  void aManualVerdictNeedsConfirmationAndNothingIsWrittenWithoutIt() {
    verdictService.set(evaluationId, controlId, "NOT_VALIDATED");

    assertThrows(
        VerdictOverrideRequiredException.class,
        () ->
            citationService.record(
                new CitationFeedbackBatchDto(evaluationId, List.of(item("ACCEPTED")))));

    // Atomicidad: ni el dictamen cambió ni quedó la decisión sobre la cita.
    assertEquals("NOT_VALIDATED", verdictRow().get("verdict"));
    assertEquals("MANUAL", verdictRow().get("source"));
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_citation_feedback"));
  }

  @Test
  void withConfirmationTheManualVerdictIsReplaced() {
    verdictService.set(evaluationId, controlId, "NEEDS_MORE_EVIDENCE");

    citationService.record(
        new CitationFeedbackBatchDto(evaluationId, List.of(item("ACCEPTED")), Boolean.TRUE));

    assertEquals("VALIDATED", verdictRow().get("verdict"));
    assertEquals("CITATION", verdictRow().get("source"));
    assertEquals(1, count("SELECT count(*) FROM prisma.ai_citation_feedback"));
  }

  @Test
  void anAlreadyValidatedControlKeepsItsOrigin() {
    verdictService.set(evaluationId, controlId, "VALIDATED");

    var result =
        citationService.record(
            new CitationFeedbackBatchDto(evaluationId, List.of(item("ACCEPTED"))));

    assertEquals(false, result.verdicts().get(0).changed());
    assertEquals("MANUAL", verdictRow().get("source"));
    assertEquals(1, count("SELECT count(*) FROM prisma.ai_citation_feedback"));
  }

  @Test
  void settingTheVerdictByHandAfterACitationDropsTheOrigin() {
    citationService.record(new CitationFeedbackBatchDto(evaluationId, List.of(item("ACCEPTED"))));

    verdictService.set(evaluationId, controlId, "NOT_VALIDATED");

    assertEquals("MANUAL", verdictRow().get("source"));
    assertEquals(null, verdictRow().get("source_ref"));
  }

  @Test
  void theDatabaseRejectsACitationVerdictWithoutItsCitation() {
    verdictService.set(evaluationId, controlId, "VALIDATED");

    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE prisma.audit_control_verdicts SET source = 'CITATION', source_ref = NULL"
                    + " WHERE evaluation_id = ?",
                evaluationId));
  }

  // ---- C4b ----

  private CreateAuditObservationDto observation(String type, String text, String prefill) {
    return new CreateAuditObservationDto(
        evaluationId,
        controlId,
        null,
        type,
        text,
        null,
        null,
        new CitationSourceDto("politica.pdf", "Página 2", "texto citado", 0.71, prefill));
  }

  @Test
  void savingTheObservationFromACitationRecordsTheDifferenceAnonymously() {
    observationService.create(observation("NON_CONFORMITY", "texto reescrito", "texto precargado"));
    observationService.create(observation("OBSERVATION", "texto precargado", "texto precargado"));

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT outcome, final_type, prefill_text, final_text FROM"
                + " prisma.ai_citation_validation_feedback ORDER BY outcome");
    assertEquals(2, rows.size());
    assertEquals("EDITED", rows.get(0).get("outcome"));
    assertEquals("NON_CONFORMITY", rows.get(0).get("final_type"));
    assertEquals("texto reescrito", rows.get(0).get("final_text"));
    assertEquals("UNEDITED", rows.get(1).get("outcome"));
    // Registrar una observación no dictamina el control.
    assertEquals(0, count("SELECT count(*) FROM prisma.audit_control_verdicts"));
    // Anónimo: la tabla no tiene organización, evaluación ni usuario.
    assertEquals(
        0,
        count(
            "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                + " 'ai_citation_validation_feedback' AND (column_name LIKE '%organization%' OR"
                + " column_name LIKE '%evaluation%' OR column_name LIKE '%user%')"));
  }

  @Test
  void closingTheFormWithoutSavingLeavesNothing() {
    // Sin llamar a create(): no hay ningún registro.
    assertEquals(0, count("SELECT count(*) FROM prisma.ai_citation_validation_feedback"));
  }
}
