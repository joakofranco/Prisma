package uy.edu.prisma.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.EvaluationFlagService;
import uy.edu.prisma.web.dto.Dto.ControlFlagDto;

/** Marca «para revisar» por control (PRI-352). */
@RestController
@RequestMapping("/api/evaluations/{evaluationId}/flags")
public class EvaluationFlagController {

  private final EvaluationFlagService service;

  public EvaluationFlagController(EvaluationFlagService service) {
    this.service = service;
  }

  // Las ven todos los roles que acceden a la evaluación (el servicio acota por organización).
  @GetMapping
  public ResponseEntity<List<ControlFlagDto>> list(@PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.list(evaluationId));
  }

  // Marcan y desmarcan los roles que trabajan la evaluación: quienes la responden y el auditor.
  @PutMapping("/{controlId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE','AUDITOR')")
  public ResponseEntity<Void> flag(@PathVariable UUID evaluationId, @PathVariable UUID controlId) {
    service.setFlagged(evaluationId, controlId, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{controlId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE','AUDITOR')")
  public ResponseEntity<Void> unflag(
      @PathVariable UUID evaluationId, @PathVariable UUID controlId) {
    service.setFlagged(evaluationId, controlId, false);
    return ResponseEntity.noContent().build();
  }
}
