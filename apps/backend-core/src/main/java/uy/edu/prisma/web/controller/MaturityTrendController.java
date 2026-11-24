package uy.edu.prisma.web.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.EvaluationProgressService;
import uy.edu.prisma.application.MaturityTrendService;
import uy.edu.prisma.web.dto.Dto.EvaluationProgressDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendDto;

/**
 * RF-VIZ-07 (PRI-321): tendencia histórica de la madurez para el Panel. Los actores del RF son el
 * Responsable de Organización y el Administrador; {@code organizationId} solo lo usa el
 * administrador (el responsable ve siempre la suya, ver MaturityTrendService).
 */
@RestController
@RequestMapping("/api/dashboard")
public class MaturityTrendController {

  private final MaturityTrendService service;
  private final EvaluationProgressService progressService;

  public MaturityTrendController(
      MaturityTrendService service, EvaluationProgressService progressService) {
    this.service = service;
    this.progressService = progressService;
  }

  @GetMapping("/maturity-trend")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<MaturityTrendDto> trend(
      @RequestParam(required = false) UUID organizationId) {
    return ResponseEntity.ok(service.trend(organizationId));
  }

  /**
   * Avance de una evaluación (también en curso o en auditoría) por día, semana o mes; {@code AUTO}
   * elige la escala según lo que dura su historia.
   */
  @GetMapping("/evaluations/{evaluationId}/progress")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<EvaluationProgressDto> progress(
      @PathVariable UUID evaluationId,
      @RequestParam(defaultValue = "AUTO") EvaluationProgressService.Granularity granularity) {
    return ResponseEntity.ok(progressService.progress(evaluationId, granularity));
  }
}
