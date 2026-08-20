package uy.edu.prisma.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.application.EvidenceService;
import uy.edu.prisma.web.dto.Dto.DownloadUrlDto;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

  private final EvidenceService service;

  public EvidenceController(EvidenceService service) {
    this.service = service;
  }

  @GetMapping("/evaluation/{evaluationId}")
  public ResponseEntity<List<EvidenceDto>> listByEvaluation(@PathVariable UUID evaluationId) {
    return ResponseEntity.ok(service.listByEvaluation(evaluationId));
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE')")
  public ResponseEntity<UploadEvidenceResponseDto> upload(
      @RequestParam("evaluationId") UUID evaluationId,
      @RequestParam(value = "controlId", required = false) UUID controlId,
      @RequestParam(value = "description", required = false) String description,
      @RequestPart("file") MultipartFile file) {
    return ResponseEntity.ok(service.upload(evaluationId, controlId, description, file));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/download")
  public ResponseEntity<DownloadUrlDto> download(@PathVariable UUID id) {
    return ResponseEntity.ok(service.generateDownloadUrl(id));
  }

  /** Reintenta indexar una evidencia en el asistente RAG (p.ej. si falló al subirla). */
  @PostMapping("/{id}/index")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE')")
  public ResponseEntity<EvidenceDto> reindex(@PathVariable UUID id) {
    return ResponseEntity.ok(service.reindex(id));
  }

  /**
   * Fragmentos de evidencia (de la organización de la evaluación, nada más) relevantes para un
   * control: documento, ubicación (página/fragmento) y texto, para que el auditor los revise y
   * registre su propia Observación de Auditoría.
   */
  @GetMapping("/evaluation/{evaluationId}/control/{controlId}/citations")
  public ResponseEntity<List<EvidenceCitationDto>> citations(
      @PathVariable UUID evaluationId, @PathVariable UUID controlId) {
    return ResponseEntity.ok(service.getCitations(evaluationId, controlId));
  }
}
