package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.OrganizationService;
import uy.edu.prisma.web.dto.Dto.CreateOrganizationDto;
import uy.edu.prisma.web.dto.Dto.OrganizationDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

  private final OrganizationService service;

  public OrganizationController(OrganizationService service) {
    this.service = service;
  }

  // El parametro page es 1-indexed (asi lo envia el frontend); se resta 1 antes de construir el
  // Pageable, que en Spring Data es 0-indexed. Sin esta conversion, pedir la "pagina 1" desde el
  // frontend consulta en realidad la segunda pagina en la base de datos, que viene vacia si hay
  // pocos registros (bug: listas y selects que dependen de esta API quedan siempre vacios).
  @GetMapping
  public ResponseEntity<PaginatedDto<OrganizationDto>> list(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return ResponseEntity.ok(service.list(search, Math.max(0, page - 1), pageSize));
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrganizationDto> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getById(id));
  }

  @PostMapping
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<OrganizationDto> create(@Valid @RequestBody CreateOrganizationDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<OrganizationDto> update(
      @PathVariable UUID id, @Valid @RequestBody CreateOrganizationDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
