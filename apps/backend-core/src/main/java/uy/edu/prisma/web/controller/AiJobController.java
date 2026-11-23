package uy.edu.prisma.web.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.AiJobService;
import uy.edu.prisma.web.dto.Dto.AiJobDto;
import uy.edu.prisma.web.dto.Dto.BatchCancelDto;

/**
 * Trabajos de IA asíncronos. Se encolan desde la función que los origina (hoy la guía de mejora:
 * {@code POST /api/improvement/suggestions/tips/async}); acá se consultan, se listan y se cancelan.
 * Mismos roles que la función de origen; la organización del trabajo se reverifica en cada llamada.
 */
@RestController
@RequestMapping("/api/ai/jobs")
@PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
public class AiJobController {

  private final AiJobService service;

  public AiJobController(AiJobService service) {
    this.service = service;
  }

  @GetMapping("/{id}")
  public ResponseEntity<AiJobDto> get(@PathVariable UUID id) {
    return ResponseEntity.ok(service.get(id));
  }

  /** Los trabajos de quien consulta (para el indicador de «Tareas de IA»). */
  @GetMapping
  public ResponseEntity<List<AiJobDto>> list(
      @RequestParam(name = "mine", defaultValue = "true") boolean mine,
      @RequestParam(name = "ids", required = false) List<UUID> ids) {
    // Con ids: estado de esos trabajos (avance de un lote), con la guía cuando ya terminó.
    if (ids != null && !ids.isEmpty()) {
      return ResponseEntity.ok(service.getMany(ids));
    }
    return ResponseEntity.ok(service.listMine());
  }

  /** Cancela de un lote lo que sigue en cola; lo que ya corre termina. */
  @DeleteMapping("/batches/{batchId}")
  public ResponseEntity<BatchCancelDto> cancelBatch(@PathVariable UUID batchId) {
    return ResponseEntity.ok(new BatchCancelDto(service.cancelBatch(batchId)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<AiJobDto> cancel(@PathVariable UUID id) {
    return ResponseEntity.ok(service.cancel(id));
  }
}
