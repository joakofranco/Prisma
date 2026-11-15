package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.application.ControlGuideService;
import uy.edu.prisma.web.dto.Dto.ControlGuideImportResultDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideItemDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideSummaryDto;
import uy.edu.prisma.web.dto.Dto.DeleteControlGuidesDto;
import uy.edu.prisma.web.dto.Dto.DeleteControlGuidesResultDto;
import uy.edu.prisma.web.dto.Dto.SaveControlGuideDto;

@RestController
@RequestMapping("/api/catalog")
@PreAuthorize("hasRole('PRISMA_ADMIN')")
public class ControlGuideController {

  private final ControlGuideService service;

  public ControlGuideController(ControlGuideService service) {
    this.service = service;
  }

  @GetMapping("/{version}/guide")
  public ResponseEntity<ControlGuideSummaryDto> summary(@PathVariable String version) {
    return ResponseEntity.ok(service.summary(version));
  }

  @GetMapping("/{version}/guide/items")
  public ResponseEntity<List<ControlGuideItemDto>> list(@PathVariable String version) {
    return ResponseEntity.ok(service.list(version));
  }

  @GetMapping("/{version}/guide/export")
  public ResponseEntity<byte[]> export(@PathVariable String version) {
    byte[] csv = service.exportCsv(version).getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("guia-controles-" + version + ".csv", StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(csv);
  }

  @PostMapping(value = "/{version}/guide", consumes = "multipart/form-data")
  public ResponseEntity<ControlGuideImportResultDto> importGuide(
      @PathVariable String version, @RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(service.importGuide(version, file));
  }

  @PutMapping("/controls/{controlId}/guide")
  public ResponseEntity<ControlGuideItemDto> save(
      @PathVariable UUID controlId, @Valid @RequestBody SaveControlGuideDto dto) {
    return ResponseEntity.ok(service.save(controlId, dto));
  }

  @PostMapping("/guide/delete")
  public ResponseEntity<DeleteControlGuidesResultDto> delete(
      @Valid @RequestBody DeleteControlGuidesDto dto) {
    return ResponseEntity.ok(new DeleteControlGuidesResultDto(service.delete(dto.controlIds())));
  }
}
