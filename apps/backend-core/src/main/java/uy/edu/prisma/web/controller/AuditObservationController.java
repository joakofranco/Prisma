package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.AuditObservationService;
import uy.edu.prisma.web.dto.Dto.AuditObservationDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;
import uy.edu.prisma.web.dto.Dto.UpdateAuditObservationDto;

@RestController
@RequestMapping("/api/audit/observations")
public class AuditObservationController {

  private final AuditObservationService service;

  public AuditObservationController(AuditObservationService service) {
    this.service = service;
  }

  // Leen el administrador y el auditor, y la organización de la evaluación (responsable o
  // evaluador interno): el servicio le muestra a la organización las observaciones solo desde que
  // la evaluación vuelve (RETURNED, APPROVED o ARCHIVED). El VIEWER no las ve. Escribir sigue
  // siendo solo del administrador y el auditor, y solo con la evaluación en auditoría.
  @GetMapping("/{evaluationId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR','ORG_RESPONSIBLE','INTERNAL_EVALUATOR')")
  public ResponseEntity<List<AuditObservationDto>> listByEvaluation(
      @PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.listByEvaluation(evaluationId));
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<AuditObservationDto> create(
      @Valid @RequestBody CreateAuditObservationDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<AuditObservationDto> update(
      @PathVariable UUID id, @RequestBody UpdateAuditObservationDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }
}
