package uy.edu.prisma.web.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import uy.edu.prisma.application.AiTraceService;
import uy.edu.prisma.domain.entity.AiTraceLink.ResourceType;
import uy.edu.prisma.web.dto.Dto.AiTraceDto;

/**
 * Trazas del modo investigación (C8): «Cómo razonó la IA». Todo es solo para PRISMA_ADMIN. Cada
 * consulta por recurso verifica el acceso a la organización del recurso enlazado; si el modo estaba
 * apagado cuando se generó, no hay traza y responde 404.
 */
@RestController
@PreAuthorize("hasRole('PRISMA_ADMIN')")
public class AiTraceController {

  private final AiTraceService service;

  public AiTraceController(AiTraceService service) {
    this.service = service;
  }

  @GetMapping("/api/ai/traces/{traceId}")
  public ResponseEntity<AiTraceDto> get(@PathVariable UUID traceId) {
    return ResponseEntity.ok(service.get(traceId));
  }

  @GetMapping("/api/improvement-plans/{planId}/ai-trace")
  public ResponseEntity<AiTraceDto> ofPlan(@PathVariable UUID planId) {
    return ResponseEntity.ok(service.forResource(ResourceType.IMPROVEMENT_PLAN, planId));
  }

  @GetMapping(
      "/api/evidence/evaluation/{evaluationId}/control/{controlId}/citations/{citationId}/ai-trace")
  public ResponseEntity<AiTraceDto> ofCitation(
      @PathVariable UUID evaluationId,
      @PathVariable UUID controlId,
      @PathVariable UUID citationId) {
    return ResponseEntity.ok(service.forResource(ResourceType.CITATION, citationId));
  }

  @GetMapping("/api/audit/observations/{id}/ai-trace")
  public ResponseEntity<AiTraceDto> ofObservation(@PathVariable UUID id) {
    return ResponseEntity.ok(service.forResource(ResourceType.OBSERVATION, id));
  }

  /** Exporta todas las trazas en JSONL (una por línea), sin organización ni usuario. */
  @GetMapping("/api/ai/traces/export")
  public ResponseEntity<StreamingResponseBody> export() {
    StreamingResponseBody body =
        out -> {
          try (var writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            service.exportJsonl(writer);
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        };
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ai-traces.jsonl\"")
        .contentType(MediaType.parseMediaType("application/x-ndjson"))
        .body(body);
  }
}
