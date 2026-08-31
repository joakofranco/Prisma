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

  private UserService service;
  private User user;
  private Organization tenant;

  @BeforeEach
  void setUp() {
    service =
        new UserService(userRepo, orgRepo, passwordHasher, auditLog, keycloakAdmin, currentUser);
    // Los tests existentes asumen la vista sin restricciones de PRISMA_ADMIN; los tests de
    // aislamiento multi-tenant de mas abajo pisan este stub con isPrismaAdmin=false.
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    tenant =
        Organization.builder()
            .id(UUID.randomUUID())
            .name("Acme")
            .rut("NIT-1")
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
            .tenant(tenant)
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
    assertEquals(tenant.getId(), dto.tenantId());
    assertEquals("Acme", dto.organizationName());
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
    assertNull(dto.tenantId());
    assertNull(dto.organizationName());
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
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.searchByTenantIdIn(eq(Set.of(tenant.getId())), any(), any()))
        .thenReturn(new PageImpl<>(List.of(user), pr, 1));

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
    verify(userRepo, never()).search(any(), any());
  }

  @Test
  void listReturnsEmptyForNonAdminWithoutTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(null);

    PaginatedDto<UserDto> result = service.list(null, 0, 10);

    assertEquals(0, result.total());
    verify(userRepo, never()).searchByTenantIdIn(any(), any(), any());
  }

  @Test
  void listScopesToAuditedOrganizationsForAuditor() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.auditedOrganizationIds()).thenReturn(Set.of(tenant.getId()));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(userRepo.searchByTenantIdIn(eq(Set.of(tenant.getId())), any(), any()))
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
    when(currentUser.currentTenantId()).thenReturn(UUID.randomUUID());
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
    assertEquals(tenant.getId(), result.tenantId());
    assertEquals(Set.of(tenant.getId()), result.auditedOrganizationIds());
    assertTrue(result.enabled());
    assertTrue(result.canLogin());
    verify(userRepo, times(1)).save(argThat(u -> u.getTenant() == tenant));
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
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("AUDITOR"), null);

    assertThrows(RuntimeException.class, () -> service.update(user.getId(), dto));
    verify(keycloakAdmin, never()).assignRealmRoles(any(), any());
  }

  @Test
  void createWithoutTenantPersistsUser() {
    when(userRepo.existsByEmail("solo@test.com")).thenReturn(false);
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    CreateUserDto dto = new CreateUserDto("solo@test.com", "N", "L", null, null, "Secreto123!");
    UserDto result = service.create(dto);

    assertNull(result.tenantId());
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
    when(orgRepo.findById(tenant.getId())).thenReturn(Optional.of(tenant));

    CreateUserDto dto =
        new CreateUserDto(
            "upd@test.com", "Nuevo", "Apellido", tenant.getId(), Set.of("VIEWER"), null);
    UserDto result = service.update(user.getId(), dto);

    assertEquals("upd@test.com", result.email());
    assertEquals("Nuevo", result.firstName());
    assertEquals("Apellido", result.lastName());
    assertEquals(tenant.getId(), result.tenantId());
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
        new CreateUserDto("admin@prisma.local", "Admin", "PRISMA", null, null, Set.of(), null, false);

    assertThrows(InvalidRequestException.class, () -> service.update(systemAdminId, dto));
  }

  @Test
  void createWithEnabledFalseSyncsDisabledToKeycloak() {
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    UUID keycloakId = UUID.randomUUID();
    when(keycloakAdmin.createUser(eq("new@test.com"), any(), any(), any(), any()))
        .thenReturn(keycloakId);

    CreateUserDto dto =
        new CreateUserDto("new@test.com", "New", "User", null, Set.of("VIEWER"), Set.of(), "Pass1234!", false);
    UserDto result = service.create(dto);

    assertFalse(result.enabled());
    // createUser() en Keycloak siempre nace habilitado -- hace falta un segundo llamado para
    // sincronizar que en realidad se lo pidió deshabilitado desde el alta.
    verify(keycloakAdmin, times(1))
        .updateProfile(keycloakId, "new@test.com", "New", "User", false);
  }

  // --- ORG_RESPONSIBLE como "administrador" de su propia organización -----------------------

  @Test
  void createAsOrgAdminForcesOwnTenantIgnoringDtoTenantId() {
    UUID otherOrgId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);
    when(orgRepo.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(keycloakAdmin.createUser(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());

    // El DTO pide OTRA organización (otherOrgId): un ORG_RESPONSIBLE no puede crear usuarios
    // fuera de la suya, así que se ignora y se usa currentTenantId() en su lugar.
    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com", "N", "L", otherOrgId, Set.of("VIEWER"), null, "Secreto123!");
    UserDto result = service.create(dto);

    assertEquals(tenant.getId(), result.tenantId());
    verify(orgRepo, never()).findById(otherOrgId);
  }

  @Test
  void createAsOrgAdminRejectsGrantingPlatformAdminRole() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com", "N", "L", null, Set.of("PRISMA_ADMIN"), null, "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.create(dto));
    verify(userRepo, never()).save(any());
    verify(keycloakAdmin, never()).createUser(any(), any(), any(), any(), any());
  }

  @Test
  void createAsOrgAdminRejectsAuditingAnotherOrganization() {
    UUID otherOrgId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    when(userRepo.existsByEmail("new@test.com")).thenReturn(false);

    CreateUserDto dto =
        new CreateUserDto(
            "new@test.com",
            "N",
            "L",
            null,
            Set.of("AUDITOR"),
            Set.of(otherOrgId),
            "Secreto123!");

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.create(dto));
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
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.create(dto));
    verify(userRepo, never()).save(any());
  }

  @Test
  void updateAsOrgAdminRejectsUserFromAnotherTenant() {
    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra").build();
    user.setTenant(otherOrg);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
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
    user.setTenant(tenant);
    UUID otherOrgId = UUID.randomUUID();
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());

    CreateUserDto dto = new CreateUserDto("u@test.com", "Jane", "Doe", otherOrgId, null, null);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.update(user.getId(), dto));
  }

  @Test
  void updateAsOrgAdminRejectsGrantingPlatformAdminRole() {
    user.setTenant(tenant);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());

    CreateUserDto dto =
        new CreateUserDto("u@test.com", "Jane", "Doe", null, Set.of("PRISMA_ADMIN"), null);

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.update(user.getId(), dto));
  }

  @Test
  void updateAsOrgAdminAllowsEditingOwnTenantUser() {
    user.setTenant(tenant);
    UUID keycloakId = UUID.randomUUID();
    user.setKeycloakId(keycloakId);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());

    CreateUserDto dto =
        new CreateUserDto(
            "u@test.com", "Jane", "Doe", tenant.getId(), Set.of("VIEWER"), null);
    UserDto result = service.update(user.getId(), dto);

    assertEquals(Set.of("VIEWER"), result.roles());
    verify(keycloakAdmin, times(1)).assignRealmRoles(keycloakId, Set.of("VIEWER"));
  }

  @Test
  void deleteAsOrgAdminRejectsUserFromAnotherTenant() {
    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra").build();
    user.setTenant(otherOrg);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
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
    user.setTenant(tenant);
    when(userRepo.findById(user.getId())).thenReturn(Optional.of(user));
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());

    service.delete(user.getId());

    verify(userRepo, times(1)).delete(user);
  }
}
