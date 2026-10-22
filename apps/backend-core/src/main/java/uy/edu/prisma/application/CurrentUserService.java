package uy.edu.prisma.application;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
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

  /**
   * Id de la cuenta de Keycloak del JWT autenticado (claim {@code sub}), si es un UUID válido.
   * Sirve para vincular una fila de {@code prisma.users} con su cuenta de Keycloak cuando todavía
   * no lo está (ver AccountService.linkKeycloakAccountIfMissing).
   */
  public Optional<UUID> currentKeycloakSubject() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal()
            instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
      String subject = jwt.getSubject();
      if (subject != null) {
        try {
          return Optional.of(UUID.fromString(subject));
        } catch (IllegalArgumentException e) {
          return Optional.empty();
        }
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

  /**
   * Id de la organizacion del usuario actual SI tiene exactamente una (ORG_RESPONSIBLE, VIEWER,
   * evaluador de una sola organizacion); {@code null} si no tiene ninguna o tiene varias
   * (PRISMA_ADMIN, evaluador general, auditor de varias).
   */
  public UUID currentTenantId() {
    return currentUser().map(User::singleOrganizationId).orElse(null);
  }

  /** Ids de las organizaciones a las que el usuario actual accede (vacio si no tiene ninguna). */
  public Set<UUID> organizationIds() {
    return currentUser().map(User::organizationIds).orElse(Set.of());
  }

  /**
   * Evaluador "general": INTERNAL_EVALUATOR sin ninguna organizacion asignada. Accede a TODAS las
   * organizaciones (no a la administracion de usuarios, que sigue siendo de PRISMA_ADMIN /
   * ORG_RESPONSIBLE).
   */
  public boolean isGeneralEvaluator() {
    return currentUser()
        .map(
            u ->
                u.getRoles().contains(UserRole.INTERNAL_EVALUATOR)
                    && u.getOrganizations().isEmpty())
        .orElse(false);
  }

  /** PRISMA_ADMIN y el evaluador general acceden a todas las organizaciones, sin restriccion. */
  public boolean hasAllOrganizationsAccess() {
    return isPrismaAdmin() || isGeneralEvaluator();
  }

  /**
   * Verifica que el usuario actual pueda operar sobre datos de la organizacion dada. PRISMA_ADMIN y
   * el evaluador general siempre pasan; el resto solo si esa organizacion esta entre las suyas.
   * Lanza 403 en caso contrario, para no filtrar evaluaciones, evidencias, observaciones de
   * auditoria, planes de mejora ni reportes entre organizaciones distintas (aislamiento
   * multi-tenant, ver docs/Proyecto.md).
   */
  public void assertOrganizationAccess(UUID organizationId) {
    if (hasAllOrganizationsAccess()) {
      return;
    }
    if (organizationId == null || !organizationIds().contains(organizationId)) {
      throw new AccessDeniedException("No tiene acceso a los datos de esta organizacion");
    }
  }
}
