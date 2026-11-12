package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.ImprovementPlanRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private AuditObservationRepository obsRepo;
  @Mock private EvidenceRepository evidenceRepo;
  @Mock private CurrentUserService currentUser;
  @Mock private AuditControlVerdictRepository verdictRepo;
  @Mock private ImprovementPlanRepository planRepo;

  private ReportService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service =
        new ReportService(
            evalRepo, matRepo, obsRepo, evidenceRepo, currentUser, verdictRepo, planRepo);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluacion 2026")
            .organization(Organization.builder().id(UUID.randomUUID()).name("Acme").build())
            .catalogVersion("5.0")
            .status(Evaluation.Status.APPROVED)
            .updatedAt(OffsetDateTime.now())
            .build();
  }

  private List<MaturityResult> results() {
    MaturityResult r =
        MaturityResult.builder()
            .functionName("Riesgos")
            .categoryName("Gobierno")
            .subcategoryName("Politica")
            .currentLevel(3)
            .targetLevel(4)
            .gap(1)
            .build();
    return List.of(r);
  }

  @Test
  void generatePdfProducesValidDocument() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    byte[] pdf = service.generatePdf(eval.getId());

    assertTrue(pdf.length > 100);
    String header = new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8);
    assertEquals("%PDF-", header);
  }

  @Test
  void generateExcelProducesWorkbook() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    byte[] xlsx = service.generateExcel(eval.getId());

    assertTrue(xlsx.length > 100);
    assertEquals('P', xlsx[0]);
    assertEquals('K', xlsx[1]);
  }

  @Test
  void generatePdfThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.generatePdf(id));
  }

  @Test
  void generateExcelThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.generateExcel(id));
  }

  @Test
  void generatePreliminaryPdfProducesValidDocument() {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId()))
        .thenReturn(
            List.of(
                AuditObservation.builder()
                    .type(AuditObservation.ObservationType.OBSERVATION)
                    .description("Falta política documentada")
                    .status(AuditObservation.ObservationStatus.OPEN)
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId())).thenReturn(List.of());

    byte[] pdf = service.generatePreliminaryPdf(eval.getId());

    assertTrue(pdf.length > 100);
    assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void generatePreliminaryExcelProducesWorkbook() {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId())).thenReturn(List.of());

    byte[] xlsx = service.generatePreliminaryExcel(eval.getId());

    assertTrue(xlsx.length > 100);
    assertEquals('P', xlsx[0]);
    assertEquals('K', xlsx[1]);
  }

  @Test
  void generatePreliminaryPdfThrowsWhenEvaluationNotInAudit() {
    eval.setStatus(Evaluation.Status.DRAFT);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(ConflictException.class, () -> service.generatePreliminaryPdf(eval.getId()));
  }

  @Test
  void generatePreliminaryExcelThrowsWhenEvaluationNotInAudit() {
    eval.setStatus(Evaluation.Status.APPROVED);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(ConflictException.class, () -> service.generatePreliminaryExcel(eval.getId()));
  }

  // ---- PRI-309: el reporte final y el ejecutivo solo con la evaluación aprobada ----

  private static final List<Evaluation.Status> NOT_APPROVED =
      List.of(
          Evaluation.Status.DRAFT,
          Evaluation.Status.IN_PROGRESS,
          Evaluation.Status.READY_FOR_AUDIT,
          Evaluation.Status.IN_AUDIT,
          Evaluation.Status.RETURNED,
          Evaluation.Status.ARCHIVED);

  @Test
  void finalAndExecutiveReportsAreRejectedUnlessTheEvaluationIsApproved() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    for (Evaluation.Status status : NOT_APPROVED) {
      eval.setStatus(status);
      for (var report :
          List.<java.util.function.Function<UUID, byte[]>>of(
              service::generatePdf, service::generateExcel, service::generateExecutivePdf)) {
        ConflictException error =
            assertThrows(ConflictException.class, () -> report.apply(eval.getId()), status.name());
        assertTrue(error.getMessage().contains("Aprobada"), error.getMessage());
      }
    }
    // Se rechaza antes de leer los resultados: no se calcula nada para un reporte que no va.
    verify(matRepo, never()).findByEvaluationId(any());
  }

  @Test
  void theRejectionMessageSaysTheCurrentStatus() {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    ConflictException error =
        assertThrows(ConflictException.class, () -> service.generatePdf(eval.getId()));

    assertTrue(error.getMessage().contains("En Auditoría"), error.getMessage());
  }

  @Test
  void anApprovedEvaluationStillGetsTheFinalReports() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    assertTrue(service.generatePdf(eval.getId()).length > 100);
    assertTrue(service.generateExcel(eval.getId()).length > 100);
    assertTrue(service.generateExecutivePdf(eval.getId()).length > 100);
  }

  @Test
  void thePreliminaryReportKeepsRequiringInAuditAndNotApproved() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId())).thenReturn(List.of());

    // Aprobada: el preliminar ya no corresponde.
    assertThrows(ConflictException.class, () -> service.generatePreliminaryPdf(eval.getId()));
    // En auditoría: el preliminar sí, y el final no.
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    assertTrue(service.generatePreliminaryPdf(eval.getId()).length > 100);
    assertThrows(ConflictException.class, () -> service.generatePdf(eval.getId()));
  }

  // ---- PRI-367: reportes de evaluaciones archivadas ----

  private void archivedAfterApproval() {
    eval.setStatus(Evaluation.Status.ARCHIVED);
    eval.setApprovedAt(OffsetDateTime.of(2025, 10, 15, 10, 0, 0, 0, java.time.ZoneOffset.UTC));
  }

  @Test
  void anArchivedEvaluationThatWasApprovedGetsTheFinalAndExecutiveReports() {
    archivedAfterApproval();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    assertTrue(service.generatePdf(eval.getId()).length > 100);
    assertTrue(service.generateExcel(eval.getId()).length > 100);
    assertTrue(service.generateExecutivePdf(eval.getId()).length > 100);
  }

  @Test
  void anArchivedEvaluationDiscardedWithoutApprovalHasNoReport() {
    eval.setStatus(Evaluation.Status.ARCHIVED);
    eval.setApprovedAt(null);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    ConflictException error =
        assertThrows(ConflictException.class, () -> service.generatePdf(eval.getId()));

    assertTrue(error.getMessage().contains("Archivada"), error.getMessage());
  }

  @Test
  void theReportsOfAnArchivedEvaluationShowWhenItWasApproved() throws Exception {
    archivedAfterApproval();
    withExecutiveData(results(), List.of());

    String finalReport = allText(service.generatePdf(eval.getId()));
    String executive = allText(service.generateExecutivePdf(eval.getId()));

    for (String text : List.of(finalReport, executive)) {
      assertTrue(text.contains("APROBADA"), text);
      assertTrue(text.contains("15/10/2025"), text);
      assertTrue(text.contains("Archivada"), text);
    }
  }

  @Test
  void generatingAReportDoesNotModifyTheEvaluation() {
    archivedAfterApproval();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    service.generatePdf(eval.getId());
    service.generateExcel(eval.getId());
    service.generateExecutivePdf(eval.getId());

    assertEquals(Evaluation.Status.ARCHIVED, eval.getStatus());
    verify(evalRepo, never()).save(any());
    verify(planRepo, never()).markOverdue();
  }

  // ---- PRI-309: reporte ejecutivo ----

  private static final String[] FUNCTIONS = {
    "Gobernar", "Identificar", "Proteger", "Detectar", "Responder", "Recuperar"
  };

  /** Resultados de {@code functions} funciones, con {@code perFunction} subcategorías cada una. */
  private List<MaturityResult> functionResults(int functions, int perFunction) {
    List<MaturityResult> out = new java.util.ArrayList<>();
    for (int f = 0; f < functions; f++) {
      UUID functionId = UUID.randomUUID();
      for (int i = 0; i < perFunction; i++) {
        out.add(
            MaturityResult.builder()
                .functionId(functionId)
                .functionName(FUNCTIONS[f])
                .categoryName("Categoria " + f)
                .subcategoryName("Subcategoria " + f + "." + i)
                .currentLevel(1 + (f + i) % 4)
                .targetLevel(4)
                .gap((f + i) % 3)
                .build());
      }
    }
    return out;
  }

  private ImprovementPlan plan(
      String action,
      ImprovementPlan.Priority priority,
      java.time.LocalDate due,
      ImprovementPlan.PlanStatus status,
      int createdMinutesAgo) {
    return ImprovementPlan.builder()
        .id(UUID.randomUUID())
        .evaluation(eval)
        .action(action)
        .responsible("Ana")
        .priority(priority)
        .status(status)
        .dueDate(due)
        .createdAt(OffsetDateTime.now().minusMinutes(createdMinutesAgo))
        .build();
  }

  private static int pageCount(byte[] pdf) throws Exception {
    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    try {
      return reader.getNumberOfPages();
    } finally {
      reader.close();
    }
  }

  private String allText(byte[] pdf) throws Exception {
    StringBuilder text = new StringBuilder();
    for (int p = 1; p <= pageCount(pdf); p++) {
      text.append(pdfPageText(pdf, p)).append('\n');
    }
    return text.toString();
  }

  private void withExecutiveData(List<MaturityResult> results, List<ImprovementPlan> plans) {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results);
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(plans);
  }

  @Test
  void executiveReportIsAValidPdfOfOneOrTwoPagesWithAllTheRequestedData() throws Exception {
    eval.setCommunityProfile(
        uy.edu.prisma.domain.entity.CommunityProfile.builder()
            .id(UUID.randomUUID())
            .name("Perfil Básico")
            .build());
    withExecutiveData(
        functionResults(6, 5),
        List.of(
            plan(
                "Definir la política de respaldo",
                ImprovementPlan.Priority.HIGH,
                java.time.LocalDate.of(2026, 12, 15),
                ImprovementPlan.PlanStatus.PENDING,
                1)));

    byte[] pdf = service.generateExecutivePdf(eval.getId());

    assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(pageCount(pdf) <= 2, "páginas: " + pageCount(pdf));
    String text = allText(pdf);
    // Los seis datos que pide el Jira.
    assertTrue(text.contains("Acme"), "organización");
    assertTrue(text.contains("Evaluacion 2026"), "evaluación");
    assertTrue(text.contains("Perfil Básico"), "perfil comunitario");
    assertTrue(text.contains("NIVEL DE MADUREZ GLOBAL"), "madurez global");
    assertTrue(text.contains("Madurez por Función"), "radar");
    assertTrue(text.contains("Principales Brechas"), "mayores brechas");
    assertTrue(text.contains("Definir la política de respaldo"), "acciones del plan");
    assertTrue(text.contains("Reporte Ejecutivo"));
  }

  @Test
  void theRadarHasOneAxisPerFunction() throws Exception {
    withExecutiveData(functionResults(6, 3), List.of());

    String text = allText(service.generateExecutivePdf(eval.getId()));

    for (String function : FUNCTIONS) {
      assertTrue(text.contains(function), "falta el eje de " + function);
    }
    assertTrue(text.contains("Nivel actual") && text.contains("Nivel objetivo"), "leyenda");
  }

  @Test
  void aSmallNumberOfFunctionsFallsBackToATableInsteadOfAShapelessRadar() throws Exception {
    withExecutiveData(functionResults(2, 3), List.of());

    String text = allText(service.generateExecutivePdf(eval.getId()));

    assertTrue(text.contains("Gobernar") && text.contains("Identificar"));
    assertFalse(text.contains("Nivel objetivo"), "sin leyenda de radar");
  }

  @Test
  void anEvaluationWithoutResultsStillProducesAnExecutiveReport() throws Exception {
    withExecutiveData(List.of(), List.of());

    byte[] pdf = service.generateExecutivePdf(eval.getId());

    assertTrue(pageCount(pdf) <= 2);
    assertTrue(allText(pdf).contains("Sin resultados de madurez"));
  }

  @Test
  void withoutAnImprovementPlanTheSectionSaysSoInsteadOfDisappearing() throws Exception {
    withExecutiveData(functionResults(6, 3), List.of());

    String text = allText(service.generateExecutivePdf(eval.getId()));

    assertTrue(text.contains("Acciones Principales del Plan de Mejora"));
    assertTrue(text.contains("Sin acciones registradas"));
  }

  @Test
  void withoutACommunityProfileItSaysTheWholeCatalogApplies() throws Exception {
    eval.setCommunityProfile(null);
    withExecutiveData(functionResults(6, 2), List.of());

    assertTrue(allText(service.generateExecutivePdf(eval.getId())).contains("Sin perfil"));
  }

  @Test
  void theFiveMainActionsAreChosenByPriorityThenDueDate() throws Exception {
    var high = ImprovementPlan.Priority.HIGH;
    var medium = ImprovementPlan.Priority.MEDIUM;
    var low = ImprovementPlan.Priority.LOW;
    var pending = ImprovementPlan.PlanStatus.PENDING;
    var due =
        (java.util.function.IntFunction<java.time.LocalDate>)
            d -> java.time.LocalDate.of(2026, 12, d);
    withExecutiveData(
        functionResults(6, 2),
        List.of(
            plan("ACCION-baja-temprana", low, due.apply(1), pending, 1),
            plan("ACCION-media-tarde", medium, due.apply(28), pending, 2),
            plan("ACCION-alta-tarde", high, due.apply(30), pending, 3),
            plan("ACCION-alta-temprana", high, due.apply(2), pending, 4),
            plan("ACCION-media-temprana", medium, due.apply(3), pending, 5),
            plan("ACCION-alta-sin-fecha", high, null, pending, 6),
            plan("ACCION-baja-tarde", low, due.apply(29), pending, 7)));

    String text = allText(service.generateExecutivePdf(eval.getId()));

    // Orden esperado: alta (con fecha, luego sin fecha), después media por fecha. Son 5.
    List<String> expected =
        List.of(
            "ACCION-alta-temprana",
            "ACCION-alta-tarde",
            "ACCION-alta-sin-fecha",
            "ACCION-media-temprana",
            "ACCION-media-tarde");
    int last = -1;
    for (String action : expected) {
      int at = text.indexOf(action);
      assertTrue(at > last, action + " fuera de orden o ausente");
      last = at;
    }
    assertFalse(text.contains("ACCION-baja-temprana"), "la sexta y la séptima no entran");
    assertFalse(text.contains("ACCION-baja-tarde"));
  }

  @Test
  void anOverdueActionIsShownAsOverdueAndACompletedOneAsCompleted() throws Exception {
    withExecutiveData(
        functionResults(6, 2),
        List.of(
            plan(
                "Vencida una",
                ImprovementPlan.Priority.HIGH,
                java.time.LocalDate.of(2026, 1, 5),
                ImprovementPlan.PlanStatus.OVERDUE,
                1),
            plan(
                "Hecha una",
                ImprovementPlan.Priority.MEDIUM,
                java.time.LocalDate.of(2026, 3, 5),
                ImprovementPlan.PlanStatus.COMPLETED,
                2)));

    String text = allText(service.generateExecutivePdf(eval.getId()));

    assertTrue(text.contains("Vencida"));
    assertTrue(text.contains("Realizado"));
    assertTrue(text.contains("05/01/2026"));
  }

  @Test
  void veryLongActionsDoNotPushTheReportPastTwoPages() throws Exception {
    String longText =
        "Implementar un procedimiento de gestión de accesos privilegiados. ".repeat(40);
    List<ImprovementPlan> plans = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      plans.add(
          plan(
              longText,
              ImprovementPlan.Priority.HIGH,
              java.time.LocalDate.of(2026, 12, 1 + i),
              ImprovementPlan.PlanStatus.PENDING,
              i));
    }
    withExecutiveData(functionResults(6, 10), plans);

    byte[] pdf = service.generateExecutivePdf(eval.getId());

    assertTrue(pageCount(pdf) <= 2, "páginas: " + pageCount(pdf));
    assertTrue(allText(pdf).contains("…"), "la acción larga se recorta");
  }

  @Test
  void everyPageOfTheExecutiveReportRepeatsTheInstitutionalHeader() throws Exception {
    withExecutiveData(functionResults(6, 10), List.of());

    byte[] pdf = service.generateExecutivePdf(eval.getId());

    for (int p = 1; p <= pageCount(pdf); p++) {
      String text = pdfPageText(pdf, p);
      assertTrue(text.contains("Acme"), "organización en la página " + p);
      assertTrue(text.contains("MCU 5.0"), "versión del MCU en la página " + p);
    }
  }

  @Test
  void theExecutiveReportOfAnotherOrganizationIsRejected() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    doThrow(new org.springframework.security.access.AccessDeniedException("otra organización"))
        .when(currentUser)
        .assertOrganizationAccess(any());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.generateExecutivePdf(eval.getId()));
    verify(planRepo, never()).findByEvaluationIdOrderByCreatedAtDesc(any());
  }

  @Test
  void executiveReportOfAMissingEvaluationIsNotFound() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.generateExecutivePdf(id));
  }

  // ---- PRI-311: metadatos de identidad en los reportes ----

  private List<MaturityResult> manyResults(int n) {
    List<MaturityResult> out = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(
          MaturityResult.builder()
              .functionName("Funcion " + i)
              .categoryName("Categoria " + i)
              .subcategoryName("Subcategoria " + i)
              .currentLevel(i % 5)
              .targetLevel(4)
              .gap(i % 3)
              .build());
    }
    return out;
  }

  private static String pdfPageText(byte[] pdf, int page) throws Exception {
    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    try {
      return new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(page);
    } finally {
      reader.close();
    }
  }

  private void withAuditor(String first, String last) {
    User auditor = User.builder().id(UUID.randomUUID()).firstName(first).lastName(last).build();
    AuditObservation obs =
        AuditObservation.builder().description("x").createdBy(auditor).evaluation(eval).build();
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(obs));
  }

  @Test
  void pdfRepeatsTheIdentityHeaderOnEveryPage() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(manyResults(120));

    byte[] pdf = service.generatePdf(eval.getId());

    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    int pages = reader.getNumberOfPages();
    reader.close();
    assertTrue(pages >= 3, "el reporte de prueba debe ocupar varias paginas, tiene " + pages);
    for (int p = 1; p <= pages; p++) {
      String text = pdfPageText(pdf, p);
      assertTrue(text.contains("Acme"), "organizacion en pagina " + p);
      assertTrue(text.contains("Evaluacion 2026"), "evaluacion en pagina " + p);
      assertTrue(text.contains("MCU 5.0"), "version del MCU en pagina " + p);
      assertTrue(text.contains("Página " + p), "pie con numero en pagina " + p);
    }
  }

  @Test
  void pdfInfoBoxShowsUpdatedAndGeneratedDates() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    String first = pdfPageText(service.generatePdf(eval.getId()), 1);

    assertTrue(first.contains("ACTUALIZADA"));
    assertTrue(first.contains("GENERADO"));
  }

  @Test
  void pdfShowsAuditorOnlyWhenTheEvaluationHasOne() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    String without = pdfPageText(service.generatePdf(eval.getId()), 1);
    assertFalse(without.contains("AUDITOR"));
    assertFalse(without.contains("Auditor:"));

    withAuditor("Ana", "Pérez");
    String with = pdfPageText(service.generatePdf(eval.getId()), 1);
    assertTrue(with.contains("AUDITOR"));
    assertTrue(with.contains("Ana Pérez"));
    assertTrue(with.contains("Auditor: Ana Pérez"));
  }

  @Test
  void auditorsAlsoComeFromControlVerdicts() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());
    User auditor = User.builder().id(UUID.randomUUID()).firstName("Luis").lastName("Gómez").build();
    when(verdictRepo.findByEvaluationId(eval.getId()))
        .thenReturn(List.of(AuditControlVerdict.builder().setBy(auditor).build()));

    assertTrue(pdfPageText(service.generatePdf(eval.getId()), 1).contains("Luis Gómez"));
  }

  @Test
  void excelSummaryListsAuditorOnlyWhenPresentAndSetsPrintHeader() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(results());

    try (var wb =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(service.generateExcel(eval.getId())))) {
      assertFalse(summaryLabels(wb).contains("Auditor"));
    }

    withAuditor("Ana", "Pérez");
    try (var wb =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(service.generateExcel(eval.getId())))) {
      assertTrue(summaryLabels(wb).contains("Auditor"));
      for (org.apache.poi.ss.usermodel.Sheet sheet : wb) {
        String header = sheet.getHeader().getLeft();
        assertTrue(header.contains("Acme"), sheet.getSheetName());
        assertTrue(header.contains("Evaluacion 2026"), sheet.getSheetName());
        assertTrue(header.contains("MCU 5.0"), sheet.getSheetName());
        assertTrue(header.contains("Ana Pérez"), sheet.getSheetName());
      }
    }
  }

  private static List<String> summaryLabels(org.apache.poi.xssf.usermodel.XSSFWorkbook wb) {
    List<String> labels = new java.util.ArrayList<>();
    for (org.apache.poi.ss.usermodel.Row row : wb.getSheet("Resumen")) {
      org.apache.poi.ss.usermodel.Cell cell = row.getCell(0);
      if (cell != null) {
        labels.add(cell.getStringCellValue());
      }
    }
    return labels;
  }

  private List<ImprovementPlan> exportPlans() {
    java.time.LocalDate today = java.time.LocalDate.now();
    return List.of(
        plan(
            "Revisar privilegios",
            ImprovementPlan.Priority.HIGH,
            today.plusDays(10),
            ImprovementPlan.PlanStatus.PENDING,
            1),
        plan(
            "Inventariar datos sensibles",
            ImprovementPlan.Priority.MEDIUM,
            today.plusDays(30),
            ImprovementPlan.PlanStatus.IN_PROGRESS,
            2),
        plan(
            "Registrar excepciones",
            ImprovementPlan.Priority.LOW,
            today.minusDays(5),
            ImprovementPlan.PlanStatus.PENDING,
            3),
        plan(
            "Capacitar al personal",
            ImprovementPlan.Priority.LOW,
            today.minusDays(20),
            ImprovementPlan.PlanStatus.COMPLETED,
            4));
  }

  @Test
  void improvementPlanPdfListsActionsAndSummary() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(exportPlans());

    byte[] pdf = service.generateImprovementPlanPdf(eval.getId());

    assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.UTF_8));
    String text = pdfPageText(pdf, 1);
    assertTrue(text.contains("Plan de Mejora"));
    assertTrue(text.contains("Revisar privilegios"));
    assertTrue(text.contains("Capacitar al personal"));
    assertTrue(text.contains("25 %"));
    assertTrue(text.contains("1 de 4 cerradas"));
    assertTrue(text.contains("1 acción vencida"));
  }

  @Test
  void improvementPlanPdfMarksOpenActionsPastDueAsOverdue() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(exportPlans());

    String text = pdfPageText(service.generateImprovementPlanPdf(eval.getId()), 1);

    assertTrue(text.contains("Vencida"));
  }

  @Test
  void improvementPlanExcelHasOneFilteredSheetWithAllColumns() throws Exception {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(exportPlans());

    try (var wb =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(service.generateImprovementPlanExcel(eval.getId())))) {
      assertEquals(1, wb.getNumberOfSheets());
      var sheet = wb.getSheet("Plan de mejora");
      assertEquals(4, sheet.getLastRowNum());
      assertEquals("Acción", sheet.getRow(0).getCell(0).getStringCellValue());
      assertEquals("Comentarios", sheet.getRow(0).getCell(6).getStringCellValue());
      assertNotNull(sheet.getCTWorksheet().getAutoFilter());
      assertEquals("Revisar privilegios", sheet.getRow(1).getCell(0).getStringCellValue());
      assertEquals("Alta", sheet.getRow(1).getCell(2).getStringCellValue());
    }
  }

  @Test
  void improvementPlanExcelShowsOverdueStatusAndControlCode() throws Exception {
    ImprovementPlan overdue =
        plan(
            "Vieja",
            ImprovementPlan.Priority.HIGH,
            java.time.LocalDate.now().minusDays(3),
            ImprovementPlan.PlanStatus.OVERDUE,
            1);
    overdue.setControl(
        uy.edu.prisma.domain.entity.CatalogControl.builder().code("MCU-AA-11").build());
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId()))
        .thenReturn(List.of(overdue));

    try (var wb =
        new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(service.generateImprovementPlanExcel(eval.getId())))) {
      var row = wb.getSheet("Plan de mejora").getRow(1);
      assertEquals("MCU-AA-11", row.getCell(1).getStringCellValue());
      assertEquals("Vencida", row.getCell(3).getStringCellValue());
    }
  }

  @Test
  void improvementPlanExportRejectsAnEmptyPlan() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.generateImprovementPlanPdf(eval.getId()));
    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.generateImprovementPlanExcel(eval.getId()));
  }

  @Test
  void improvementPlanExportThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.generateImprovementPlanPdf(id));
    assertThrows(ResourceNotFoundException.class, () -> service.generateImprovementPlanExcel(id));
  }

  @Test
  void improvementPlanExportDoesNotRequireAnApprovedEvaluation() {
    eval.setStatus(Evaluation.Status.IN_PROGRESS);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(planRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(exportPlans());

    assertTrue(service.generateImprovementPlanPdf(eval.getId()).length > 100);
  }
}
