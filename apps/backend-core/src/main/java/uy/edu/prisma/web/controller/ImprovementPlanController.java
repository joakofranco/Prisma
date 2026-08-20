package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.ImprovementPlanService;
import uy.edu.prisma.web.dto.Dto.CreateImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.ImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsRequestDto;
import uy.edu.prisma.web.dto.Dto.SuggestedImprovementDto;
import uy.edu.prisma.web.dto.Dto.UpdateImprovementPlanDto;

@RestController
@RequestMapping("/api/improvement")
public class ImprovementPlanController {

  private final ImprovementPlanService service;

  public ImprovementPlanController(ImprovementPlanService service) {
    this.service = service;
  }

  @GetMapping("/{evaluationId}")
  public ResponseEntity<List<ImprovementPlanDto>> listByEvaluation(
      @PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.listByEvaluation(evaluationId));
  }

  @GetMapping("/{evaluationId}/suggestions")
  public ResponseEntity<List<SuggestedImprovementDto>> suggestions(
      @PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.suggest(evaluationId));
  }

  @PostMapping("/suggestions/tips")
  public ResponseEntity<RemediationTipsDto> suggestionTips(
      @Valid @RequestBody RemediationTipsRequestDto dto) {
    return ResponseEntity.ok(service.tips(dto));
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<ImprovementPlanDto> create(
      @Valid @RequestBody CreateImprovementPlanDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<ImprovementPlanDto> update(
      @PathVariable UUID id, @RequestBody UpdateImprovementPlanDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }
}
