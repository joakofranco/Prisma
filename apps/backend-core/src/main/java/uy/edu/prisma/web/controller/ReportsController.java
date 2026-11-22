package uy.edu.prisma.web.controller;

import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.application.ReportSealingService;
import uy.edu.prisma.application.ReportService;
import uy.edu.prisma.application.TechnicalReportService;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.web.dto.Dto.ReportVerificationDto;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {

  private final ReportService service;
  private final TechnicalReportService technical;
  private final ReportSealingService sealing;

  public ReportsController(
      ReportService service, TechnicalReportService technical, ReportSealingService sealing) {
    this.service = service;
    this.technical = technical;
    this.sealing = sealing;
  }

  // Reporte técnico detallado (RF-REP-02, PRI-310): control por control, con veredictos,
  // observaciones y evidencias. Mismos roles y estados que el reporte final.
  @GetMapping("/{id}/technical/pdf")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<byte[]> technicalPdf(@PathVariable UUID id) {
    byte[] content = sealing.seal(technical.generate(id), "técnico", id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-tecnico-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  // Solo el Administrador y el Responsable de Organización descargan reportes (PRI-313); el
  // Auditor queda fuera. assertOrganizationAccess en ReportService sigue acotando a su
  // organización.
  @GetMapping("/{id}/pdf")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
    byte[] content = sealing.seal(service.generatePdf(id), "de madurez", id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-madurez-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  @GetMapping("/{id}/excel")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<byte[]> excel(@PathVariable UUID id) {
    byte[] content = service.generateExcel(id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-madurez-" + id + ".xlsx"))
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(content);
  }

  // Reporte ejecutivo gerencial (RF-REP-01): de una a dos páginas, solo con la evaluación aprobada.
  // Mismos roles que el PDF final: el Auditor no baja reportes finales.
  @GetMapping("/{id}/executive/pdf")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')")
  public ResponseEntity<byte[]> executivePdf(@PathVariable UUID id) {
    byte[] content = sealing.seal(service.generateExecutivePdf(id), "ejecutivo", id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-ejecutivo-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  @GetMapping("/{id}/preliminary/pdf")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<byte[]> preliminaryPdf(@PathVariable UUID id) {
    byte[] content = sealing.seal(service.generatePreliminaryPdf(id), "preliminar", id);
    return ResponseEntity.ok()
        .headers(fileHeaders("informe-preliminar-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  @GetMapping("/{id}/preliminary/excel")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','AUDITOR')")
  public ResponseEntity<byte[]> preliminaryExcel(@PathVariable UUID id) {
    byte[] content = service.generatePreliminaryExcel(id);
    return ResponseEntity.ok()
        .headers(fileHeaders("informe-preliminar-" + id + ".xlsx"))
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(content);
  }

  @GetMapping("/{id}/improvement-plan/pdf")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')")
  public ResponseEntity<byte[]> improvementPlanPdf(@PathVariable UUID id) {
    byte[] content = sealing.seal(service.generateImprovementPlanPdf(id), "del plan de mejora", id);
    return ResponseEntity.ok()
        .headers(fileHeaders("plan-mejora-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  @GetMapping("/{id}/improvement-plan/excel")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')")
  public ResponseEntity<byte[]> improvementPlanExcel(@PathVariable UUID id) {
    byte[] content = service.generateImprovementPlanExcel(id);
    return ResponseEntity.ok()
        .headers(fileHeaders("plan-mejora-" + id + ".xlsx"))
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(content);
  }

  private static final long MAX_VERIFY_BYTES = 50L * 1024 * 1024;

  @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')")
  public ReportVerificationDto verify(@RequestParam("file") MultipartFile file)
      throws java.io.IOException {
    if (file.isEmpty()) {
      throw new InvalidRequestException("Seleccioná el PDF a verificar", "file");
    }
    if (file.getSize() > MAX_VERIFY_BYTES) {
      throw new InvalidRequestException("El PDF supera los 50 MB", "file");
    }
    return sealing.verify(file.getBytes());
  }

  private HttpHeaders fileHeaders(String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
    return headers;
  }
}
