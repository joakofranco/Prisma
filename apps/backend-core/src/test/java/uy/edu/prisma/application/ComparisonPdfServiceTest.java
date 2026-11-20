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
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.web.dto.Dto.ComparedEvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;
import uy.edu.prisma.web.dto.Dto.FunctionDeltaDto;
import uy.edu.prisma.web.dto.Dto.SubcategoryDeltaDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComparisonPdfServiceTest {

  @Mock private EvaluationComparisonService comparison;
  @Mock private CurrentUserService currentUser;

  private ComparisonPdfService service;
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID govern = UUID.randomUUID();
  private final UUID respond = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new ComparisonPdfService(comparison, currentUser);
    when(currentUser.currentUser())
        .thenReturn(Optional.of(User.builder().firstName("Ana").lastName("Pérez").build()));
  }

  private EvaluationComparisonDto comparison(boolean sameProfile) {
    OffsetDateTime t = OffsetDateTime.parse("2025-06-02T10:00:00-03:00");
    return new EvaluationComparisonDto(
        UUID.randomUUID(),
        "Intendencia de Rivera",
        new ComparedEvaluationDto(a, "Anual 2025", "Estándar", t, 0, 0.95, 82, 234, 35.0),
        new ComparedEvaluationDto(
            b,
            "Anual 2026",
            sameProfile ? "Estándar" : "Básico",
            t.plusYears(1),
            1,
            1.48,
            142,
            234,
            60.7),
        sameProfile,
        1,
        1,
        1,
        0,
        1,
        "Entre ambas evaluaciones la madurez global subió 0,53 (de 0,95 a 1,48).",
        List.of(
            new FunctionDeltaDto(govern, "Gobernar", 1.5, 2.0, 0.5),
            new FunctionDeltaDto(respond, "Responder", 2.0, 1.0, -1.0)),
        List.of(
            new SubcategoryDeltaDto(
                UUID.randomUUID(), "GV.OC-01", "Contexto", govern, "Gobernar", "C", 1, 3, 2, 3),
            new SubcategoryDeltaDto(
                UUID.randomUUID(),
                "GV.PO-01",
                "Política",
                govern,
                "Gobernar",
                "C",
                null,
                1,
                null,
                2),
            new SubcategoryDeltaDto(
                UUID.randomUUID(), "GV.RM-02", "Riesgos", govern, "Gobernar", "C", 2, 2, 0, 3),
            new SubcategoryDeltaDto(
                UUID.randomUUID(),
                "RS.MA-01",
                "Incidentes",
                respond,
                "Responder",
                "C",
                2,
                1,
                -1,
                3)));
  }

  private static String text(byte[] pdf) throws Exception {
    com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(pdf);
    try {
      StringBuilder all = new StringBuilder();
      var extractor = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader);
      for (int p = 1; p <= reader.getNumberOfPages(); p++) {
        all.append(extractor.getTextFromPage(p)).append('\n');
      }
      return all.toString().replaceAll("\\s+", " ");
    } finally {
      reader.close();
    }
  }

  @Test
  void thePdfHasTheSummaryFunctionsSubcategoriesAndAnnex() throws Exception {
    when(comparison.compare(a, b)).thenReturn(comparison(true));

    byte[] pdf = service.generate(a, b);
    String text = text(pdf);

    assertTrue(new String(pdf, 0, 5).startsWith("%PDF-"));
    assertTrue(text.contains("Comparativo entre evaluaciones"));
    assertTrue(text.contains("Intendencia de Rivera"));
    assertTrue(text.contains("Anual 2025 · Estándar"));
    assertTrue(text.contains("por Ana Pérez"));
    assertTrue(text.contains("0,95 a 1,48"));
    assertTrue(text.contains("+0,53"));
    assertTrue(text.contains("82 a 142"));
    assertTrue(text.contains("+60"));
    assertTrue(text.contains("Lectura:"));
    assertTrue(text.contains("Gobernar"));
    assertTrue(text.contains("+0,50"));
    assertTrue(text.contains("-1,00"), text);
    assertTrue(text.contains("GV.OC-01"));
    assertTrue(text.contains("solo en B"));
    assertTrue(text.contains("3 · alcanzado"));
    assertTrue(text.contains("3 · falta 2"));
    assertTrue(text.contains("Anexo"));
    assertTrue(text.contains("GV.RM-02 (2)"));
    assertTrue(text.contains("Página 1 de"));
    assertFalse(text.contains("perfiles distintos"));
  }

  @Test
  void differentProfilesAreWarned() throws Exception {
    when(comparison.compare(a, b)).thenReturn(comparison(false));

    assertTrue(text(service.generate(a, b)).contains("perfiles distintos"));
  }

  @Test
  void signedNumbers() {
    assertEquals("+60", ComparisonPdfService.signed(60));
    assertEquals("-1", ComparisonPdfService.signed(-1));
    assertEquals("+0,53", ComparisonPdfService.signed(0.53));
    assertEquals("0", ComparisonPdfService.signed(0));
  }
}
