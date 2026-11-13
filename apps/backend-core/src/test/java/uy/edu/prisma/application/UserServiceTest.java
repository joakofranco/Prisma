package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uy.edu.prisma.config.PasswordHasher;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

  @Mock private UserRepository userRepo;
  @Mock private OrganizationRepository orgRepo;
  @Mock private PasswordHasher passwordHasher;
  @Mock private AuditLogService auditLog;
  @Mock private KeycloakAdminClient keycloakAdmin;
  @Mock private CurrentUserService currentUser;
  @Mock private EmailSettingsService emailSettings;

  private UserService service;
  private User user;
  private Organization tenant;

  @BeforeEach
  void setUp() {
    service =
        new UserService(
            userRepo,
            orgRepo,
            passwordHasher,
            auditLog,
            keycloakAdmin,
            currentUser,
            emailSettings,
            "prisma-frontend",
            "https://prisma.test");
    emailConfigured(true);
    // Los tests existentes asumen la vista sin restricciones de PRISMA_ADMIN; los tests de
    // aislamiento multi-tenant de mas abajo pisan este stub con isPrismaAdmin=false.
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    tenant =
        Organization.builder()
            .id(UUID.randomUUID())
            .name("Acme")
            .identifier("NIT-1")
            .sector("TECH")
            .size("SMALL")
            .enabled(true)
            .build();

    user =
        User.builder()
            .id(UUID.randomUUID())
            .email("u@test.com")
            .firstName("Jane")
            .lastName("Doe")
            .organizations(new java.util.HashSet<>(Set.of(tenant)))
            .enabled(true)
            .roles(Set.of(UserRole.VIEWER))
            .createdAt(OffsetDateTime.now())
            .build();
  }

  @Test
  void listReturnsPaginatedResults() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.search(any(), any())).thenReturn(new PageImpl<>(List.of(user), pr, 1));

    PaginatedDto<UserDto> result = service.list("jane", 0, 10);

    assertEquals(1, result.total());
    assertEquals(1, result.data().size());
    assertEquals(0, result.page());
    assertEquals(10, result.pageSize());
    UserDto dto = result.data().get(0);
    assertEquals(user.getId(), dto.id());
    assertEquals("u@test.com", dto.email());
    assertEquals("Jane", dto.firstName());
    assertEquals("Doe", dto.lastName());
    assertEquals(Set.of(tenant.getId()), dto.organizationIds());
    assertEquals(List.of("Acme"), dto.organizationNames());
    assertEquals(Set.of("VIEWER"), dto.roles());
    assertTrue(dto.enabled());
    assertEquals(user.getCreatedAt(), dto.createdAt());
    assertFalse(dto.canLogin());
  }

  @Test
  void listWithNullTenantAndNullSearchMapsNulls() {
    User bare = User.builder().id(UUID.randomUUID()).email("b@test.com").build();
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.search(isNull(), any())).thenReturn(new PageImpl<>(List.of(bare), pr, 1));

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    UserDto dto = result.data().get(0);
    assertTrue(dto.organizationIds().isEmpty());
    assertTrue(dto.organizationNames().isEmpty());
    assertTrue(dto.roles().isEmpty());
  }

  @Test
  void getByIdReturnsDto() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    UserDto dto = service.getById(user.getId());

    assertEquals(user.getId(), dto.id());
  }

  @Test
  void getByIdThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(userRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getById(id));
  }

  @Test
  void listScopesToOwnTenantForNonAdminNonAuditorRoles() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.searchByOrganizationIdIn(eq(Set.of(tenant.getId())), any(), any()))
        .thenReturn(new PageImpl<>(List.of(user), pr, 1));

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
    verify(userRepo, never()).search(any(), any());
  }

  @Test
  void listReturnsEmptyForNonAdminWithoutTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.organizationIds()).thenReturn(Set.of());

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    assertEquals(0, result.total());
    verify(userRepo, never()).searchByOrganizationIdIn(any(), any(), any());
  }

  @Test
  void listScopesToAuditedOrganizationsForAuditor() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.searchByOrganizationIdIn(eq(Set.of(tenant.getId())), any(), any()))
        .thenReturn(new PageImpl<>(List.of(user), pr, 1));

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
  }

  @Test
  void getByIdAllowsViewingOwnRecordEvenOutsideTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentUserId()).thenReturn(user.getId());
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    UserDto dto = service.getById(user.getId());

    assertEquals(user.getId(), dto.id());
  }

  @Test
  void getByIdDeniesAccessToUserOutsideTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.currentUserId()).thenReturn(UUID.randomUUID());
    when(currentUser.organizationIds()).thenReturn(Set.of(UUID.randomUUID()));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.getById(user.getId()));
  }

  @Test
  void createProvisionsUserInKeycloakAndPersistsWithRolesAndTenant() {
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    when(orgRepo.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID keycloakId = UUID.randomUUID();
    when(keycloakAdmin.createUser(
            eq("new@test.com"), eq("N"), eq("L"), eq("Secreto123!"), eq(Set.of("AUDITOR"))))
        .thenReturn(keycloakId);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com",
            "N",
            "L",
            tenant.getId(),
            Set.of("AUDITOR"),
            Set.of(tenant.getId()),
            "Secreto123!");
    UserDto result = service.create(dto);

    assertEquals("new@test.com", result.email());
    assertEquals(Set.of("AUDITOR"), result.roles());
    assertEquals(Set.of(tenant.getId()), result.organizationIds());
    assertTrue(result.enabled());
    assertTrue(result.canLogin());
    verify(userRepo, times(1)).save(argThat(u -> u.getOrganizations().contains(tenant)));
    verify(keycloakAdmin, times(1))
        .createUser("new@test.com", "N", "L", "Secreto123!", Set.of("AUDITOR"));
  }

  @Test
  void createThrowsWhenAuditorHasNoOrganizationsAssigned() {
    when(userRepo.existsByEmail("noorg@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "noorg@test.com", "N", "L", null, Set.of("AUDITOR"), Set.of(), "Secreto123!");

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void updateThrowsWhenAddingAuditorRoleWithoutOrganizations() {
    user.setOrganizations(new java.util.HashSet<>());
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("AUDITOR"), null);

    assertThrows(RuntimeException.class, () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).assignRealmRoles(any(), any());
  }

  // RF-USR-04: segregación de funciones -- un usuario no puede ser auditor y evaluador interno
  // a la vez (ni al crearse, ni al editarse para sumar el rol que le falta).
  @Test
  void createThrowsWhenAuditorAndInternalEvaluatorAssignedTogether() {
    when(userRepo.existsByEmail("dual@test.com")).thenReturn(false);
    when(orgRepo.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));

    CreateUserDto dto =
        new CreateUserDto(
            "dual@test.com",
            "N",
            "L",
            tenant.getId(),
            Set.of("AUDITOR", "INTERNAL_EVALUATOR"),
            Set.of(tenant.getId()),
            "Secreto123!");

    assertThrows(ConflictException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void updateThrowsWhenAddingAuditorRoleToExistingInternalEvaluator() {
    user.setRoles(new java.util.HashSet<>(Set.of(UserRole.INTERNAL_EVALUATOR)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto(
            "u@test.com", "Jane", "Doe", null, Set.of("INTERNAL_EVALUATOR", "AUDITOR"), null);

    assertThrows(ConflictException.class, () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).assignRealmRoles(any(), any());
  }

  @Test
  void updateAllowsChangingBetweenAuditorAndInternalEvaluatorSeparately() {
    user.setRoles(new java.util.HashSet<>(Set.of(UserRole.AUDITOR)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("INTERNAL_EVALUATOR"), null);

    UserDto result = service.update(user.getId(), dto);

    assertEquals(Set.of("INTERNAL_EVALUATOR"), result.roles());
  }

  @Test
  void createWithoutTenantPersistsUser() {
    when(userRepo.existsByEmail("solo@test.com")).thenReturn(false);
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    CreateUserDto dto = new CreateUserDto("solo@test.com", "N", "L", null, null, "Secreto123!");
    UserDto result = service.create(dto);

    assertTrue(result.organizationIds().isEmpty());
    assertTrue(result.roles().isEmpty());
    verify(userRepo, times(1)).save(any(User.class));
  }

  @Test
  void createThrowsWhenEmailAlreadyExists() {
    when(userRepo.existsByEmail("dup@test.com")).thenReturn(true);

    CreateUserDto dto = new CreateUserDto("dup@test.com", "N", "L", null, null, "Secreto123!");

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void createThrowsWhenPasswordMissing() {
    when(userRepo.existsByEmail("nopass@test.com")).thenReturn(false);

    CreateUserDto dto = new CreateUserDto("nopass@test.com", "N", "L", null, null, null);

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void createRollsBackWhenKeycloakProvisioningFails() {
    when(userRepo.existsByEmail("boom@test.com")).thenReturn(false);
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("keycloak caído"));

    CreateUserDto dto = new CreateUserDto("boom@test.com", "N", "L", null, null, "Secreto123!");

    assertThrows(RuntimeException.class, () -> service.create(dto));
    // El insert local sí se llamó (dentro de la transacción), pero @Transactional revierte todo
    // el método si createUser() lanza -- acá solo podemos verificar que se intentó, el rollback
    // en sí lo garantiza Spring, no este test unitario.
    verify(userRepo, times(1)).save(any(User.class));
  }

  @Test
  void updateSyncsProfileAndRolesWhenAlreadyProvisioned() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));

    CreateUserDto dto =
        new CreateUserDto(
            "upd@test.com", "Nuevo", "Apellido", tenant.getId(), Set.of("VIEWER"), null);
    UserDto result = service.update(user.getId(), dto);

    assertEquals("upd@test.com", result.email());
    assertEquals("Nuevo", result.firstName());
    assertEquals("Apellido", result.lastName());
    assertEquals(Set.of(tenant.getId()), result.organizationIds());
    assertTrue(result.canLogin());
    // El email nuevo (no el viejo) es lo que se le manda a Keycloak: ahí es también el username,
    // así que un cambio de email tiene que sincronizar los dos a la vez en la misma llamada.
    verify(keycloakAdmin, times(1))
        .updateProfile(keycloakId, "upd@test.com", "Nuevo", "Apellido", true);
    verify(keycloakAdmin, times(1)).assignRealmRoles(keycloakId, Set.of("VIEWER"));
    verify(keycloakAdmin, never()).setPermanentPassword(any(), any());
    verify(userRepo, never()).save(any());
  }

  @Test
  void updateKeepsEmailUnchangedWhenDtoSendsTheSameOne() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    // Mismo email que ya tiene el usuario (u@test.com): no debería ni consultar existsByEmail
    // (chocaría consigo mismo) ni tratarlo como un cambio real.
    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", null, null, null);
    UserDto result = service.update(user.getId(), dto);

    assertEquals("u@test.com", result.email());
    verify(userRepo, never()).existsByEmail(any());
  }

  @Test
  void updateThrowsWhenEmailAlreadyBelongsToAnotherUser() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(userRepo.existsByEmail("tomado@test.com")).thenReturn(true);

    CreateUserDto dto = new CreateUserDto("tomado@test.com", "Jane", "Doe", null, null, null);

    assertThrows(ConflictException.class, () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void updateSetsPermanentPasswordInKeycloakWhenProvided() {
    // No resetPassword() (temporary=true): un admin restableciendo la clave de un usuario ya
    // activo desde el panel espera que esa clave habilite el acceso de inmediato, sin que
    // Keycloak la descarte y lo obligue a elegir otra en el próximo login.
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", null, null, "NuevaClave1!");
    service.update(user.getId(), dto);

    verify(keycloakAdmin, times(1)).setPermanentPassword(keycloakId, "NuevaClave1!");
    verify(keycloakAdmin, never()).resetPassword(any(), any());
  }

  // ---- PRI-233: el administrador desbloquea una cuenta ----

  @Test
  void unlockClearsTheFailuresOfALockedAccountAndAuditsIt() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.loginFailureStatus(keycloakId))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(true, 10));

    assertTrue(service.unlock(user.getId()));

    verify(keycloakAdmin).clearLoginFailures(keycloakId);
    verify(auditLog)
        .record(eq("UNLOCK_USER"), eq("user:" + user.getId()), contains("\"wasLocked\":true"));
  }

  @Test
  void unlockAlsoClearsAnAccountWithFailuresThatIsNotLockedYet() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.loginFailureStatus(keycloakId))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(false, 4));

    assertTrue(service.unlock(user.getId()));

    verify(keycloakAdmin).clearLoginFailures(keycloakId);
  }

  @Test
  void unlockOfAnAccountThatWasNotLockedChangesNothingAndSaysSo() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.loginFailureStatus(keycloakId))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(false, 0));

    assertFalse(service.unlock(user.getId()));

    verify(keycloakAdmin, never()).clearLoginFailures(any());
    verify(auditLog)
        .record(eq("UNLOCK_USER"), eq("user:" + user.getId()), contains("\"wasLocked\":false"));
  }

  @Test
  void unlockOfAUserWithoutKeycloakAccountIsRejected() {
    user.setKeycloakId(null);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    assertThrows(InvalidRequestException.class, () -> service.unlock(user.getId()));

    verify(keycloakAdmin, never()).loginFailureStatus(any());
    verify(auditLog, never()).record(eq("UNLOCK_USER"), any(), any());
  }

  @Test
  void unlockOfAMissingUserIsNotFound() {
    UUID id = UUID.randomUUID();
    when(userRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.unlock(id));
  }

  @Test
  void unlockDoesNotHideKeycloakErrors() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.loginFailureStatus(keycloakId))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(true, 10));
    doThrow(new RuntimeException("Keycloak caído"))
        .when(keycloakAdmin)
        .clearLoginFailures(keycloakId);

    assertThrows(RuntimeException.class, () -> service.unlock(user.getId()));

    verify(auditLog, never()).record(eq("UNLOCK_USER"), any(), any());
  }

  // ---- PRI-365: el admin que restablece una contraseña le quita el MFA al usuario ----

  private CreateUserDto passwordChange() {
    return new CreateUserDto("u@test.com", "Jane", "Doe", null, null, "NuevaClave1!");
  }

  @Test
  void adminResettingAnotherUsersPasswordRemovesTheirMfaAndAuditsIt() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.removeOtpCredentials(keycloakId)).thenReturn(2);

    service.update(user.getId(), passwordChange());

    var order = inOrder(keycloakAdmin);
    order.verify(keycloakAdmin).setPermanentPassword(keycloakId, "NuevaClave1!");
    order.verify(keycloakAdmin).removeOtpCredentials(keycloakId);
    verify(auditLog)
        .record(eq("REMOVE_MFA"), eq("user:" + user.getId()), contains("\"removedCredentials\":2"));
  }

  @Test
  void noMfaAuditWhenTheUserHadNoMfa() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.removeOtpCredentials(keycloakId)).thenReturn(0);

    service.update(user.getId(), passwordChange());

    verify(keycloakAdmin).removeOtpCredentials(keycloakId);
    verify(auditLog, never()).record(eq("REMOVE_MFA"), any(), any());
  }

  @Test
  void mfaIsNotRemovedWhenThePasswordIsNotChanged() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    service.update(user.getId(), new CreateUserDto("u@test.com", "Jane", "Doe", null, null, null));

    verify(keycloakAdmin, never()).removeOtpCredentials(any());
  }

  @Test
  void orgResponsibleResettingAPasswordDoesNotTouchTheMfa() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    user.setOrganizations(Set.of(tenant));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    service.update(user.getId(), passwordChange());

    verify(keycloakAdmin).setPermanentPassword(keycloakId, "NuevaClave1!");
    verify(keycloakAdmin, never()).removeOtpCredentials(any());
  }

  @Test
  void adminEditingTheirOwnAccountKeepsTheirMfa() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.currentUser()).thenReturn(Optional.of(user));

    service.update(user.getId(), passwordChange());

    verify(keycloakAdmin).setPermanentPassword(keycloakId, "NuevaClave1!");
    verify(keycloakAdmin, never()).removeOtpCredentials(any());
  }

  @Test
  void keycloakFailureRemovingMfaPropagatesSoTheAdminKnows() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(keycloakAdmin.removeOtpCredentials(keycloakId))
        .thenThrow(new IllegalStateException("keycloak caído"));

    assertThrows(IllegalStateException.class, () -> service.update(user.getId(), passwordChange()));
    verify(auditLog, never()).record(eq("REMOVE_MFA"), any(), any());
  }

  @Test
  void updateSelfHealsProvisioningWhenMissingAndPasswordProvided() {
    // Usuario creado antes de este fix (o cuya creación en Keycloak falló en su momento):
    // keycloakId es null. Si el admin le fija una contraseña ahora, debe provisionarse recién acá.
    assertNull(user.getKeycloakId());
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    UUID newKeycloakId = UUID.randomUUID();
    when(keycloakAdmin.createUser("u@test.com", "Jane", "Doe", "Rescate123!", Set.of("VIEWER")))
        .thenReturn(newKeycloakId);

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("VIEWER"), "Rescate123!");
    UserDto result = service.update(user.getId(), dto);

    assertTrue(result.canLogin());
    verify(keycloakAdmin, times(1))
        .createUser("u@test.com", "Jane", "Doe", "Rescate123!", Set.of("VIEWER"));
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void updateWithoutPasswordLeavesUnprovisionedUserUnprovisioned() {
    assertNull(user.getKeycloakId());
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("VIEWER"), null);
    UserDto result = service.update(user.getId(), dto);

    assertFalse(result.canLogin());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(userRepo.findById(id)).thenReturn(Optional.empty());

    CreateUserDto dto = new CreateUserDto("x@test.com", "N", "L", null, null, null);

    assertThrows(RuntimeException.class, () -> service.update(id, dto));
  }

  @Test
  void deleteRemovesUserAndKeycloakAccount() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    service.delete(user.getId());

    verify(keycloakAdmin, times(1)).deleteUser(keycloakId);
    verify(userRepo, times(1)).delete(user);
  }

  @Test
  void deleteIsBlockedWhenUserHasDependentDataAndNeverTouchesKeycloak() {
    // El chequeo va ANTES de borrar en Keycloak: si no, la cuenta de Keycloak se perdería aunque la
    // base rechace el borrado por la FK (y el rollback no la restaura).
    user.setKeycloakId(UUID.randomUUID());
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(userRepo.countEvaluationsCreatedBy(user.getId())).thenReturn(2L);
    when(userRepo.countEvidenceUploadedBy(user.getId())).thenReturn(1L);

    uy.edu.prisma.domain.exception.ConflictException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.ConflictException.class,
            () -> service.delete(user.getId()));

    assertTrue(ex.getMessage().contains("2 evaluaciones creadas"));
    assertTrue(ex.getMessage().contains("1 evidencias subidas"));
    assertTrue(ex.getMessage().contains("Deshabilítelo"));
    verify(keycloakAdmin, never()).deleteUser(any());
    verify(userRepo, never()).delete(any());
    verify(auditLog, never()).record(eq("DELETE"), any(), any());
  }

  @Test
  void deleteIsBlockedWhenUserIsResponsibleOfAnOrganization() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(userRepo.countOrganizationsResponsibleFor(user.getId())).thenReturn(1L);

    assertThrows(
        uy.edu.prisma.domain.exception.ConflictException.class, () -> service.delete(user.getId()));
    verify(userRepo, never()).delete(any());
  }

  @Test
  void deleteIsBlockedByResponsesAndAuditObservations() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(userRepo.countResponsesBy(user.getId())).thenReturn(5L);
    when(userRepo.countAuditObservationsBy(user.getId())).thenReturn(3L);

    uy.edu.prisma.domain.exception.ConflictException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.ConflictException.class,
            () -> service.delete(user.getId()));
    assertTrue(ex.getMessage().contains("5 respuestas de evaluación"));
    assertTrue(ex.getMessage().contains("3 observaciones de auditoría"));
  }

  @Test
  void deleteSkipsKeycloakWhenNeverProvisioned() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    service.delete(user.getId());

    verify(keycloakAdmin, never()).deleteUser(any());
    verify(userRepo, times(1)).delete(user);
  }

  @Test
  void deleteThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(userRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.delete(id));
  }

  @Test
  void deleteRejectsDeletingYourOwnAccount() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.currentUserId()).thenReturn(user.getId());

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.delete(user.getId()));
    verify(userRepo, never()).delete(any());
    verify(keycloakAdmin, never()).deleteUser(any());
  }

  @Test
  void deleteRejectsDeletingTheGlobalSystemAdmin() {
    UUID systemAdminId = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    User systemAdmin =
        User.builder()
            .id(systemAdminId)
            .email("admin@prisma.local")
            .firstName("Admin")
            .lastName("PRISMA")
            .roles(Set.of(UserRole.PRISMA_ADMIN))
            .build();
    when(userRepo.findById(systemAdminId)).thenReturn(Optional.of(systemAdmin));
    // Lo pide OTRO admin, no el propio administrador global -- confirma que la protección es
    // independiente de la de "no podés borrarte a vos mismo".
    when(currentUser.currentUserId()).thenReturn(UUID.randomUUID());

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.delete(systemAdminId));
    verify(userRepo, never()).delete(any());
  }

  // --- Habilitar/deshabilitar (sin borrar, ver el comentario en el campo "enabled" de
  // CreateUserDto) --------------------------------------------------------------------------

  @Test
  void updateDisablesUserAndSyncsToKeycloak() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    user.setEnabled(true);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, null, Set.of(), null, false);
    UserDto result = service.update(user.getId(), dto);

    assertFalse(result.enabled());
    verify(keycloakAdmin, times(1)).updateProfile(keycloakId, "u@test.com", "Jane", "Doe", false);
  }

  @Test
  void updateReEnablesUser() {
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    user.setEnabled(false);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, null, Set.of(), null, true);
    UserDto result = service.update(user.getId(), dto);

    assertTrue(result.enabled());
    verify(keycloakAdmin, times(1)).updateProfile(keycloakId, "u@test.com", "Jane", "Doe", true);
  }

  @Test
  void updateLeavesEnabledUnchangedWhenDtoOmitsIt() {
    user.setEnabled(false);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    // Firma de compat (sin "enabled"): un cliente viejo no debería poder reactivar sin querer a
    // un usuario que un admin deshabilitó a propósito.
    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", null, null, null);
    UserDto result = service.update(user.getId(), dto);

    assertFalse(result.enabled());
  }

  @Test
  void updateRejectsDisablingYourOwnAccount() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.currentUserId()).thenReturn(user.getId());

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, null, Set.of(), null, false);

    assertThrows(InvalidRequestException.class, () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void updateRejectsDisablingTheGlobalSystemAdmin() {
    UUID systemAdminId = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    User systemAdmin =
        User.builder()
            .id(systemAdminId)
            .email("admin@prisma.local")
            .firstName("Admin")
            .lastName("PRISMA")
            .roles(Set.of(UserRole.PRISMA_ADMIN))
            .build();
    when(userRepo.findById(systemAdminId)).thenReturn(Optional.of(systemAdmin));
    when(currentUser.currentUserId()).thenReturn(UUID.randomUUID());

    CreateUserDto dto =
        new CreateUserDto(
            "admin@prisma.local", "Admin", "PRISMA", null, null, Set.of(), null, false);

    assertThrows(InvalidRequestException.class, () -> service.update(systemAdminId, dto));
  }

  @Test
  void createWithEnabledFalseSyncsDisabledToKeycloak() {
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID keycloakId = UUID.randomUUID();
    when(keycloakAdmin.createUser(eq("new@test.com"), any(), any(), any(), any()))
        .thenReturn(keycloakId);

    // VIEWER exige exactamente una organización (ver UserService.validateOrganizationsForRoles).
    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com",
            "New",
            "User",
            tenant.getId(),
            Set.of("VIEWER"),
            Set.of(),
            "Pass1234!",
            false);
    UserDto result = service.create(dto);

    assertFalse(result.enabled());
    // createUser() en Keycloak siempre nace habilitado -- hace falta un segundo llamado para
    // sincronizar que en realidad se lo pidió deshabilitado desde el alta.
    verify(keycloakAdmin, times(1)).updateProfile(keycloakId, "new@test.com", "New", "User", false);
  }

  // --- ORG_RESPONSIBLE como "administrador" de su propia organización -----------------------

  @Test
  void createAsOrgAdminForcesOwnOrganizationWhenDtoOmitsIt() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    CreateUserDto dto =
        new CreateUserDto("new@test.com", "N", "L", null, Set.of("VIEWER"), null, "Secreto123!");
    UserDto result = service.create(dto);

    assertEquals(Set.of(tenant.getId()), result.organizationIds());
  }

  @Test
  void createAsOrgAdminRejectsAssigningAnotherOrganization() {
    UUID otherOrgId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com", "N", "L", otherOrgId, Set.of("VIEWER"), null, "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
  }

  @Test
  void createAsOrgAdminCannotCreateAGeneralEvaluator() {
    // Sin organizaciones, un INTERNAL_EVALUATOR es "general" (accede a todas). Un ORG_RESPONSIBLE
    // siempre crea dentro de SU organización: no puede otorgar ese alcance global.
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    when(userRepo.existsByEmail("ev@test.com")).thenReturn(false);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    UserDto result =
        service.create(
            new CreateUserDto(
                "ev@test.com",
                "Eva",
                "Luador",
                null,
                Set.of("INTERNAL_EVALUATOR"),
                null,
                "Secreto123!"));

    assertEquals(Set.of(tenant.getId()), result.organizationIds());
  }

  @Test
  void createAsOrgAdminRejectsGrantingPlatformAdminRole() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com", "N", "L", null, Set.of("PRISMA_ADMIN"), null, "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void createAsOrgAdminRejectsAuditingAnotherOrganization() {
    UUID otherOrgId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com", "N", "L", null, Set.of("AUDITOR"), Set.of(otherOrgId), "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
  }

  @Test
  void createAsOrgAdminWithoutOwnTenantIsRejected() {
    // Estado inválido en la práctica (ORG_RESPONSIBLE siempre debería tener tenant), pero si
    // llegara a pasar no debe terminar creando un usuario sin organización asignada.
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(null);
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(null);

    CreateUserDto dto =
        new CreateUserDto("new@test.com", "N", "L", null, Set.of("VIEWER"), null, "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class, () -> service.create(dto));
    verify(userRepo, never()).save(any());
  }

  @Test
  void updateAsOrgAdminRejectsUserFromAnotherTenant() {
    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra").build();
    user.setOrganizations(new java.util.HashSet<>(Set.of(otherOrg)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(otherOrg.getId());

    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", null, null, null);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void updateAsOrgAdminRejectsMovingUserToAnotherTenant() {
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant)));
    UUID otherOrgId = UUID.randomUUID();
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", otherOrgId, null, null);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.update(user.getId(), dto));
  }

  @Test
  void updateAsOrgAdminRejectsGrantingPlatformAdminRole() {
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("PRISMA_ADMIN"), null);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.update(user.getId(), dto));
  }

  @Test
  void updateAsOrgAdminAllowsEditingOwnTenantUser() {
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant)));
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", tenant.getId(), Set.of("VIEWER"), null);
    UserDto result = service.update(user.getId(), dto);

    assertEquals(Set.of("VIEWER"), result.roles());
    verify(keycloakAdmin, times(1)).assignRealmRoles(keycloakId, Set.of("VIEWER"));
  }

  @Test
  void deleteAsOrgAdminRejectsUserFromAnotherTenant() {
    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra").build();
    user.setOrganizations(new java.util.HashSet<>(Set.of(otherOrg)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(otherOrg.getId());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.delete(user.getId()));
    verify(userRepo, never()).delete(any());
  }

  @Test
  void deleteAsOrgAdminAllowsDeletingOwnTenantUser() {
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    service.delete(user.getId());

    verify(userRepo, times(1)).delete(user);
  }

  // --- Organizaciones por rol (I7 / C7, camino B: membresia M:N) -----------------------------

  private Organization org(String name) {
    return Organization.builder().id(UUID.randomUUID()).name(name).enabled(true).build();
  }

  private UserDto createWith(Set<String> roles, Set<UUID> orgIds, List<Organization> resolved) {
    when(userRepo.existsByEmail(any())).thenReturn(false);
    when(orgRepo.findAllById(any())).thenReturn(resolved);
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
    return service.create(
        new CreateUserDto("x@test.com", "Ana", "Perez", orgIds, roles, "Secreto123!", null));
  }

  @Test
  void createRejectsAssigningAnInactiveOrganization() {
    Organization inactive = org("Vieja");
    inactive.setEnabled(false);

    uy.edu.prisma.domain.exception.InvalidRequestException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.InvalidRequestException.class,
            () -> createWith(Set.of("VIEWER"), Set.of(inactive.getId()), List.of(inactive)));

    assertTrue(ex.getMessage().contains("inactiva"));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void updateRejectsAddingAnInactiveOrganizationButKeepsExistingOnes() {
    Organization inactive = org("Vieja");
    inactive.setEnabled(false);
    user.setRoles(Set.of(UserRole.INTERNAL_EVALUATOR));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant, inactive));

    // Sumar una inactiva: rechazado.
    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () ->
            service.update(
                user.getId(),
                new CreateUserDto(
                    "u@test.com",
                    "Jane",
                    "Doe",
                    Set.of(tenant.getId(), inactive.getId()),
                    Set.of("INTERNAL_EVALUATOR"),
                    null,
                    null)));

    // Pero si ya la tenía (quedó inactiva después), seguir en ella no es un error.
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant, inactive)));
    UserDto result =
        service.update(
            user.getId(),
            new CreateUserDto(
                "u@test.com",
                "Jane",
                "Doe",
                Set.of(tenant.getId(), inactive.getId()),
                Set.of("INTERNAL_EVALUATOR"),
                null,
                null));
    assertEquals(Set.of(tenant.getId(), inactive.getId()), result.organizationIds());
  }

  @Test
  void adminCanBeCreatedWithoutAnyOrganization() {
    UserDto result = createWith(Set.of("PRISMA_ADMIN"), null, List.of());

    assertTrue(result.organizationIds().isEmpty());
  }

  @Test
  void generalEvaluatorCanBeCreatedWithoutOrganizationsByAdmin() {
    UserDto result = createWith(Set.of("INTERNAL_EVALUATOR"), Set.of(), List.of());

    assertTrue(result.organizationIds().isEmpty());
    assertEquals(Set.of("INTERNAL_EVALUATOR"), result.roles());
  }

  @Test
  void evaluatorCanBeCreatedWithSeveralOrganizations() {
    Organization a = org("A");
    Organization b = org("B");

    UserDto result =
        createWith(Set.of("INTERNAL_EVALUATOR"), Set.of(a.getId(), b.getId()), List.of(a, b));

    assertEquals(Set.of(a.getId(), b.getId()), result.organizationIds());
    assertEquals(List.of("A", "B"), result.organizationNames());
  }

  @Test
  void auditorCanBeCreatedWithSeveralOrganizations() {
    Organization a = org("A");
    Organization b = org("B");

    UserDto result = createWith(Set.of("AUDITOR"), Set.of(a.getId(), b.getId()), List.of(a, b));

    assertEquals(Set.of(a.getId(), b.getId()), result.organizationIds());
  }

  @Test
  void responsibleAndViewerRequireExactlyOneOrganization() {
    Organization a = org("A");
    Organization b = org("B");
    for (String role : List.of("ORG_RESPONSIBLE", "VIEWER")) {
      assertThrows(
          uy.edu.prisma.domain.exception.InvalidRequestException.class,
          () -> createWith(Set.of(role), Set.of(), List.of()),
          role + " sin organizacion");
      assertThrows(
          uy.edu.prisma.domain.exception.InvalidRequestException.class,
          () -> createWith(Set.of(role), Set.of(a.getId(), b.getId()), List.of(a, b)),
          role + " con dos organizaciones");
      assertEquals(
          Set.of(a.getId()),
          createWith(Set.of(role), Set.of(a.getId()), List.of(a)).organizationIds());
    }
  }

  @Test
  void adminRoleWinsOverTheOtherRolesOrganizationRules() {
    // pruebadai@: PRISMA_ADMIN + varios roles + varias organizaciones. Al ser global no se le
    // aplican las reglas de cantidad de los demás roles.
    Organization a = org("A");
    Organization b = org("B");

    UserDto result =
        createWith(
            Set.of("PRISMA_ADMIN", "VIEWER", "AUDITOR"),
            Set.of(a.getId(), b.getId()),
            List.of(a, b));

    assertEquals(2, result.organizationIds().size());
  }

  @Test
  void adminCanClearAllOrganizationsOfAnEvaluatorOnUpdate() {
    // "Volver atrás" / "sin organización": un set vacío en el PUT quita todas (evaluador general).
    user.setRoles(Set.of(UserRole.INTERNAL_EVALUATOR));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(orgRepo.findAllById(any())).thenReturn(List.of());

    UserDto result =
        service.update(
            user.getId(),
            new CreateUserDto(
                "u@test.com", "Jane", "Doe", Set.of(), Set.of("INTERNAL_EVALUATOR"), null, null));

    assertTrue(result.organizationIds().isEmpty());
  }

  @Test
  void updateWithoutOrganizationsFieldLeavesThemUntouched() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    UserDto result =
        service.update(
            user.getId(),
            new CreateUserDto(
                "u@test.com", "Jane", "Doe", (Set<UUID>) null, Set.of("VIEWER"), null, null));

    assertEquals(Set.of(tenant.getId()), result.organizationIds());
  }

  @Test
  void orgAdminCannotEditOrDeleteAUserWhoAlsoBelongsToAnotherOrganization() {
    Organization other = org("Otra");
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant, other)));
    user.setRoles(Set.of(UserRole.INTERNAL_EVALUATOR));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            service.update(
                user.getId(),
                new CreateUserDto(
                    "u@test.com", "Jane", "Doe", (Set<UUID>) null, Set.of("VIEWER"), null, null)));
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.delete(user.getId()));
    verify(userRepo, never()).delete(any());
    // Pero SÍ puede verlo: comparten una organización.
    assertDoesNotThrow(() -> service.getById(user.getId()));
  }

  @Test
  void orgAdminEditingASingleOrganizationUserNeverChangesTheirOrganizations() {
    user.setOrganizations(new java.util.HashSet<>(Set.of(tenant)));
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(tenant.getId()));

    service.update(
        user.getId(),
        new CreateUserDto(
            "u@test.com", "Jane", "Doe", Set.of(tenant.getId()), Set.of("VIEWER"), null, null));

    assertEquals(Set.of(tenant.getId()), user.organizationIds());
    verify(orgRepo, never()).findAllById(any());
  }

  // ---- RF-USR-05 (PRI-240): invitación de evaluadores internos ----

  private void emailConfigured(boolean configured) {
    when(emailSettings.get())
        .thenReturn(
            new EmailSettingsDto(
                configured ? "smtp.test" : "",
                587,
                configured ? "no-reply@prisma.test" : "",
                null,
                false,
                null,
                true,
                false,
                configured));
  }

  private void actAsResponsibleOf(Organization organization) {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(organization.getId());
    when(currentUser.organizationIds()).thenReturn(Set.of(organization.getId()));
  }

  private User invitedUser(OffsetDateTime acceptedAt) {
    return User.builder()
        .id(UUID.randomUUID())
        .email("lucia@test.com")
        .firstName("Lucía")
        .lastName("Fernández")
        .organizations(new java.util.HashSet<>(Set.of(tenant)))
        .enabled(true)
        .roles(Set.of(UserRole.INTERNAL_EVALUATOR))
        .keycloakId(UUID.randomUUID())
        .invitedAt(OffsetDateTime.now().minusDays(5))
        .invitationAcceptedAt(acceptedAt)
        .build();
  }

  @Test
  void inviteCreatesAnInternalEvaluatorOfTheResponsiblesOrganizationAndSendsTheEmail() {
    actAsResponsibleOf(tenant);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID keycloakId = UUID.randomUUID();
    when(keycloakAdmin.createInvitedUser(
            "lucia@test.com", "Lucía", "Fernández", Set.of("INTERNAL_EVALUATOR")))
        .thenReturn(keycloakId);

    UserDto result =
        service.invite(new InviteUserDto("lucia@test.com", "Lucía", "Fernández", null));

    assertEquals(Set.of("INTERNAL_EVALUATOR"), result.roles());
    assertEquals(Set.of(tenant.getId()), result.organizationIds());
    assertNotNull(result.invitedAt());
    assertNull(result.invitationAcceptedAt());
    assertTrue(result.canLogin());
    verify(userRepo).save(argThat(u -> u.getPasswordHash() == null));
    verify(keycloakAdmin)
        .sendExecuteActionsEmail(
            keycloakId,
            List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"),
            259200,
            "prisma-frontend",
            "https://prisma.test");
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
    verify(auditLog).record(eq("INVITE_USER"), any(), contains("lucia@test.com"));
  }

  @Test
  void aResponsibleCannotInviteToAnotherOrganization() {
    actAsResponsibleOf(tenant);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            service.invite(
                new InviteUserDto("lucia@test.com", "Lucía", "Fernández", UUID.randomUUID())));
    verify(userRepo, never()).save(any());
  }

  @Test
  void theAdminMustChooseTheOrganization() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.invite(new InviteUserDto("lucia@test.com", "Lucía", "Fernández", null)));
    verify(userRepo, never()).save(any());
  }

  @Test
  void theAdminInvitesToTheOrganizationChosen() {
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createInvitedUser(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    UserDto result =
        service.invite(new InviteUserDto("lucia@test.com", "Lucía", "Fernández", tenant.getId()));

    assertEquals(Set.of(tenant.getId()), result.organizationIds());
  }

  @Test
  void inviteRejectsAnEmailAlreadyRegistered() {
    when(userRepo.existsByEmail("lucia@test.com")).thenReturn(true);

    assertThrows(
        ConflictException.class,
        () ->
            service.invite(
                new InviteUserDto("lucia@test.com", "Lucía", "Fernández", tenant.getId())));
  }

  @Test
  void inviteRejectsAnInactiveOrganization() {
    tenant.setEnabled(false);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));

    assertThrows(
        InvalidRequestException.class,
        () ->
            service.invite(
                new InviteUserDto("lucia@test.com", "Lucía", "Fernández", tenant.getId())));
    verify(userRepo, never()).save(any());
  }

  @Test
  void inviteRejectsAnOrganizationThatDoesNotExist() {
    when(orgRepo.findAllById(any())).thenReturn(List.of());

    assertThrows(
        ResourceNotFoundException.class,
        () ->
            service.invite(
                new InviteUserDto("lucia@test.com", "Lucía", "Fernández", UUID.randomUUID())));
  }

  @Test
  void withoutAMailServerNobodyIsInvited() {
    emailConfigured(false);
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () ->
                service.invite(
                    new InviteUserDto("lucia@test.com", "Lucía", "Fernández", tenant.getId())));
    assertTrue(e.getMessage().contains("servidor de correo"));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createInvitedUser(any(), any(), any(), any());
  }

  @Test
  void whenTheEmailFailsTheKeycloakAccountIsRemoved() {
    when(orgRepo.findAllById(Set.of(tenant.getId()))).thenReturn(List.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID keycloakId = UUID.randomUUID();
    when(keycloakAdmin.createInvitedUser(any(), any(), any(), any())).thenReturn(keycloakId);
    doThrow(new KeycloakAdminClient.EmailDeliveryException(new RuntimeException("smtp")))
        .when(keycloakAdmin)
        .sendExecuteActionsEmail(any(), any(), anyInt(), any(), any());

    assertThrows(
        ConflictException.class,
        () ->
            service.invite(
                new InviteUserDto("lucia@test.com", "Lucía", "Fernández", tenant.getId())));
    verify(keycloakAdmin).deleteUser(keycloakId);
    verify(auditLog, never()).record(eq("INVITE_USER"), any(), any());
  }

  @Test
  void resendInvitationSendsTheEmailAgainAndRenewsTheDate() {
    User invited = invitedUser(null);
    OffsetDateTime before = invited.getInvitedAt();
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));

    UserDto result = service.resendInvitation(invited.getId());

    assertTrue(result.invitedAt().isAfter(before));
    verify(keycloakAdmin)
        .sendExecuteActionsEmail(eq(invited.getKeycloakId()), any(), eq(259200), any(), any());
    verify(auditLog).record(eq("RESEND_INVITATION"), eq("user:" + invited.getId()), any());
  }

  @Test
  void resendInvitationRejectsWhoAlreadyEntered() {
    User invited = invitedUser(OffsetDateTime.now());
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));

    assertThrows(ConflictException.class, () -> service.resendInvitation(invited.getId()));
    verify(keycloakAdmin, never()).sendExecuteActionsEmail(any(), any(), anyInt(), any(), any());
  }

  @Test
  void resendInvitationRejectsAUserCreatedWithATemporaryPassword() {
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    assertThrows(ConflictException.class, () -> service.resendInvitation(user.getId()));
  }

  @Test
  void resendInvitationRejectsADisabledUser() {
    User invited = invitedUser(null);
    invited.setEnabled(false);
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));

    assertThrows(InvalidRequestException.class, () -> service.resendInvitation(invited.getId()));
  }

  @Test
  void resendInvitationRejectsAUserWithoutKeycloakAccount() {
    User invited = invitedUser(null);
    invited.setKeycloakId(null);
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));

    assertThrows(InvalidRequestException.class, () -> service.resendInvitation(invited.getId()));
  }

  @Test
  void aResponsibleCannotResendAnInvitationOfAnotherOrganization() {
    User invited = invitedUser(null);
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.organizationIds()).thenReturn(Set.of(UUID.randomUUID()));

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.resendInvitation(invited.getId()));
  }

  @Test
  void resendInvitationTranslatesAnEmailFailure() {
    User invited = invitedUser(null);
    when(userRepo.findById(invited.getId())).thenReturn(Optional.of(invited));
    doThrow(new KeycloakAdminClient.EmailDeliveryException(new RuntimeException("smtp")))
        .when(keycloakAdmin)
        .sendExecuteActionsEmail(any(), any(), anyInt(), any(), any());

    assertThrows(ConflictException.class, () -> service.resendInvitation(invited.getId()));
  }

  // ---- Estado de bloqueo de cada usuario (RF-AUTH-08): la lista refleja lo que dice Keycloak ----

  private User withKeycloakAccount(String email) {
    return User.builder()
        .id(UUID.randomUUID())
        .email(email)
        .firstName("N")
        .lastName("L")
        .enabled(true)
        .roles(Set.of(UserRole.VIEWER))
        .keycloakId(UUID.randomUUID())
        .createdAt(OffsetDateTime.now())
        .build();
  }

  private PaginatedDto<UserDto> listOf(User... users) {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.search(any(), any()))
        .thenReturn(new PageImpl<>(List.of(users), pr, users.length));
    return service.list(null, 0, 10);
  }

  private static UserDto byEmail(PaginatedDto<UserDto> page, String email) {
    return page.data().stream().filter(u -> u.email().equals(email)).findFirst().orElseThrow();
  }

  @Test
  void theListMarksALockedAccountWithTheTimeItStaysLockedUntil() {
    User locked = withKeycloakAccount("locked@test.com");
    long until = java.time.Instant.parse("2026-11-13T19:29:04Z").getEpochSecond();
    when(keycloakAdmin.loginFailureStatus(locked.getKeycloakId()))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(true, 10, until));

    UserDto dto = byEmail(listOf(locked), "locked@test.com");

    assertEquals(Boolean.TRUE, dto.locked());
    assertEquals(java.time.Instant.ofEpochSecond(until), dto.lockedUntil().toInstant());
  }

  @Test
  void theListShowsAnAccountWithFailuresButNotLockedAsNotLocked() {
    User tryingUser = withKeycloakAccount("trying@test.com");
    when(keycloakAdmin.loginFailureStatus(tryingUser.getKeycloakId()))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(false, 4, 0));

    UserDto dto = byEmail(listOf(tryingUser), "trying@test.com");

    assertEquals(Boolean.FALSE, dto.locked());
    assertNull(dto.lockedUntil());
  }

  @Test
  void aUserWithoutAnAccountHasNoLockStateAndKeycloakIsNotAskedAboutIt() {
    user.setKeycloakId(null);

    UserDto dto = byEmail(listOf(user), "u@test.com");

    assertNull(dto.locked());
    assertNull(dto.lockedUntil());
    verify(keycloakAdmin, never()).loginFailureStatus(any());
  }

  @Test
  void eachUserInThePageGetsItsOwnState() {
    User a = withKeycloakAccount("a@test.com");
    User b = withKeycloakAccount("b@test.com");
    when(keycloakAdmin.loginFailureStatus(a.getKeycloakId()))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(true, 10, 4_000_000_000L));
    when(keycloakAdmin.loginFailureStatus(b.getKeycloakId()))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(false, 0, 0));

    PaginatedDto<UserDto> page = listOf(a, b);

    assertEquals(Boolean.TRUE, byEmail(page, "a@test.com").locked());
    assertEquals(Boolean.FALSE, byEmail(page, "b@test.com").locked());
  }

  @Test
  void ifKeycloakFailsTheListIsStillReturnedWithTheStateUnknown() {
    User u = withKeycloakAccount("down@test.com");
    when(keycloakAdmin.loginFailureStatus(u.getKeycloakId()))
        .thenThrow(new IllegalStateException("Keycloak no responde"));

    UserDto dto = byEmail(listOf(u), "down@test.com");

    assertNull(dto.locked());
    assertEquals("down@test.com", dto.email());
  }

  @Test
  void ifKeycloakIsTooSlowTheListDoesNotWaitForeverAndTheStateIsUnknown() {
    User slow = withKeycloakAccount("slow@test.com");
    when(keycloakAdmin.loginFailureStatus(slow.getKeycloakId()))
        .thenAnswer(
            inv -> {
              Thread.sleep(10_000);
              return new KeycloakAdminClient.LoginFailureStatus(true, 10, 4_000_000_000L);
            });

    long started = System.nanoTime();
    UserDto dto = byEmail(listOf(slow), "slow@test.com");
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;

    assertNull(dto.locked());
    assertTrue(elapsedMs < 5_000, "esperó " + elapsedMs + " ms");
  }

  @Test
  void theDetailAlsoReportsTheLockState() {
    User locked = withKeycloakAccount("detail@test.com");
    when(userRepo.findById(locked.getId())).thenReturn(Optional.of(locked));
    when(keycloakAdmin.loginFailureStatus(locked.getKeycloakId()))
        .thenReturn(new KeycloakAdminClient.LoginFailureStatus(true, 10, 4_000_000_000L));

    assertEquals(Boolean.TRUE, service.getById(locked.getId()).locked());
  }
}
