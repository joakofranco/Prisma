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

/** PRI-359: el auditor acepta o rechaza las citas que propone la búsqueda asistida. */
@RestController
@RequestMapping("/api/evidence/citations")
public class CitationFeedbackController {

  private final AiCitationFeedbackService service;

  public CitationFeedbackController(AiCitationFeedbackService service) {
    this.service = service;
  }

  @PostMapping("/feedback")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<Void> feedback(@Valid @RequestBody CitationFeedbackBatchDto dto) {
    service.record(dto);
    return ResponseEntity.noContent().build();
  }
}
