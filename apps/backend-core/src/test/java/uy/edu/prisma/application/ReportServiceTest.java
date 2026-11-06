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
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
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

  private ReportService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service = new ReportService(evalRepo, matRepo, obsRepo, evidenceRepo, currentUser, verdictRepo);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluacion 2026")
            .organization(Organization.builder().id(UUID.randomUUID()).name("Acme").build())
            .catalogVersion("5.0")
            .status(Evaluation.Status.IN_AUDIT)
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
}
