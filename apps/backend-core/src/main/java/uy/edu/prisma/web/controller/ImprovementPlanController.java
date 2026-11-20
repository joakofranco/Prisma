package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.AiJobService;
import uy.edu.prisma.application.ImprovementPlanService;
import uy.edu.prisma.web.dto.Dto.AiJobAcceptedDto;
import uy.edu.prisma.web.dto.Dto.AiSuggestionFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CreateImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.GuideBatchAcceptedDto;
import uy.edu.prisma.web.dto.Dto.GuideBatchRequestDto;
import uy.edu.prisma.web.dto.Dto.GuideJobRequestDto;
import uy.edu.prisma.web.dto.Dto.ImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsRequestDto;
import uy.edu.prisma.web.dto.Dto.SuggestedImprovementDto;
import uy.edu.prisma.web.dto.Dto.UpdateImprovementPlanDto;

@RestController
@RequestMapping("/api/improvement")
public class ImprovementPlanController {

  private final ImprovementPlanService service;
  private final AiJobService aiJobService;

  public ImprovementPlanController(ImprovementPlanService service, AiJobService aiJobService) {
    this.service = service;
    this.aiJobService = aiJobService;
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
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<RemediationTipsDto> suggestionTips(
      @Valid @RequestBody RemediationTipsRequestDto dto) {
    return ResponseEntity.ok(service.tips(dto));
  }

  /**
   * Versión asíncrona de la guía: encola un trabajo y responde enseguida con 202. El resultado se
   * consulta en {@code GET /api/ai/jobs/{id}}. Convive con el endpoint síncrono.
   */
  @PostMapping("/suggestions/tips/async")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<AiJobAcceptedDto> suggestionTipsAsync(
      @Valid @RequestBody GuideJobRequestDto dto) {
    return ResponseEntity.accepted().body(aiJobService.enqueueGuide(dto));
  }

  /**
   * Lote de guías: una guía por control, en segundo plano y con prioridad de lote. Responde {@code
   * 202} con el id del lote y el trabajo de cada control; el avance se consulta con {@code GET
   * /api/ai/jobs?ids=…} y se cancela con {@code DELETE /api/ai/jobs/batches/{batchId}}.
   */
  @PostMapping("/suggestions/tips/batch")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<GuideBatchAcceptedDto> suggestionTipsBatch(
      @Valid @RequestBody GuideBatchRequestDto dto) {
    return ResponseEntity.accepted().body(aiJobService.enqueueGuideBatch(dto));
  }

  @PostMapping("/suggestions/feedback")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<Void> suggestionFeedback(
      @Valid @RequestBody AiSuggestionFeedbackBatchDto dto) {
    service.recordFeedback(dto);
    return ResponseEntity.noContent().build();
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
      @PathVariable UUID id, @Valid @RequestBody UpdateImprovementPlanDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }
}
