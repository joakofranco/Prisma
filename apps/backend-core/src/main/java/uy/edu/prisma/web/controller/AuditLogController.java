package uy.edu.prisma.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.AuditLogService;
import uy.edu.prisma.web.dto.Dto.AuditLogDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

/**
 * Bitácora de acciones del sistema (quién hizo qué, cuándo y desde dónde). PRISMA_ADMIN ve las de
 * todos los tenants; ORG_RESPONSIBLE (el "administrador" de su propia organización, ver
 * CurrentUserService) sólo las de la suya -- AuditLogService.list acota la consulta según el rol,
 * este controller no recibe ni necesita un tenantId por parámetro.
 */
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

  private final AuditLogService service;

  public AuditLogController(AuditLogService service) {
    this.service = service;
  }

  // Ver el comentario en OrganizationController.list: page es 1-indexed en la API, se convierte
  // a 0-indexed recien al construir el Pageable.
  @GetMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<PaginatedDto<AuditLogDto>> list(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String action,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ResponseEntity.ok(service.list(search, action, Math.max(0, page - 1), pageSize));
  }
}
