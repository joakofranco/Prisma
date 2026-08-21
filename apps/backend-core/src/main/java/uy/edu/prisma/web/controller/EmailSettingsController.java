package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.EmailSettingsService;
import uy.edu.prisma.web.dto.Dto.EmailSettingsDto;
import uy.edu.prisma.web.dto.Dto.UpdateEmailSettingsDto;

/**
 * Administración global del servidor SMTP (envío del email de "¿Olvidaste tu contraseña?" y demás
 * notificaciones de Keycloak). Sólo PRISMA_ADMIN -- son credenciales de un servidor de correo
 * compartido por toda la plataforma, no algo que un responsable de organización deba tocar.
 */
@RestController
@RequestMapping("/api/admin/email-settings")
@PreAuthorize("hasRole('PRISMA_ADMIN')")
public class EmailSettingsController {

  private final EmailSettingsService service;

  public EmailSettingsController(EmailSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<EmailSettingsDto> get() {
    return ResponseEntity.ok(service.get());
  }

  @PutMapping
  public ResponseEntity<EmailSettingsDto> update(@Valid @RequestBody UpdateEmailSettingsDto dto) {
    service.update(dto);
    return ResponseEntity.ok(service.get());
  }
}
