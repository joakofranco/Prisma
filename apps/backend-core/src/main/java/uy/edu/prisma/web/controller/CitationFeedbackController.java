package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.AiCitationFeedbackService;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackResultDto;

/**
 * PRI-359: el auditor acepta o rechaza las citas que propone la búsqueda asistida. Aceptar una cita
 * valida el control (C4a): la respuesta informa qué pasó con el dictamen de cada control. {@code
 * 409 VERDICT_OVERRIDE_REQUIRED} si habría que pisar un dictamen puesto a mano sin confirmación.
 */
@RestController
@RequestMapping("/api/evidence/citations")
public class CitationFeedbackController {

  private final AiCitationFeedbackService service;

  public CitationFeedbackController(AiCitationFeedbackService service) {
    this.service = service;
  }

  @PostMapping("/feedback")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<CitationFeedbackResultDto> feedback(
      @Valid @RequestBody CitationFeedbackBatchDto dto) {
    return ResponseEntity.ok(service.record(dto));
  }
}
