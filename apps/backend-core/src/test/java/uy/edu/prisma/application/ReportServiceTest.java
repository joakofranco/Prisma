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
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private CurrentUserService currentUser;

  private ReportService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service = new ReportService(evalRepo, matRepo, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluacion 2026")
            .organization(Organization.builder().id(UUID.randomUUID()).name("Acme").build())
            .catalogVersion("5.0")
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
}
