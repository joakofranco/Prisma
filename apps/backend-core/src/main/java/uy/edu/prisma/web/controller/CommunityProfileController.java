package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.CommunityProfileService;
import uy.edu.prisma.web.dto.Dto.CommunityProfileDto;
import uy.edu.prisma.web.dto.Dto.CommunityProfileSummaryDto;
import uy.edu.prisma.web.dto.Dto.CreateCommunityProfileDto;

@RestController
@RequestMapping("/api/community-profiles")
public class CommunityProfileController {

  private final CommunityProfileService service;

  public CommunityProfileController(CommunityProfileService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<List<CommunityProfileSummaryDto>> list(
      @RequestParam(defaultValue = "5.0") String catalogVersion) {
    return ResponseEntity.ok(service.listByVersion(catalogVersion));
  }

  @GetMapping("/{id}")
  public ResponseEntity<CommunityProfileDto> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getById(id));
  }

  @PostMapping
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<CommunityProfileDto> create(
      @Valid @RequestBody CreateCommunityProfileDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<CommunityProfileDto> update(
      @PathVariable UUID id, @Valid @RequestBody CreateCommunityProfileDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
