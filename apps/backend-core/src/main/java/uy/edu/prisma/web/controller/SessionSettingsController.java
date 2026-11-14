package uy.edu.prisma.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.SessionSettingsService;
import uy.edu.prisma.web.dto.Dto.SessionSettingsDto;

/**
 * Cierre de sesión por inactividad. Leerlo lo puede hacer cualquier usuario autenticado (el
 * frontend arma su temporizador con estos valores); cambiarlo, solo PRISMA_ADMIN.
 */
@RestController
@RequestMapping("/api/settings/session")
public class SessionSettingsController {

  private final SessionSettingsService service;

  public SessionSettingsController(SessionSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<SessionSettingsDto> get() {
    return ResponseEntity.ok(service.get());
  }

  @PutMapping
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<SessionSettingsDto> update(@RequestBody SessionSettingsDto dto) {
    return ResponseEntity.ok(service.update(dto));
  }
}
