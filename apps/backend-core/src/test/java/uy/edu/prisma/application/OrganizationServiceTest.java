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
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceTest {

  @Mock private OrganizationRepository repo;
  @Mock private AuditLogService auditLog;
  @Mock private CurrentUserService currentUser;
  @Mock private UserRepository userRepo;
  @Mock private KeycloakAdminClient keycloakAdmin;

  private OrganizationService service;
  private Organization org;
  private User responsible;

  @BeforeEach
  void setUp() {
    service = new OrganizationService(repo, auditLog, currentUser, userRepo, keycloakAdmin);
    // Por defecto los tests existentes asumen la vista sin restricciones de PRISMA_ADMIN; los
    // tests de aislamiento multi-tenant de mas abajo pisan este stub con isPrismaAdmin=false.
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);

    responsible = User.builder().id(UUID.randomUUID()).email("r@test.com").build();
    org =
        Organization.builder()
            .id(UUID.randomUUID())
            .name("Acme")
            .identifier("NIT-1")
            .sector("TECH")
            .size("SMALL")
            .responsible(responsible)
            .enabled(true)
            .createdAt(OffsetDateTime.now())
            .build();
  }

  @Test
  void listReturnsPaginatedResults() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list("acme", 0, 10);

    assertEquals(1, result.total());
    OrganizationDto dto = result.data().get(0);
    assertEquals(org.getId(), dto.id());
    assertEquals("Acme", dto.name());
    assertEquals("NIT-1", dto.identifier());
    assertEquals("TECH", dto.sector());
    assertEquals("SMALL", dto.size());
    assertEquals(responsible.getId(), dto.responsibleId());
    // Sin nombre cargado, el responsable se muestra por su email (PRI-367).
    assertEquals("r@test.com", dto.responsibleName());
    assertTrue(dto.enabled());
    assertEquals(org.getCreatedAt(), dto.createdAt());
  }

  @Test
  void theResponsibleNameIsFirstAndLastName() {
    responsible.setFirstName("Ana");
    responsible.setLastName("Pérez");
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.search(any(), any(), any())).thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals("Ana Pérez", result.data().get(0).responsibleName());
  }

  @Test
  void listMapsNullResponsible() {
    Organization bare =
        Organization.builder().id(UUID.randomUUID()).name("X").identifier("N2").build();
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.search(isNull(), any(), any())).thenReturn(new PageImpl<>(List.of(bare), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertNull(result.data().get(0).responsibleId());
    assertNull(result.data().get(0).responsibleName());
  }

  @Test
  void listScopesToOwnTenantForNonAdminNonAuditorRoles() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.organizationIds()).thenReturn(Set.of(org.getId()));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.searchByIdIn(eq(Set.of(org.getId())), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
    verify(repo, never()).search(any(), any(), any());
  }

  @Test
  void listReturnsEmptyForNonAdminWithoutTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.organizationIds()).thenReturn(Set.of());

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals(0, result.total());
    verify(repo, never()).searchByIdIn(any(), any(), any(), any());
  }

  @Test
  void listScopesToAuditedOrganizationsForAuditor() {
    UUID auditedId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.organizationIds()).thenReturn(Set.of(auditedId));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.searchByIdIn(eq(Set.of(auditedId)), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
  }

  @Test
  void getByIdDeniesAccessOutsideTenant() {
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(org.getId());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.getById(org.getId()));
    verify(repo, never()).findById(any());
  }

  @Test
  void getByIdReturnsDto() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));

    assertEquals(org.getId(), service.getById(org.getId()).id());
  }

  @Test
  void getByIdThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getById(id));
  }

  @Test
  void createPersistsEnabledOrganization() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto =
        new CreateOrganizationDto("NewCo", "NIT-0009", false, "FIN", "LARGE", null);
    OrganizationDto result = service.create(dto);

    assertEquals("NewCo", result.name());
    assertEquals("NIT-0009", result.identifier());
    assertFalse(result.identifierIsRut());
    assertEquals("FIN", result.sector());
    assertEquals("LARGE", result.size());
    assertTrue(result.enabled());
    assertNull(result.responsibleId());
    verify(repo, times(1)).save(argThat(o -> o.getId() == null));
  }

  @Test
  void updateModifiesOrganization() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto =
        new CreateOrganizationDto("NewName", "NIT-XXXX", false, "HEALTH", "MEDIUM", null);
    OrganizationDto result = service.update(org.getId(), dto);

    assertEquals("NewName", result.name());
    assertEquals("NIT-XXXX", result.identifier());
    assertEquals("HEALTH", result.sector());
    assertEquals("MEDIUM", result.size());
  }

  @Test
  void createNormalizesBlankIdentifierToNull() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto =
        new CreateOrganizationDto("SinId", "   ", false, "FIN", "LARGE", null);
    OrganizationDto result = service.create(dto);

    // No "" -- una segunda organización sin identificador tiene que poder crearse sin chocar
    // contra la UNIQUE constraint (que sí distingue NULL de "", ver el comentario en
    // OrganizationService.blankToNull).
    assertNull(result.identifier());
  }

  @Test
  void updateNormalizesBlankIdentifierToNull() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto =
        new CreateOrganizationDto("NewName", "", false, "HEALTH", "MEDIUM", null);
    OrganizationDto result = service.update(org.getId(), dto);

    assertNull(result.identifier());
  }

  @Test
  void theRutCheckboxWithoutTextLeavesNoIdentifierAndNoRutFlag() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationDto result =
        service.create(new CreateOrganizationDto("SinId", "  ", true, "FIN", "LARGE", null));

    assertNull(result.identifier());
    assertFalse(result.identifierIsRut());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    CreateOrganizationDto dto = new CreateOrganizationDto("A", "B", false, null, null, null);

    assertThrows(RuntimeException.class, () -> service.update(id, dto));
  }

  @Test
  void deleteIsSoftAndPreservesTheOrganizationRow() {
    // No debe ser un DELETE real: users.tenant_id y evaluations.organization_id referencian
    // organizations sin ON DELETE CASCADE, asi que borrar la fila revienta con una violacion de
    // integridad referencial en cuanto tiene algun usuario o evaluacion -- y ademas se perderia
    // el historico, que es justo lo que se pide preservar.
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));

    service.delete(org.getId());

    verify(repo, never()).deleteById(any());
    verify(repo, times(1)).save(org);
    assertFalse(org.getEnabled());
  }

  // ---- Identificador: RUT (casilla «es un RUT») y largo mínimo ----

  private OrganizationDto createWith(String identifier, boolean isRut) {
    return service.create(
        new CreateOrganizationDto("Nueva", identifier, isRut, "TECH", "SMALL", null));
  }

  private uy.edu.prisma.domain.exception.InvalidRequestException assertInvalid(
      String identifier, boolean isRut) {
    uy.edu.prisma.domain.exception.InvalidRequestException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.InvalidRequestException.class,
            () -> createWith(identifier, isRut),
            identifier);
    assertEquals("identifier", ex.getField());
    verify(repo, never()).save(any());
    return ex;
  }

  @Test
  void aValidRutIsStoredNormalizedAndFlaggedWhateverTheWayItWasTyped() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    for (String typed :
        List.of("216166580014", "21-616658-001-4", " 21 616658 001 4 ", "UY 21.616658.001-4")) {
      OrganizationDto result = createWith(typed, true);

      assertEquals("21-616658-001-4", result.identifier(), typed);
      assertTrue(result.identifierIsRut(), typed);
    }
  }

  @Test
  void anInvalidRutIsRejectedOnTheIdentifierField() {
    for (String bad :
        List.of(
            "210303670014", // dígito verificador incorrecto
            "21616658001", // 11 dígitos
            "2161665800144", // 13 dígitos
            "006166580014", // prefijo 00
            "236166580014", // prefijo 23
            "210000000014", // secuencial 000000
            "216166580024", // grupo distinto de 001
            "21616658001A", // no numérico
            "NIT-12345678")) {
      assertEquals(OrganizationMessages.INVALID_RUT, assertInvalid(bad, true).getMessage(), bad);
    }
  }

  @Test
  void aRutIsNotValidatedWhenTheCheckboxIsOff() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    // Mismo texto que arriba es inválido como RUT, pero como identificador libre se acepta tal
    // cual.
    OrganizationDto result = createWith("210303670014", false);

    assertEquals("210303670014", result.identifier());
    assertFalse(result.identifierIsRut());
  }

  @Test
  void aFreeIdentifierNeedsAtLeastEightCharactersAndIsTrimmed() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    assertEquals(
        OrganizationMessages.IDENTIFIER_TOO_SHORT, assertInvalid("1234567", false).getMessage());
    assertEquals("12345678", createWith("12345678", false).identifier());
    assertEquals("ABC-12345", createWith("  ABC-12345  ", false).identifier());
  }

  @Test
  void aFreeIdentifierCannotExceedTheColumnLength() {
    assertEquals(
        OrganizationMessages.IDENTIFIER_TOO_LONG,
        assertInvalid("X".repeat(OrganizationMessages.MAX_IDENTIFIER_LENGTH + 1), false)
            .getMessage());
  }

  @Test
  void updateAlsoValidatesAndNormalizesTheRut() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    OrganizationDto result =
        service.update(
            org.getId(),
            new CreateOrganizationDto("Acme", "211003420017", true, "TECH", "SMALL", null));

    assertEquals("21-100342-001-7", result.identifier());
    assertTrue(result.identifierIsRut());

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () ->
            service.update(
                org.getId(),
                new CreateOrganizationDto("Acme", "211003420018", true, "TECH", "SMALL", null)));
  }

  // ---- Identificador duplicado (C11): se controla siempre, sea o no un RUT ----

  private Organization other(String name, String identifier) {
    return Organization.builder()
        .id(UUID.randomUUID())
        .name(name)
        .identifier(identifier)
        .enabled(true)
        .build();
  }

  @Test
  void createRejectsADuplicateIdentifierWithAClearMessageAndTheFieldName() {
    Organization existing = other("Acme", "ABC.12345-X");
    when(repo.findByNormalizedIdentifier("ABC12345X")).thenReturn(List.of(existing));

    uy.edu.prisma.domain.exception.ConflictException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.ConflictException.class,
            () -> createWith("ABC.12345-X", false));

    assertEquals("identifier", ex.getField());
    assertTrue(
        ex.getMessage().contains("Ya existe una organización con el identificador ABC.12345-X"));
    assertTrue(ex.getMessage().contains("«Acme»"));
    verify(repo, never()).save(any());
  }

  @Test
  void aRutIsDetectedAsDuplicateOfTheSameNumberStoredWithoutTheRutFlag() {
    Organization existing = other("Acme", "216166580014");
    when(repo.findByNormalizedIdentifier("216166580014")).thenReturn(List.of(existing));

    uy.edu.prisma.domain.exception.ConflictException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.ConflictException.class,
            () -> createWith("21-616658-001-4", true));

    assertEquals("identifier", ex.getField());
    assertTrue(ex.getMessage().contains("21-616658-001-4"), ex.getMessage());
  }

  @Test
  void aFreeIdentifierIsDetectedAsDuplicateOfAStoredRut() {
    Organization existing = other("Acme", "21-616658-001-4");
    when(repo.findByNormalizedIdentifier("216166580014")).thenReturn(List.of(existing));

    assertThrows(
        uy.edu.prisma.domain.exception.ConflictException.class,
        () -> createWith(" 216166580014 ", false));
  }

  @Test
  void createWithAnUnusedIdentifierOrNoIdentifierIsNotChecked() {
    when(repo.findByNormalizedIdentifier(any())).thenReturn(List.of());
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    createWith("55555555", false);
    createWith(null, false);
    createWith("   ", false);

    // Solo el primero consulta: un identificador vacío es opcional y no puede estar "duplicado".
    verify(repo, times(1)).findByNormalizedIdentifier(any());
  }

  @Test
  void updateAllowsKeepingTheOrganizationsOwnIdentifier() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(org)).thenReturn(org);
    when(repo.findByNormalizedIdentifier("NIT00001")).thenReturn(List.of(org));

    assertDoesNotThrow(
        () ->
            service.update(
                org.getId(),
                new CreateOrganizationDto("Acme", "NIT-0001", false, "TECH", "SMALL", null)));
  }

  @Test
  void updateRejectsTakingAnotherOrganizationsIdentifier() {
    Organization existing = other("Otra", "99999999");
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.findByNormalizedIdentifier("99999999")).thenReturn(List.of(existing));

    uy.edu.prisma.domain.exception.ConflictException ex =
        assertThrows(
            uy.edu.prisma.domain.exception.ConflictException.class,
            () ->
                service.update(
                    org.getId(),
                    new CreateOrganizationDto("Acme", "99999999", false, "TECH", "SMALL", null)));

    assertEquals("identifier", ex.getField());
    verify(repo, never()).save(any());
  }

  @Test
  void normalizeIdentifierIgnoresSeparatorsSpacesAndCase() {
    assertEquals("212345670", OrganizationService.normalizeIdentifier(" 21.234.567-0 "));
    assertEquals("12345678K", OrganizationService.normalizeIdentifier("12.345.678-k"));
    assertEquals("", OrganizationService.normalizeIdentifier(null));
    assertEquals("", OrganizationService.normalizeIdentifier("  - . "));
  }

  private User orgUser(String email, Set<UserRole> roles, boolean enabled, UUID keycloakId) {
    return User.builder()
        .id(UUID.randomUUID())
        .email(email)
        .firstName("N")
        .lastName("L")
        .roles(roles)
        .enabled(enabled)
        .keycloakId(keycloakId)
        .organizations(new java.util.HashSet<>(Set.of(org)))
        .build();
  }

  @Test
  void deleteDisablesOrganizationUsersLocallyAndInKeycloak() {
    UUID kcA = UUID.randomUUID();
    UUID kcB = UUID.randomUUID();
    User a = orgUser("a@t.com", Set.of(UserRole.VIEWER), true, kcA);
    User b = orgUser("b@t.com", Set.of(UserRole.ORG_RESPONSIBLE), true, kcB);
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(a, b));

    service.delete(org.getId());

    assertFalse(org.getEnabled());
    assertFalse(a.getEnabled());
    assertFalse(b.getEnabled());
    verify(keycloakAdmin, times(1)).updateProfile(kcA, "a@t.com", "N", "L", false);
    verify(keycloakAdmin, times(1)).updateProfile(kcB, "b@t.com", "N", "L", false);
    verify(auditLog, times(1))
        .record("DELETE", "organization:" + org.getId(), "{\"usersDisabled\":2}");
  }

  @Test
  void deleteLeavesAuditorsAndGlobalAdminsUntouched() {
    User auditor = orgUser("aud@t.com", Set.of(UserRole.AUDITOR), true, UUID.randomUUID());
    User auditorAndViewer =
        orgUser("av@t.com", Set.of(UserRole.AUDITOR, UserRole.VIEWER), true, UUID.randomUUID());
    User admin = orgUser("adm@t.com", Set.of(UserRole.PRISMA_ADMIN), true, UUID.randomUUID());
    User regular = orgUser("r@t.com", Set.of(UserRole.INTERNAL_EVALUATOR), true, UUID.randomUUID());
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId()))
        .thenReturn(List.of(auditor, auditorAndViewer, admin, regular));

    service.delete(org.getId());

    assertTrue(auditor.getEnabled());
    assertTrue(auditorAndViewer.getEnabled());
    assertTrue(admin.getEnabled());
    assertFalse(regular.getEnabled());
    verify(keycloakAdmin, times(1)).updateProfile(any(), any(), any(), any(), eq(false));
  }

  @Test
  void deleteKeepsUsersWhoStillHaveAnotherActiveOrganization() {
    Organization otherActive =
        Organization.builder().id(UUID.randomUUID()).name("Otra").enabled(true).build();
    User multi = orgUser("m@t.com", Set.of(UserRole.INTERNAL_EVALUATOR), true, UUID.randomUUID());
    multi.setOrganizations(new java.util.HashSet<>(Set.of(org, otherActive)));
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(multi));

    service.delete(org.getId());

    assertTrue(multi.getEnabled());
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
    verify(auditLog, times(1))
        .record("DELETE", "organization:" + org.getId(), "{\"usersDisabled\":0}");
  }

  @Test
  void deleteDisablesMultiOrganizationUsersOnceAllTheirOrganizationsAreInactive() {
    Organization otherInactive =
        Organization.builder().id(UUID.randomUUID()).name("Otra").enabled(false).build();
    User multi = orgUser("m@t.com", Set.of(UserRole.INTERNAL_EVALUATOR), true, UUID.randomUUID());
    multi.setOrganizations(new java.util.HashSet<>(Set.of(org, otherInactive)));
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(multi));

    service.delete(org.getId());

    assertFalse(multi.getEnabled());
    verify(keycloakAdmin, times(1)).updateProfile(any(), eq("m@t.com"), any(), any(), eq(false));
  }

  @Test
  void deleteSkipsAlreadyDisabledUsersAndUsersWithoutKeycloakAccount() {
    User alreadyOff = orgUser("off@t.com", Set.of(UserRole.VIEWER), false, UUID.randomUUID());
    User noKeycloak = orgUser("nk@t.com", Set.of(UserRole.VIEWER), true, null);
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(alreadyOff, noKeycloak));

    service.delete(org.getId());

    assertFalse(noKeycloak.getEnabled());
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
    verify(auditLog, times(1))
        .record("DELETE", "organization:" + org.getId(), "{\"usersDisabled\":1}");
  }

  @Test
  void deleteRollsBackKeycloakChangesWhenOneUserFails() {
    UUID kcA = UUID.randomUUID();
    UUID kcB = UUID.randomUUID();
    User a = orgUser("a@t.com", Set.of(UserRole.VIEWER), true, kcA);
    User b = orgUser("b@t.com", Set.of(UserRole.VIEWER), true, kcB);
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of(a, b));
    doThrow(new IllegalStateException("keycloak caído"))
        .when(keycloakAdmin)
        .updateProfile(kcB, "b@t.com", "N", "L", false);

    assertThrows(IllegalStateException.class, () -> service.delete(org.getId()));

    // El usuario que ya se había deshabilitado en Keycloak vuelve a habilitarse (compensación);
    // la base se revierte con la transacción. Nada de la bitácora de "DELETE".
    verify(keycloakAdmin, times(1)).updateProfile(kcA, "a@t.com", "N", "L", true);
    verify(auditLog, never()).record(eq("DELETE"), any(), any());
  }

  // ---- Reactivar (C8) ----

  @Test
  void enableReactivatesAnInactiveOrganization() {
    org.setEnabled(false);
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(org)).thenReturn(org);
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of());

    OrganizationReactivationDto result = service.enable(org.getId());

    assertTrue(org.getEnabled());
    assertTrue(result.organization().enabled());
    assertEquals(0, result.disabledUsers());
    verify(auditLog, times(1))
        .record("REACTIVATE", "organization:" + org.getId(), "{\"usersStillDisabled\":0}");
  }

  @Test
  void enableDoesNotReEnableUsersAndReportsHowManyStayDisabled() {
    org.setEnabled(false);
    User off1 = orgUser("o1@t.com", Set.of(UserRole.VIEWER), false, UUID.randomUUID());
    User off2 = orgUser("o2@t.com", Set.of(UserRole.ORG_RESPONSIBLE), false, UUID.randomUUID());
    User on = orgUser("on@t.com", Set.of(UserRole.VIEWER), true, UUID.randomUUID());
    User offAuditor = orgUser("oa@t.com", Set.of(UserRole.AUDITOR), false, UUID.randomUUID());
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(org)).thenReturn(org);
    when(userRepo.findByOrganizationId(org.getId()))
        .thenReturn(List.of(off1, off2, on, offAuditor));

    OrganizationReactivationDto result = service.enable(org.getId());

    assertEquals(2, result.disabledUsers());
    assertFalse(off1.getEnabled());
    assertFalse(off2.getEnabled());
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
    verify(auditLog, times(1))
        .record("REACTIVATE", "organization:" + org.getId(), "{\"usersStillDisabled\":2}");
  }

  @Test
  void enableOnAnAlreadyActiveOrganizationIsANoOpWithoutAuditEntry() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(org)).thenReturn(org);
    when(userRepo.findByOrganizationId(org.getId())).thenReturn(List.of());

    service.enable(org.getId());

    assertTrue(org.getEnabled());
    verify(auditLog, never()).record(eq("REACTIVATE"), any(), any());
  }

  @Test
  void enableThrowsWhenOrganizationMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.enable(id));
  }

  @Test
  void deleteThrowsWhenOrganizationMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
    verify(repo, never()).save(any());
  }
}
