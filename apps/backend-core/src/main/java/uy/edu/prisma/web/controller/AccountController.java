package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.AccountService;
import uy.edu.prisma.web.dto.Dto.ChangePasswordDto;
import uy.edu.prisma.web.dto.Dto.SessionEventDto;
import uy.edu.prisma.web.dto.Dto.UpdateProfileDto;

/**
 * Autoservicio sobre la cuenta del usuario autenticado. Sin {@code @PreAuthorize}: cualquier rol
 * puede operar acá, porque la identidad la resuelve el JWT (SecurityConfig ya exige
 * "anyRequest().authenticated()" por defecto), no un rol -- es "mi cuenta", no "una cuenta".
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @PutMapping("/profile")
  public ResponseEntity<Void> updateProfile(@Valid @RequestBody UpdateProfileDto dto) {
    service.updateProfile(dto);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/password")
  public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordDto dto) {
    service.changePassword(dto);
    return ResponseEntity.noContent().build();
  }

  // Ver el comentario en SessionEventDto: el login/logout lo maneja Keycloak, no backend-core --
  // esto es sólo el "aviso" que el frontend manda para que quede en la bitácora (AuditLog).
  @PostMapping("/session-event")
  public ResponseEntity<Void> recordSessionEvent(@Valid @RequestBody SessionEventDto dto) {
    service.recordSessionEvent(dto);
    return ResponseEntity.noContent().build();
  }
}
