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

  @GetMapping("/{evaluationId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
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
