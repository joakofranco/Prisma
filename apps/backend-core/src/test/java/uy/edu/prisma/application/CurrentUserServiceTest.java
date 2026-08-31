package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrentUserServiceTest {

  @Mock private UserRepository userRepository;

  private CurrentUserService service;
  private User user;

  @BeforeEach
  void setUp() {
    service = new CurrentUserService(userRepository);
    user =
        User.builder()
            .id(UUID.randomUUID())
            .email("admin@prisma.local")
            .tenant(Organization.builder().id(UUID.randomUUID()).build())
            .build();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private Jwt jwtWithEmail(String email) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .subject("sub-1")
        .claim("email", email)
        .build();
  }

  @Test
  void resolvesUserByJwtEmail() {
    Jwt jwt = jwtWithEmail("admin@prisma.local");
    when(userRepository.findByEmail("admin@prisma.local")).thenReturn(Optional.of(user));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, java.util.List.of()));

    assertEquals(user.getId(), service.currentUser().map(User::getId).orElse(null));
    assertEquals(user.getId(), service.currentUserId());
    assertEquals(user.getTenant().getId(), service.currentUser().get().getTenant().getId());
  }

  @Test
  void returnsEmptyWhenNoAuthentication() {
    assertTrue(service.currentUser().isEmpty());
    assertNull(service.currentUserId());
  }

  @Test
  void returnsEmptyForAnonymousAuthentication() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("anonymousUser", ""));
    assertTrue(service.currentUser().isEmpty());
  }

  @Test
  void returnsEmptyWhenPrincipalNotJwt() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("user", null, java.util.List.of()));
    assertTrue(service.currentUser().isEmpty());
  }

  @Test
  void returnsEmptyWhenEmailClaimMissing() {
    Jwt jwt = jwtWithEmail(null);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(jwt, null, java.util.List.of()));
    assertTrue(service.currentUser().isEmpty());
  }

  @Test
  void returnsEmptyWhenEmailNotRegistered() {
    when(userRepository.findByEmail("ghost@prisma.local")).thenReturn(Optional.empty());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                jwtWithEmail("ghost@prisma.local"), null, java.util.List.of()));
    assertTrue(service.currentUser().isEmpty());
  }

  // ---- Aislamiento multi-tenant (isGlobalRole / currentTenantId / assertOrganizationAccess) ----

  private void authenticateAs(User u) {
    when(userRepository.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                jwtWithEmail(u.getEmail()), null, java.util.List.of()));
  }

  @Test
  void isGlobalRoleTrueForPrismaAdmin() {
    user.setRoles(java.util.Set.of(UserRole.PRISMA_ADMIN));
    authenticateAs(user);
    assertTrue(service.isGlobalRole());
  }

  @Test
  void isGlobalRoleFalseForAuditor() {
    // AUDITOR dejó de ser un rol global: antes auditaba cualquier organización sin distinción,
    // ahora está acotado a las que se le asignaron explícitamente (ver auditedOrganizationIds).
    user.setRoles(java.util.Set.of(UserRole.AUDITOR));
    authenticateAs(user);
    assertFalse(service.isGlobalRole());
    assertTrue(service.isAuditor());
  }

  @Test
  void isGlobalRoleFalseForOrgResponsible() {
    user.setRoles(java.util.Set.of(UserRole.ORG_RESPONSIBLE));
    authenticateAs(user);
    assertFalse(service.isGlobalRole());
  }

  @Test
  void isGlobalRoleFalseWhenNotAuthenticated() {
    assertFalse(service.isGlobalRole());
  }

  @Test
  void assertOrganizationAccessAllowsGlobalRoleForAnyOrganization() {
    user.setRoles(java.util.Set.of(UserRole.PRISMA_ADMIN));
    authenticateAs(user);
    // Distinta a user.getTenant(): un rol global debe poder acceder igual.
    assertDoesNotThrow(() -> service.assertOrganizationAccess(UUID.randomUUID()));
  }

  @Test
  void assertOrganizationAccessAllowsMatchingTenant() {
    user.setRoles(java.util.Set.of(UserRole.ORG_RESPONSIBLE));
    authenticateAs(user);
    assertDoesNotThrow(() -> service.assertOrganizationAccess(user.getTenant().getId()));
  }

  @Test
  void assertOrganizationAccessDeniesDifferentTenant() {
    user.setRoles(java.util.Set.of(UserRole.ORG_RESPONSIBLE));
    authenticateAs(user);
    UUID otherOrgId = UUID.randomUUID();
    assertThrows(AccessDeniedException.class, () -> service.assertOrganizationAccess(otherOrgId));
  }

  @Test
  void assertOrganizationAccessDeniesWhenUserHasNoTenant() {
    User noTenant = User.builder().id(UUID.randomUUID()).email("viewer@prisma.local").build();
    noTenant.setRoles(java.util.Set.of(UserRole.VIEWER));
    authenticateAs(noTenant);
    UUID someOrgId = UUID.randomUUID();
    assertThrows(AccessDeniedException.class, () -> service.assertOrganizationAccess(someOrgId));
  }

  @Test
  void assertOrganizationAccessDeniesWhenNotAuthenticated() {
    UUID someOrgId = UUID.randomUUID();
    assertThrows(AccessDeniedException.class, () -> service.assertOrganizationAccess(someOrgId));
  }

  // ---- Auditor acotado a organizaciones asignadas (auditedOrganizationIds) ----

  @Test
  void assertOrganizationAccessAllowsAuditorForAssignedOrganization() {
    Organization org1 = Organization.builder().id(UUID.randomUUID()).build();
    Organization org2 = Organization.builder().id(UUID.randomUUID()).build();
    user.setRoles(java.util.Set.of(UserRole.AUDITOR));
    user.setAuditedOrganizations(java.util.Set.of(org1, org2));
    authenticateAs(user);

    assertDoesNotThrow(() -> service.assertOrganizationAccess(org1.getId()));
    assertDoesNotThrow(() -> service.assertOrganizationAccess(org2.getId()));
    assertEquals(java.util.Set.of(org1.getId(), org2.getId()), service.auditedOrganizationIds());
  }

  @Test
  void assertOrganizationAccessDeniesAuditorForUnassignedOrganization() {
    Organization assigned = Organization.builder().id(UUID.randomUUID()).build();
    user.setRoles(java.util.Set.of(UserRole.AUDITOR));
    user.setAuditedOrganizations(java.util.Set.of(assigned));
    authenticateAs(user);

    UUID otherOrgId = UUID.randomUUID();
    assertThrows(AccessDeniedException.class, () -> service.assertOrganizationAccess(otherOrgId));
  }

  @Test
  void assertOrganizationAccessDeniesAuditorWithNoOrganizationsAssigned() {
    user.setRoles(java.util.Set.of(UserRole.AUDITOR));
    authenticateAs(user);

    assertTrue(service.auditedOrganizationIds().isEmpty());
    assertThrows(
        AccessDeniedException.class, () -> service.assertOrganizationAccess(UUID.randomUUID()));
  }

  @Test
  void auditedOrganizationIdsEmptyForNonAuditor() {
    user.setRoles(java.util.Set.of(UserRole.ORG_RESPONSIBLE));
    authenticateAs(user);
    assertTrue(service.auditedOrganizationIds().isEmpty());
  }
}
