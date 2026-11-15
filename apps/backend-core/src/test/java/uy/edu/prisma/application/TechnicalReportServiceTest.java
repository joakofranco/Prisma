package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import uy.edu.prisma.domain.entity.CatalogCategory;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogRequirement;
import uy.edu.prisma.domain.entity.CatalogRequirementSubcategory;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.web.dto.Dto.EvaluationAuditCycleEventDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TechnicalReportServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvaluationResponseRepository responseRepo;
  @Mock private AuditControlVerdictRepository verdictRepo;
  @Mock private AuditObservationRepository obsRepo;
  @Mock private EvidenceRepository evidenceRepo;
  @Mock private AuditLogService auditLog;
  @Mock private CurrentUserService currentUser;

  private TechnicalReportService service;
  private Evaluation eval;
  private CatalogSubcategory sub;
  private CatalogControl c1;
  private CatalogControl c2;
  private CatalogControl c3;
  private CatalogControl c4;
  private CatalogControl c5;

  private static final OffsetDateTime APPROVED_AT =
      OffsetDateTime.of(2026, 10, 28, 10, 0, 0, 0, ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    service =
        new TechnicalReportService(
            evalRepo,
            matRepo,
            controlRepo,
            responseRepo,
            verdictRepo,
            obsRepo,
            evidenceRepo,
            auditLog,
            currentUser);
    User auditor =
        User.builder().id(UUID.randomUUID()).firstName("Laura").lastName("Méndez").build();
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluación anual 2026")
            .organization(Organization.builder().id(UUID.randomUUID()).name("Acme").build())
            .catalogVersion("5.0")
            .status(Evaluation.Status.APPROVED)
            .approvedAt(APPROVED_AT)
            .approvedBy(auditor)
            .certificationSeal("abc123sello")
            .globalMaturity(1)
            .updatedAt(APPROVED_AT)
            .build();

    CatalogFunction function =
        CatalogFunction.builder().id(UUID.randomUUID()).name("Proteger").build();
    CatalogCategory category =
        CatalogCategory.builder()
            .id(UUID.randomUUID())
            .code("SO")
            .name("Seguridad de las operaciones")
            .function_(function)
            .build();
    sub =
        CatalogSubcategory.builder()
            .id(UUID.randomUUID())
            .code("SO.6")
            .name("Respaldos")
            .category(category)
            .build();
    CatalogRequirement requirement = CatalogRequirement.builder().id(UUID.randomUUID()).build();
    requirement.setSubcategoryLinks(
        List.of(
            CatalogRequirementSubcategory.builder()
                .id(UUID.randomUUID())
                .requirement(requirement)
                .subcategory(sub)
                .build()));
    c1 = control(requirement, "SO.6-1", "Respaldos periódicos", 1);
    c2 = control(requirement, "SO.6-2", "Respaldos en ubicación separada", 1);
    c3 = control(requirement, "SO.6-3", "Grado y retención de los respaldos", 2);
    c4 = control(requirement, "SO.6-4", "Prueba de restauración", 3);
    c5 = control(requirement, "SO.6-5", "Cifrado de los respaldos", 3);

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1, c2, c3, c4, c5));
    when(responseRepo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                response(c1, true, "Respaldo diario automatizado"),
                response(c2, true, null),
                response(c3, false, "La política está en borrador"),
                response(c4, true, null)));
    when(verdictRepo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                verdict(c1, AuditControlVerdict.Verdict.VALIDATED),
                verdict(c2, AuditControlVerdict.Verdict.NEEDS_MORE_EVIDENCE),
                verdict(c3, AuditControlVerdict.Verdict.NOT_VALIDATED),
                verdict(c4, AuditControlVerdict.Verdict.VALIDATED)));
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId()))
        .thenReturn(
            List.of(
                AuditObservation.builder()
                    .id(UUID.randomUUID())
                    .evaluation(eval)
                    .control(c3)
                    .type(AuditObservation.ObservationType.NON_CONFORMITY)
                    .status(AuditObservation.ObservationStatus.OPEN)
                    .description("No existe una politica de retencion aprobada")
                    .createdBy(auditor)
                    .createdAt(APPROVED_AT.minusDays(7))
                    .build(),
                AuditObservation.builder()
                    .id(UUID.randomUUID())
                    .evaluation(eval)
                    .subcategory(sub)
                    .type(AuditObservation.ObservationType.RECOMMENDATION)
                    .status(AuditObservation.ObservationStatus.RESOLVED)
                    .description("Agendar la prueba de restauracion anual")
                    .createdBy(auditor)
                    .createdAt(APPROVED_AT.minusDays(6))
                    .build()));
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()))
        .thenReturn(
            List.of(
                Evidence.builder()
                    .id(UUID.randomUUID())
                    .evaluation(eval)
                    .fileName("organigrama.pdf")
                    .fileHash("f".repeat(64))
                    .uploadedAt(APPROVED_AT.minusDays(20))
                    .build(),
                Evidence.builder()
                    .id(UUID.randomUUID())
                    .evaluation(eval)
                    .control(c1)
                    .fileName("politica-respaldos.pdf")
                    .fileHash("a".repeat(64))
                    .uploadedAt(APPROVED_AT.minusDays(10))
                    .build()));
    when(matRepo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                MaturityResult.builder()
                    .subcategoryId(sub.getId())
                    .subcategoryName("Respaldos")
                    .functionName("Proteger")
                    .categoryName("Seguridad de las operaciones")
                    .currentLevel(1)
                    .targetLevel(3)
                    .gap(2)
                    .build()));
    when(auditLog.getEvaluationAuditTrail(eval.getId()))
        .thenReturn(
            List.of(
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(),
                    "UPDATE_STATUS",
                    "APPROVED",
                    auditor.getId(),
                    "Laura Méndez",
                    APPROVED_AT,
                    1)));
    when(currentUser.currentUser())
        .thenReturn(Optional.of(User.builder().firstName("Ana").lastName("Pérez").build()));
  }

  private static CatalogControl control(
      CatalogRequirement requirement, String code, String description, int level) {
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .requirement(requirement)
        .code(code)
        .description(description)
        .targetLevel(level)
        .build();
  }

  private EvaluationResponse response(CatalogControl c, boolean compliant, String comment) {
    return EvaluationResponse.builder()
        .evaluation(eval)
        .control(c)
        .compliant(compliant)
        .observations(comment)
        .build();
  }

  private AuditControlVerdict verdict(CatalogControl c, AuditControlVerdict.Verdict v) {
    return AuditControlVerdict.builder().evaluation(eval).control(c).verdict(v).build();
  }

  private static String text(byte[] pdf) throws Exception {
    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    try {
      StringBuilder all = new StringBuilder();
      var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
      for (int p = 1; p <= reader.getNumberOfPages(); p++) {
        all.append(extractor.getTextFromPage(p)).append('\n');
      }
      // Los saltos de línea del maquetado no importan para buscar frases.
      return all.toString().replaceAll("\\s+", " ");
    } finally {
      reader.close();
    }
  }

  private static int pages(byte[] pdf) throws Exception {
    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    try {
      return reader.getNumberOfPages();
    } finally {
      reader.close();
    }
  }

  @Test
  void theReportIsAValidPdfWithEveryControlAndItsCompliance() throws Exception {
    byte[] pdf = service.generate(eval.getId());
    String text = text(pdf);

    assertTrue(new String(pdf, 0, 5).startsWith("%PDF-"));
    for (String code : List.of("SO.6-1", "SO.6-2", "SO.6-3", "SO.6-4", "SO.6-5")) {
      assertTrue(text.contains(code), code);
    }
    assertTrue(text.contains("CUMPLE"));
    assertTrue(text.contains("NO CUMPLE"));
    assertTrue(text.contains("SIN RESPUESTA"));
    assertTrue(text.contains("Respaldo diario automatizado"));
  }

  @Test
  void theTraceabilityShowsEachStatusChangeWithItsTimeCycleAndAuthor() throws Exception {
    OffsetDateTime sent = APPROVED_AT.minusHours(2);
    when(auditLog.getEvaluationAuditTrail(eval.getId()))
        .thenReturn(
            List.of(
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(), "UPDATE_STATUS", "IN_PROGRESS", null, "Ana Pérez", sent, 0),
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(),
                    "UPDATE_STATUS",
                    "READY_FOR_AUDIT",
                    null,
                    "Ana Pérez",
                    sent.plusMinutes(5),
                    1),
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(),
                    "UPDATE_STATUS",
                    "APPROVED",
                    null,
                    "Laura Méndez",
                    APPROVED_AT,
                    1),
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(), "CERTIFY", null, null, "Laura Méndez", APPROVED_AT, 1)));

    String text = text(service.generate(eval.getId()));

    assertFalse(text.contains("UPDATE_STATUS"));
    assertTrue(text.contains("Fecha y hora"));
    assertTrue(text.contains("Borrador › En Curso"), text);
    assertTrue(text.contains("En Curso › Lista para Auditoría"));
    assertTrue(text.contains("Lista para Auditoría › Aprobada"));
    assertTrue(text.contains("Sello de certificación emitido"));
    String approvedAt =
        APPROVED_AT
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    assertTrue(text.contains(approvedAt), approvedAt);
  }

  @Test
  void withoutStatusChangesTheTraceabilitySaysSo() throws Exception {
    when(auditLog.getEvaluationAuditTrail(eval.getId())).thenReturn(List.of());

    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("Sin cambios de estado registrados."));
  }

  @Test
  void itShowsTheAuditorVerdictsAndObservations() throws Exception {
    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("Validado"));
    assertTrue(text.contains("No validado"));
    assertTrue(text.contains("Más evidencia"));
    assertTrue(text.contains("Sin revisar"));
    assertTrue(text.contains("NO CONFORMIDAD"));
    assertTrue(text.contains("No existe una politica de retencion aprobada"));
    assertTrue(text.contains("Agendar la prueba de restauracion anual"));
  }

  @Test
  void evidencesAreReferencedFromTheControlAndListedInTheAnnex() throws Exception {
    String text = text(service.generate(eval.getId()));

    // E-01 es la más antigua (general), E-02 la del control SO.6-1.
    assertTrue(text.contains("organigrama.pdf (general, sin control)"), text);
    assertTrue(text.contains("politica-respaldos.pdf"));
    assertTrue(text.contains("E-02"));
    assertTrue(text.contains("aaaaaaaaaaaaaaaa"));
  }

  @Test
  void itExplainsWhyTheSubcategoryIsAtItsLevelAndWhatIsMissing() throws Exception {
    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("Por qué está en nivel 1"), text);
    assertTrue(text.contains("No alcanza el nivel 2 porque SO.6-3 no está cumplido"), text);
    assertTrue(text.contains("Para subir al nivel 2"), text);
    assertTrue(text.contains("6. Pendientes que frenan el nivel"));
  }

  @Test
  void theSummaryAndTraceabilityAreIncluded() throws Exception {
    byte[] pdf = service.generate(eval.getId());
    String text = text(pdf);

    // 3 de 5 controles cumplidos.
    assertTrue(text.contains("cumple el 60 %"), text);
    assertTrue(text.contains("Cómo leer este reporte"));
    assertTrue(text.contains("Nivel acumulativo"));
    assertTrue(text.contains("abc123sello"));
    assertTrue(text.contains("Laura Méndez"));
    assertTrue(text.contains("Ana Pérez"));
    assertTrue(text.contains("Página 1 de " + pages(pdf)), text);
  }

  @Test
  void anEvaluationWithoutEvidencesObservationsOrHistoryStillProducesTheReport() throws Exception {
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId())).thenReturn(List.of());
    when(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());
    when(auditLog.getEvaluationAuditTrail(eval.getId())).thenReturn(List.of());
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    eval.setCertificationSeal(null);
    eval.setApprovedBy(null);

    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("La evaluación no tiene evidencias adjuntas."), text);
    assertTrue(text.contains("Sin observaciones del auditor."));
    assertTrue(text.contains("Sin cambios de estado registrados."));
    assertTrue(text.contains("Sin sello"));
  }

  @Test
  void whenEverySubcategoryReachesItsTargetThereAreNoPendings() throws Exception {
    when(matRepo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                MaturityResult.builder()
                    .subcategoryId(sub.getId())
                    .subcategoryName("Respaldos")
                    .functionName("Proteger")
                    .categoryName("Seguridad de las operaciones")
                    .currentLevel(3)
                    .targetLevel(3)
                    .gap(0)
                    .build()));

    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("No hay pendientes"), text);
    assertTrue(text.contains("Todas las subcategorías alcanzan su nivel objetivo."));
  }

  @Test
  void anArchivedEvaluationThatWasApprovedAlsoHasItsTechnicalReport() throws Exception {
    eval.setStatus(Evaluation.Status.ARCHIVED);

    assertTrue(text(service.generate(eval.getId())).contains("Archivada"));
  }

  @Test
  void itIsRejectedUnlessTheEvaluationWasApproved() {
    for (Evaluation.Status status :
        List.of(Evaluation.Status.IN_AUDIT, Evaluation.Status.DRAFT, Evaluation.Status.ARCHIVED)) {
      eval.setStatus(status);
      eval.setApprovedAt(null);

      assertThrows(ConflictException.class, () -> service.generate(eval.getId()), status.name());
    }
    verify(controlRepo, never()).findByVersionOrdered(any());
  }

  @Test
  void anotherOrganizationCannotDownloadIt() {
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(eval.getOrganization().getId());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.generate(eval.getId()));
    verify(controlRepo, never()).findByVersionOrdered(any());
  }

  @Test
  void generatingItDoesNotModifyTheEvaluation() {
    service.generate(eval.getId());

    assertEquals(Evaluation.Status.APPROVED, eval.getStatus());
    verify(evalRepo, never()).save(any());
    verify(responseRepo, never()).save(any());
  }

  @Test
  void aSubcategoryAtItsTargetSaysSoAndHasNoNextStep() {
    var row =
        new TechnicalReportService.ControlRow(
            c1, TechnicalReportService.Compliance.MET, null, null, List.of());
    var block =
        new TechnicalReportService.SubcategoryBlock(
            "F", "C", "C1", "S", 1, 1, List.of(row), List.of());

    assertTrue(TechnicalReportService.whyText(block).startsWith("Alcanza su nivel objetivo"));
    assertNull(TechnicalReportService.nextStepText(block));
  }

  @Test
  void aPendingLevelOneControlKeepsTheSubcategoryAtZero() {
    var pending =
        new TechnicalReportService.ControlRow(
            c1, TechnicalReportService.Compliance.NO_ANSWER, null, null, List.of());
    var higher =
        new TechnicalReportService.ControlRow(
            c4, TechnicalReportService.Compliance.MET, null, null, List.of());
    var block =
        new TechnicalReportService.SubcategoryBlock(
            "F", "C", "C1", "S", 0, 3, List.of(pending, higher), List.of());

    String why = TechnicalReportService.whyText(block);
    assertTrue(why.startsWith("No alcanza el nivel 1 porque SO.6-1 no está cumplido"), why);
    assertTrue(why.contains("1 control de nivel superior cumplido"), why);
  }

  // ---- PDF/A-1b (RNF-12) ----

  private static String raw(byte[] pdf) {
    return new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  @Test
  void theTechnicalReportDeclaresItselfPdfA1bWithAnOutputIntent() {
    String text = raw(service.generate(eval.getId()));

    assertTrue(text.startsWith("%PDF-1.4"));
    assertTrue(text.contains("<pdfaid:part>1</pdfaid:part>"));
    assertTrue(text.contains("<pdfaid:conformance>B</pdfaid:conformance>"));
    assertTrue(text.contains("/Metadata"));
    assertTrue(text.contains("/OutputIntents"));
    assertTrue(text.contains("/GTS_PDFA1"));
    assertFalse(text.contains("/SMask"));
    assertFalse(text.matches("(?s).*/c[aA] +[0-9.]+.*"), "sin opacidad");
    assertFalse(text.contains("/Encrypt"));
  }

  @Test
  void theTechnicalReportEmbedsEveryFont() {
    String text = raw(service.generate(eval.getId()));

    assertTrue(text.contains("/FontFile2"), "fuente TrueType embebida");
    assertFalse(text.contains("/BaseFont /Helvetica"), "Helvetica sin embeber");
    assertFalse(text.contains("/BaseFont/Helvetica"), "Helvetica sin embeber");
    int descriptors = text.split("/Type ?/FontDescriptor", -1).length - 1;
    int files = text.split("/FontFile2", -1).length - 1;
    assertEquals(descriptors, files, "un FontFile2 por FontDescriptor");
  }

  /** Vuelca el reporte técnico para validarlo con veraPDF (junto con los de ReportServiceTest). */
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "pdfa.dumpDir", matches = ".+")
  @Test
  void dumpTechnicalReportForVeraPdfValidation() throws Exception {
    java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("pdfa.dumpDir"));
    java.nio.file.Files.createDirectories(dir);
    java.nio.file.Files.write(dir.resolve("reporte-tecnico.pdf"), service.generate(eval.getId()));
  }

  // ---- Madurez por categoría ----

  @Test
  void theTechnicalReportShowsMaturityByCategoryRightAfterMaturityByFunction() throws Exception {
    String text = text(service.generate(eval.getId()));

    int byFunction = text.indexOf("3. Madurez por función");
    int byCategory = text.indexOf("Madurez por categoría");
    int detail = text.indexOf("4. Detalle por subcategoría");
    assertTrue(byFunction >= 0 && byCategory > byFunction, "va después de la tabla por función");
    assertTrue(byCategory < detail, "y antes del detalle por subcategoría");
    // La tabla de siglas: sigla, categoría y función.
    assertTrue(text.contains("Sigla"), text);
    String section = text.substring(byCategory, detail);
    assertTrue(section.contains("SO Seguridad de las operaciones"), section);
    assertTrue(section.contains("Proteger"), section);
  }

  private CatalogControl controlInNewCategory(
      String functionName, String categoryCode, String categoryName) {
    CatalogFunction f = CatalogFunction.builder().id(UUID.randomUUID()).name(functionName).build();
    CatalogCategory cat =
        CatalogCategory.builder()
            .id(UUID.randomUUID())
            .code(categoryCode)
            .name(categoryName)
            .function_(f)
            .build();
    CatalogSubcategory s =
        CatalogSubcategory.builder()
            .id(UUID.randomUUID())
            .code(categoryCode + "-1")
            .name("Sub " + categoryCode)
            .category(cat)
            .build();
    CatalogRequirement req = CatalogRequirement.builder().id(UUID.randomUUID()).build();
    req.setSubcategoryLinks(
        List.of(
            CatalogRequirementSubcategory.builder()
                .id(UUID.randomUUID())
                .requirement(req)
                .subcategory(s)
                .build()));
    return control(req, categoryCode + "-1-1", "Control " + categoryCode, 1);
  }

  @Test
  void withThreeOrMoreCategoriesTheTechnicalReportDrawsTheRadar() throws Exception {
    var idAm = controlInNewCategory("Identificar", "ID.AM", "Gestión de activos");
    var gvOc = controlInNewCategory("Gobernar", "GV.OC", "Contexto organizacional");
    when(controlRepo.findByVersionOrdered("5.0"))
        .thenReturn(List.of(c1, c2, c3, c4, c5, idAm, gvOc));

    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("Escala de 0 a 4"), "radar");
    // Sin resultado de madurez guardado una subcategoría cuenta como nivel 0.
    assertTrue(text.contains("ID.AM 0.0") && text.contains("GV.OC 0.0"), text);
    assertFalse(text.contains("Nivel objetivo"), "sin línea de objetivo");
  }

  @Test
  void withFewerThanThreeCategoriesTheTechnicalReportShowsOnlyTheTable() throws Exception {
    String text = text(service.generate(eval.getId()));

    assertTrue(text.contains("Madurez por categoría"));
    assertFalse(text.contains("Escala de 0 a 4"), "sin radar");
  }
}
