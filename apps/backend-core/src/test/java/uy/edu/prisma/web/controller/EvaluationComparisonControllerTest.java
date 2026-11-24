package uy.edu.prisma.web.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uy.edu.prisma.application.ComparisonPdfService;
import uy.edu.prisma.application.EvaluationComparisonService;
import uy.edu.prisma.application.ReportSealingService;
import uy.edu.prisma.web.dto.Dto.ComparisonCandidateDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;

@ExtendWith(MockitoExtension.class)
class EvaluationComparisonControllerTest {

  @Mock private EvaluationComparisonService service;
  @Mock private ComparisonPdfService pdf;
  @Mock private ReportSealingService sealing;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new EvaluationComparisonController(service, pdf, sealing))
            .build();
  }

  @Test
  void listsTheCandidates() throws Exception {
    UUID org = UUID.randomUUID();
    when(service.candidates(org))
        .thenReturn(
            List.of(
                new ComparisonCandidateDto(UUID.randomUUID(), "Anual 2026", "Estándar", null, 1)));

    mvc.perform(get("/api/evaluations/compare/candidates").param("organizationId", org.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Anual 2026"));
  }

  @Test
  void comparesTwoEvaluations() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(service.compare(a, b))
        .thenReturn(
            new EvaluationComparisonDto(
                UUID.randomUUID(), "Org", null, null, true, 3, 1, 2, 0, 0, "Lectura", null, null));

    mvc.perform(get("/api/evaluations/compare").param("a", a.toString()).param("b", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subcategoriesUp").value(3))
        .andExpect(jsonPath("$.reading").value("Lectura"));
  }

  @Test
  void downloadsThePdf() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    byte[] generated = new byte[] {37, 80, 68, 70};
    byte[] sealed = new byte[] {37, 80, 68, 70, 1, 2, 3};
    when(pdf.generate(a, b)).thenReturn(generated);
    when(sealing.seal(generated, "comparativo", a)).thenReturn(sealed);

    mvc.perform(
            get("/api/evaluations/compare/pdf").param("a", a.toString()).param("b", b.toString()))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"))
        .andExpect(content().bytes(sealed))
        .andExpect(
            header()
                .string(
                    "Content-Disposition", org.hamcrest.Matchers.containsString("comparativo")));
  }
}
