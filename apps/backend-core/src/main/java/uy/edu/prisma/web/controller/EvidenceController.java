package uy.edu.prisma.web.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.application.EvidenceService;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.EvidenceRulesDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

  private final EvidenceService service;

  public EvidenceController(EvidenceService service) {
    this.service = service;
  }

  /**
   * Reglas de carga vigentes (formatos y tamaño máximo), para que la pantalla las use antes de
   * enviar.
   */
  @GetMapping("/allowed-formats")
  public ResponseEntity<EvidenceRulesDto> allowedFormats() {
    return ResponseEntity.ok(
        new EvidenceRulesDto(service.allowedExtensions(), service.maxSizeBytes()));
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

  // RF-EVI-08: la restriccion fina (solo quien la subio o el responsable de la organizacion,
  // salvo PRISMA_ADMIN) vive en EvidenceService.delete -- este @PreAuthorize solo descarta roles
  // que nunca pueden borrar evidencia (p.ej. AUDITOR, VIEWER).
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }

  // Bajan archivos el Responsable de Organización, el Administrador y el Auditor (que revisa la
  // evidencia citada). Los reportes (PRI-313) mantienen su propia regla, sin Auditor. El backend
  // sirve el archivo (con JWT en cada descarga) en
  // lugar de entregar un enlace pre-firmado de RustFS.
  @GetMapping("/{id}/download")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')")
  public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
    EvidenceService.EvidenceFile file = service.download(id);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.parseMediaType(file.contentType()))
        .contentLength(file.size())
        .body(new InputStreamResource(file.content()));
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
