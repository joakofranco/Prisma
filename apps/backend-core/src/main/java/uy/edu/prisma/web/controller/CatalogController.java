package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.CatalogService;
import uy.edu.prisma.web.dto.Dto.CatalogImportDto;
import uy.edu.prisma.web.dto.Dto.CatalogVersionDto;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }

  @GetMapping("/versions")
  public ResponseEntity<Map<String, Object>> versions() {
    List<CatalogVersionDto> vers = service.listVersions();
    return ResponseEntity.ok(
        Map.of("versions", vers.stream().map(CatalogVersionDto::version).toList()));
  }

  @GetMapping("/{version}")
  public ResponseEntity<Map<String, Object>> getByVersion(
      @PathVariable String version, @RequestParam(required = false) UUID profileId) {
    return ResponseEntity.ok(service.getByVersion(version, profileId));
  }

  // Da de alta una versión nueva completa (JSON subido, CSV parseado o armado a mano en el
  // formulario: el frontend siempre lo normaliza al mismo árbol antes de mandarlo acá). Solo
  // PRISMA_ADMIN -- afecta a toda la plataforma, no a una organización puntual.
  @PostMapping("/import")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<CatalogVersionDto> importCatalog(@Valid @RequestBody CatalogImportDto dto) {
    return ResponseEntity.ok(service.importCatalog(dto));
  }

  @DeleteMapping("/{version}")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<Void> deleteVersion(@PathVariable String version) {
    service.deleteVersion(version);
    return ResponseEntity.noContent().build();
  }
}
