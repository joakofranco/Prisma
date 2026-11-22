package uy.edu.prisma.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.ComparisonPdfService;
import uy.edu.prisma.application.EvaluationComparisonService;
import uy.edu.prisma.application.ReportSealingService;
import uy.edu.prisma.web.dto.Dto.ComparisonCandidateDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;

/**
 * RF-VIZ-06 (PRI-320): comparativo entre dos evaluaciones de la misma organización, en pantalla y
 * en PDF. Actores del RF: Responsable de Organización y Auditor; también el Administrador. El
 * acceso a la organización lo valida el servicio (multitenant).
 */
@RestController
@RequestMapping("/api/evaluations/compare")
@PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE', 'AUDITOR')")
public class EvaluationComparisonController {

  private final EvaluationComparisonService service;
  private final ComparisonPdfService pdf;
  private final ReportSealingService sealing;

  public EvaluationComparisonController(
      EvaluationComparisonService service, ComparisonPdfService pdf, ReportSealingService sealing) {
    this.service = service;
    this.pdf = pdf;
    this.sealing = sealing;
  }

  /** Evaluaciones finalizadas que se pueden comparar (la más reciente primero). */
  @GetMapping("/candidates")
  public ResponseEntity<List<ComparisonCandidateDto>> candidates(
      @RequestParam(required = false) UUID organizationId) {
    return ResponseEntity.ok(service.candidates(organizationId));
  }

  @GetMapping
  public ResponseEntity<EvaluationComparisonDto> compare(
      @RequestParam UUID a, @RequestParam UUID b) {
    return ResponseEntity.ok(service.compare(a, b));
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> comparePdf(@RequestParam UUID a, @RequestParam UUID b) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentDisposition(
        ContentDisposition.attachment().filename("comparativo-evaluaciones.pdf").build());
    return ResponseEntity.ok()
        .headers(headers)
        .contentType(MediaType.APPLICATION_PDF)
        .body(sealing.seal(pdf.generate(a, b), "comparativo", a));
  }
}
