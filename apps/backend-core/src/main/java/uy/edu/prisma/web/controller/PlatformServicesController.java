package uy.edu.prisma.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.PlatformServicesService;
import uy.edu.prisma.config.VpnAccessGuard;
import uy.edu.prisma.web.dto.Dto.PlatformServiceDto;
import uy.edu.prisma.web.dto.Dto.ReplaceServicesHostDto;
import uy.edu.prisma.web.dto.Dto.UpdatePlatformServiceUrlDto;

/**
 * Lanzador de servicios de la infraestructura. Solo PRISMA_ADMIN. El listado se sirve desde
 * cualquier red (los enlaces apuntan a la VPN y solo abren conectado a ella); las operaciones que
 * modifican las URL verifican la red de origen (403 con código VPN_REQUIRED si no).
 */
@RestController
@RequestMapping("/api/admin/services")
@PreAuthorize("hasRole('PRISMA_ADMIN')")
public class PlatformServicesController {

  private final PlatformServicesService service;
  private final VpnAccessGuard vpnGuard;

  public PlatformServicesController(PlatformServicesService service, VpnAccessGuard vpnGuard) {
    this.service = service;
    this.vpnGuard = vpnGuard;
  }

  @GetMapping
  public ResponseEntity<List<PlatformServiceDto>> list() {
    return ResponseEntity.ok(service.list());
  }

  @PutMapping("/{id}")
  public ResponseEntity<PlatformServiceDto> updateUrl(
      @PathVariable UUID id,
      @RequestBody UpdatePlatformServiceUrlDto dto,
      HttpServletRequest request) {
    vpnGuard.require(request);
    return ResponseEntity.ok(service.updateUrl(id, dto.url()));
  }

  @PostMapping("/{id}/reset")
  public ResponseEntity<PlatformServiceDto> reset(
      @PathVariable UUID id, HttpServletRequest request) {
    vpnGuard.require(request);
    return ResponseEntity.ok(service.reset(id));
  }

  @PostMapping("/replace-host")
  public ResponseEntity<List<PlatformServiceDto>> replaceHost(
      @RequestBody ReplaceServicesHostDto dto, HttpServletRequest request) {
    vpnGuard.require(request);
    return ResponseEntity.ok(service.replaceHost(dto.host()));
  }
}
