package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.AuditVerdictService;
import uy.edu.prisma.web.dto.Dto.ControlVerdictDto;
import uy.edu.prisma.web.dto.Dto.SetControlVerdictDto;

/** Dictamen del auditor por control (RF-AUD-06). Mismos roles que las observaciones. */
@RestController
@RequestMapping("/api/audit/verdicts")
public class AuditVerdictController {

  private final AuditVerdictService service;

  public AuditVerdictController(AuditVerdictService service) {
    this.service = service;
  }

  @GetMapping("/{evaluationId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<List<ControlVerdictDto>> list(@PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.listByEvaluation(evaluationId));
  }

  @PutMapping("/{evaluationId}/controls/{controlId}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<ControlVerdictDto> set(
      @PathVariable UUID evaluationId,
      @PathVariable UUID controlId,
      @Valid @RequestBody SetControlVerdictDto dto) {
    return ResponseEntity.ok(service.set(evaluationId, controlId, dto.verdict()));
  }
}
