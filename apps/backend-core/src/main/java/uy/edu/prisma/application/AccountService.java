package uy.edu.prisma.application;

import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.ChangePasswordDto;
import uy.edu.prisma.web.dto.Dto.SessionEventDto;
import uy.edu.prisma.web.dto.Dto.UpdateProfileDto;

/**
 * Acciones de autoservicio sobre la cuenta del usuario autenticado (no requiere ningún rol
 * particular, cualquiera puede operar sobre SU PROPIA cuenta: ver AccountController, sin
 * {@code @PreAuthorize} porque la identidad la resuelve {@link CurrentUserService}, no un rol).
 *
 * <p>Distinto del reseteo de contraseña que hace un PRISMA_ADMIN sobre otro usuario (ver
 * UserService.update): ahí no hace falta conocer la contraseña anterior porque el admin ya demostró
 * su identidad con su propio rol; acá sí, porque cualquier usuario autenticado podría estar en una
 * sesión compartida o robada.
 */
@Service
public class AccountService {

  private final CurrentUserService currentUser;
  private final KeycloakAdminClient keycloakAdmin;
  private final AuditLogService auditLog;

  public AccountService(
      CurrentUserService currentUser, KeycloakAdminClient keycloakAdmin, AuditLogService auditLog) {
    this.currentUser = currentUser;
    this.keycloakAdmin = keycloakAdmin;
    this.auditLog = auditLog;
  }

  // Autoservicio: corrige el nombre/apellido de la cuenta propia (p.ej. quedó mal cargado al
  // darla de alta). @Transactional acá para que `user` -- traído por CurrentUserService, que no
  // es transaccional en sí misma -- quede administrado dentro de ESTA transacción y el
  // dirty-checking de JPA persista los cambios al hacer commit (mismo patrón que UserService).
  @Transactional
  public void updateProfile(UpdateProfileDto dto) {
    User user =
        currentUser
            .currentUser()
            .orElseThrow(() -> new AccessDeniedException("No se pudo identificar al usuario"));
    user.setFirstName(dto.firstName());
    user.setLastName(dto.lastName());
    if (user.getKeycloakId() != null) {
      // Mantiene el nombre visible en Keycloak (tokens, consola de admin) en sync con el de
      // prisma.users -- si no, quedarían desalineados hasta la próxima vez que se edite desde
      // el panel de Usuarios (que sí sincroniza, ver UserService.update).
      keycloakAdmin.updateProfile(
          user.getKeycloakId(),
          user.getEmail(),
          user.getFirstName(),
          user.getLastName(),
          user.getEnabled());
    }
    auditLog.record("UPDATE_PROFILE", "user:" + user.getId(), null);
  }

  public void changePassword(ChangePasswordDto dto) {
    User user =
        currentUser
            .currentUser()
            .orElseThrow(() -> new AccessDeniedException("No se pudo identificar al usuario"));
    if (user.getKeycloakId() == null) {
      // No debería pasar en la práctica: si está acá es porque ya tiene un JWT válido de
      // Keycloak, lo que implica que sí tiene cuenta ahí. Defensivo por si el dato local quedó
      // desincronizado.
      throw new InvalidRequestException(
          "Tu cuenta todavía no tiene acceso habilitado; contactá a un administrador");
    }
    if (!keycloakAdmin.verifyPassword(user.getEmail(), dto.currentPassword())) {
      throw new InvalidRequestException("La contraseña actual no es correcta");
    }
    keycloakAdmin.setPermanentPassword(user.getKeycloakId(), dto.newPassword());
    auditLog.record("CHANGE_PASSWORD", "user:" + user.getId(), null);
  }

  private static final Set<String> ALLOWED_SESSION_EVENTS = Set.of("LOGIN", "LOGOUT");

  // El frontend llama esto justo después de que Keycloak lo autentica (stores/auth.ts#init, una
  // sola vez por sesión de pestaña, no en cada refresh) y justo antes de mandarlo al logout de
  // Keycloak (stores/auth.ts#logout) -- ver el comentario en SessionEventDto sobre por qué hace
  // falta este viaje explícito en vez de que quede registrado solo.
  public void recordSessionEvent(SessionEventDto dto) {
    if (!ALLOWED_SESSION_EVENTS.contains(dto.event())) {
      throw new InvalidRequestException("Evento de sesión inválido");
    }
    User user =
        currentUser
            .currentUser()
            .orElseThrow(() -> new AccessDeniedException("No se pudo identificar al usuario"));
    auditLog.record(dto.event(), "user:" + user.getId(), null);
  }
}
