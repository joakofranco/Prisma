package uy.edu.prisma.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.repository.UserRepository;

/** Resuelve el usuario de base de datos asociado al JWT autenticado. */
@Service
public class CurrentUserService {

  private final UserRepository userRepository;

  public CurrentUserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public Optional<User> currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }
    Object principal = authentication.getPrincipal();
    if (principal == null) {
      return Optional.empty();
    }
    if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
      String email = jwt.getClaimAsString("email");
      if (email != null && !email.isBlank()) {
        return userRepository.findByEmail(email);
      }
    }
    return Optional.empty();
  }

  /** Id del usuario actual o {@code null} si no es trazable. */
  public UUID currentUserId() {
    return currentUser().map(User::getId).orElse(null);
  }

  /**
   * PRISMA_ADMIN es el único rol verdaderamente global (no atado a organizacion alguna): administra
   * a traves de todos los tenants sin restriccion. AUDITOR NO es global -- ver {@link
   * #isAuditor()}: esta acotado a las organizaciones que se le asignaron explicitamente al
   * crearlo/editarlo (antes sí lo era, lo que dejaba a cualquier auditor auditar evaluaciones de
   * cualquier organizacion). El resto de los roles (ORG_RESPONSIBLE, INTERNAL_EVALUATOR, VIEWER)
   * estan acotados a la organizacion (tenant) del usuario.
   */
  public boolean isGlobalRole() {
    return isPrismaAdmin();
  }

  public boolean isPrismaAdmin() {
    return currentUser().map(u -> u.getRoles().contains(UserRole.PRISMA_ADMIN)).orElse(false);
  }

  public boolean isAuditor() {
    return currentUser().map(u -> u.getRoles().contains(UserRole.AUDITOR)).orElse(false);
  }

  public boolean hasRole(UserRole role) {
    return currentUser().map(u -> u.getRoles().contains(role)).orElse(false);
  }

  /** Id de la organizacion (tenant) del usuario actual, o {@code null} si no tiene una asignada. */
  public UUID currentTenantId() {
    return currentUser().map(User::getTenant).map(Organization::getId).orElse(null);
  }

  /**
   * Ids de las organizaciones que el auditor actual puede auditar (asignadas al crearlo/editarlo,
   * ver UserService). Vacio si el usuario actual no es AUDITOR o no tiene ninguna asignada.
   */
  public Set<UUID> auditedOrganizationIds() {
    return currentUser()
        .map(
            u ->
                u.getAuditedOrganizations().stream()
                    .map(Organization::getId)
                    .collect(Collectors.toSet()))
        .orElse(Set.of());
  }

  /**
   * Verifica que el usuario actual pueda operar sobre datos de la organizacion dada. PRISMA_ADMIN
   * siempre pasa; AUDITOR solo si esa organizacion esta entre las que tiene asignadas; el resto
   * solo si coincide con su propio tenant. Lanza 403 en caso contrario, para no filtrar
   * evaluaciones, evidencias, observaciones de auditoria, planes de mejora ni reportes entre
   * organizaciones distintas (aislamiento multi-tenant, ver docs/Proyecto.md).
   */
  public void assertOrganizationAccess(UUID organizationId) {
    if (isPrismaAdmin()) {
      return;
    }
    if (isAuditor()) {
      if (organizationId != null && auditedOrganizationIds().contains(organizationId)) {
        return;
      }
      throw new AccessDeniedException("No tiene acceso a los datos de esta organizacion");
    }
    UUID tenantId = currentTenantId();
    if (organizationId == null || !organizationId.equals(tenantId)) {
      throw new AccessDeniedException("No tiene acceso a los datos de esta organizacion");
    }
  }
}
