package uy.edu.prisma.web.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.UserService;
import uy.edu.prisma.web.dto.Dto.CreateUserDto;
import uy.edu.prisma.web.dto.Dto.InvitationAvailabilityDto;
import uy.edu.prisma.web.dto.Dto.InviteUserDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;
import uy.edu.prisma.web.dto.Dto.UnlockUserResultDto;
import uy.edu.prisma.web.dto.Dto.UserDto;

@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserService service;

  public UserController(UserService service) {
    this.service = service;
  }

  // Ver el comentario en OrganizationController.list: page es 1-indexed en la API, se convierte
  // a 0-indexed recien al construir el Pageable.
  @GetMapping
  public ResponseEntity<PaginatedDto<UserDto>> list(
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return ResponseEntity.ok(service.list(search, Math.max(0, page - 1), pageSize));
  }

  @GetMapping("/{id}")
  public ResponseEntity<UserDto> getById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getById(id));
  }

  // ORG_RESPONSIBLE es el "administrador" de su propia organización: puede dar de alta,
  // editar y borrar usuarios, pero UserService acota cada operación a su propio tenant y le
  // impide otorgar el rol global PRISMA_ADMIN (ver los comentarios ahí). PRISMA_ADMIN sigue
  // siendo el único rol sin esa restricción -- administra a través de todos los tenants.
  @PostMapping
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserDto dto) {
    return ResponseEntity.ok(service.create(dto));
  }

  /**
   * RF-USR-05 (PRI-240): invita a un evaluador interno por correo; crea su contraseña desde el
   * enlace. UserService fija el rol y, para ORG_RESPONSIBLE, su propia organización.
   */
  @PostMapping("/invitations")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> invite(@Valid @RequestBody InviteUserDto dto) {
    return ResponseEntity.ok(service.invite(dto));
  }

  /**
   * Si hay servidor de correo para invitar. El responsable no puede leer la configuración SMTP (es
   * de PRISMA_ADMIN): esto solo le dice si se puede, para avisarle antes de completar el
   * formulario.
   */
  @GetMapping("/invitations/availability")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<InvitationAvailabilityDto> invitationAvailability() {
    return ResponseEntity.ok(new InvitationAvailabilityDto(service.canSendInvitations()));
  }

  /** Reenvía la invitación de alguien que todavía no ingresó. */
  @PostMapping("/{id}/invitation/resend")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> resendInvitation(@PathVariable UUID id) {
    return ResponseEntity.ok(service.resendInvitation(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<UserDto> update(
      @PathVariable UUID id, @Valid @RequestBody CreateUserDto dto) {
    return ResponseEntity.ok(service.update(id, dto));
  }

  /**
   * Desbloquea la cuenta de un usuario bloqueado por intentos fallidos de login (RF-AUTH-08). Solo
   * el administrador global: las demás acciones sobre usuarios admiten también a ORG_RESPONSIBLE,
   * pero un bloqueo es una medida de seguridad y levantarlo no se delega.
   */
  @PostMapping("/{id}/unlock")
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<UnlockUserResultDto> unlock(@PathVariable UUID id) {
    return ResponseEntity.ok(new UnlockUserResultDto(service.unlock(id)));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('PRISMA_ADMIN', 'ORG_RESPONSIBLE')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
