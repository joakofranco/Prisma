package uy.edu.prisma.web.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uy.edu.prisma.application.EvaluationProgressService;
import uy.edu.prisma.application.MaturityTrendService;
import uy.edu.prisma.web.dto.Dto.EvaluationProgressDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendPointDto;

@ExtendWith(MockitoExtension.class)
class MaturityTrendControllerTest {

  @Mock private MaturityTrendService service;
  @Mock private EvaluationProgressService progressService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new MaturityTrendController(service, progressService))
            .build();
  }

  @Test
  void returnsTheTrendOfTheRequestedOrganization() throws Exception {
    UUID orgId = UUID.randomUUID();
    when(service.trend(orgId))
        .thenReturn(
            new MaturityTrendDto(
                orgId,
                "Intendencia de Rivera",
                List.of(
                    new MaturityTrendPointDto(
                        UUID.randomUUID(),
                        "Anual 2026",
                        "APPROVED",
                        false,
                        OffsetDateTime.parse("2026-10-28T10:00:00-03:00"),
                        OffsetDateTime.parse("2026-10-28T10:00:00-03:00"),
                        null,
                        "Catálogo completo",
                        142,
                        230,
                        61.7,
                        1.48,
                        1))));

    mvc.perform(get("/api/dashboard/maturity-trend").param("organizationId", orgId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organizationName").value("Intendencia de Rivera"))
        .andExpect(jsonPath("$.points[0].profileName").value("Catálogo completo"))
        .andExpect(jsonPath("$.points[0].provisional").value(false))
        .andExpect(jsonPath("$.points[0].compliancePercent").value(61.7))
        .andExpect(jsonPath("$.points[0].maturityAverage").value(1.48));
  }

  @Test
  void withoutOrganizationTheServiceDecides() throws Exception {
    when(service.trend(null)).thenReturn(new MaturityTrendDto(UUID.randomUUID(), "Propia", null));

    mvc.perform(get("/api/dashboard/maturity-trend"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points").isEmpty());
    verify(service).trend(null);
  }

  @Test
  void returnsTheWeeklyProgressOfAnEvaluation() throws Exception {
    UUID evaluationId = UUID.randomUUID();
    when(progressService.progress(evaluationId, EvaluationProgressService.Granularity.MONTH))
        .thenReturn(
            new EvaluationProgressDto(
                evaluationId,
                "EVA-1",
                "IN_PROGRESS",
                "En Curso",
                "Estándar",
                OffsetDateTime.parse("2026-06-02T10:00:00-03:00"),
                142,
                200,
                230,
                38,
                70,
                "MONTH",
                null,
                null));

    mvc.perform(
            get("/api/dashboard/evaluations/{id}/progress", evaluationId)
                .param("granularity", "MONTH"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.compliantControls").value(142))
        .andExpect(jsonPath("$.last30DaysDelta").value(38))
        .andExpect(jsonPath("$.granularity").value("MONTH"))
        .andExpect(jsonPath("$.periods").isEmpty());
  }
}
