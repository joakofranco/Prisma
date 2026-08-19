package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.EvaluationService;
import uy.edu.prisma.web.dto.Dto.CreateEvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationResponseDto;
import uy.edu.prisma.web.dto.Dto.MaturityResultDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;
import uy.edu.prisma.web.dto.Dto.SaveResponseDto;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

  private final EvaluationService service;

  public EvaluationController(EvaluationService service) {
    this.service = service;
  }

  // Ver el comentario en OrganizationController.list: page es 1-indexed en la API, se convierte
  // a 0-indexed recien al construir el Pageable.
  @GetMapping
  public ResponseEntity<PaginatedDto<EvaluationDto>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID organizationId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return ResponseEntity.ok(service.list(status, organizationId, Math.max(0, page - 1), pageSize));
  }

  @GetMapping("/{id}")
  public ResponseEntity<EvaluationDto> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getById(id));
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<EvaluationDto> create(@Valid @RequestBody CreateEvaluationDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  // ORG_RESPONSIBLE e INTERNAL_EVALUATOR mueven DRAFT/IN_PROGRESS/RETURNED (autoevaluacion) y
  // AUDITOR mueve READY_FOR_AUDIT/IN_AUDIT (auditoria) -- ver los botones de "Acciones de Flujo"
  // en EvaluationDetailView.vue, que ya distinguen por rol que transicion ofrecer. AUDITOR faltaba
  // acá: el auditor podia ver la evaluacion y cargar observaciones, pero nunca podia iniciar la
  // auditoria ni aprobar/devolver -- el 403 lo paraba en seco pese a que el frontend le mostraba
  // los botones. INTERNAL_EVALUATOR tenía el problema inverso: el frontend SÍ le mostraba estos
  // botones pero el @PreAuthorize no lo incluía, así que el click siempre daba 403.
  //
  // El @PreAuthorize de acá solo exige ESTAR en uno de estos roles para entrar al endpoint;
  // EvaluationService.updateStatus es quien valida la transición puntual (tabla de estados
  // válidos) y qué rol puede dispararla -- antes ninguna de las dos cosas se chequeaba ahí, así
  // que cualquiera de estos roles podía saltar a cualquier estado del enum sin importar el actual
  // (p.ej. revertir una evaluación ARCHIVED a DRAFT).
  @PatchMapping("/{id}/status")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','INTERNAL_EVALUATOR','AUDITOR')")
  public ResponseEntity<EvaluationDto> updateStatus(
      @PathVariable UUID id, @RequestBody Map<String, String> body) {
    return ResponseEntity.ok(service.updateStatus(id, body.get("status")));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  // ---- Responses ----
  @GetMapping("/{id}/responses")
  public ResponseEntity<List<EvaluationResponseDto>> getResponses(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getResponses(id));
  }

  @PostMapping("/{id}/responses")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE')")
  public ResponseEntity<EvaluationResponseDto> saveResponse(
      @PathVariable UUID id, @Valid @RequestBody SaveResponseDto dto) {
    return ResponseEntity.ok(service.saveResponse(id, dto));
  }

  // ---- Results ----
  @GetMapping("/{id}/results")
  public ResponseEntity<List<MaturityResultDto>> getResults(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getResults(id));
  }

  @PostMapping("/{id}/calculate")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR')")
  public ResponseEntity<Void> calculateMaturity(@PathVariable UUID id) {
    service.calculateMaturity(id);
    return ResponseEntity.ok().build();
  }
}
