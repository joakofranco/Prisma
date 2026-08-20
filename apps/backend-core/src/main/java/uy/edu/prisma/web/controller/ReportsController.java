package uy.edu.prisma.web.controller;

import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.ReportService;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {

  private final ReportService service;

  public ReportsController(ReportService service) {
    this.service = service;
  }

  @GetMapping("/{id}/pdf")
  public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
    byte[] content = service.generatePdf(id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-madurez-" + id + ".pdf"))
        .contentType(MediaType.APPLICATION_PDF)
        .body(content);
  }

  @GetMapping("/{id}/excel")
  public ResponseEntity<byte[]> excel(@PathVariable UUID id) {
    byte[] content = service.generateExcel(id);
    return ResponseEntity.ok()
        .headers(fileHeaders("reporte-madurez-" + id + ".xlsx"))
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(content);
  }

  private HttpHeaders fileHeaders(String filename) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
    return headers;
  }
}
