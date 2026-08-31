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
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrganizationServiceTest {

  @Mock private OrganizationRepository repo;
  @Mock private AuditLogService auditLog;
  @Mock private CurrentUserService currentUser;

  private OrganizationService service;
  private Organization org;
  private User responsible;

  @BeforeEach
  void setUp() {
    service = new OrganizationService(repo, auditLog, currentUser);
    // Por defecto los tests existentes asumen la vista sin restricciones de PRISMA_ADMIN; los
    // tests de aislamiento multi-tenant de mas abajo pisan este stub con isPrismaAdmin=false.
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    responsible = User.builder().id(UUID.randomUUID()).email("r@test.com").build();
    org =
        Organization.builder()
            .id(UUID.randomUUID())
            .name("Acme")
            .rut("NIT-1")
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
    when(repo.search(any(), any())).thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list("acme", 0, 10);

    assertEquals(1, result.total());
    OrganizationDto dto = result.data().get(0);
    assertEquals(org.getId(), dto.id());
    assertEquals("Acme", dto.name());
    assertEquals("NIT-1", dto.rut());
    assertEquals("TECH", dto.sector());
    assertEquals("SMALL", dto.size());
    assertEquals(responsible.getId(), dto.responsibleId());
    assertTrue(dto.enabled());
    assertEquals(org.getCreatedAt(), dto.createdAt());
  }

  @Test
  void listMapsNullResponsible() {
    Organization bare = Organization.builder().id(UUID.randomUUID()).name("X").rut("N2").build();
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.search(isNull(), any())).thenReturn(new PageImpl<>(List.of(bare), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertNull(result.data().get(0).responsibleId());
  }

  @Test
  void listScopesToOwnTenantForNonAdminNonAuditorRoles() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(org.getId());
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.searchByIdIn(eq(Set.of(org.getId())), any(), any()))
        .thenReturn(new PageImpl<>(List.of(org), pr, 1));

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals(1, result.total());
    verify(repo, never()).search(any(), any());
  }

  @Test
  void listReturnsEmptyForNonAdminWithoutTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(null);

    PaginatedDto<OrganizationDto> result = service.list(null, 0, 10);

    assertEquals(0, result.total());
    verify(repo, never()).searchByIdIn(any(), any(), any());
  }

  @Test
  void listScopesToAuditedOrganizationsForAuditor() {
    UUID auditedId = UUID.randomUUID();
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.auditedOrganizationIds()).thenReturn(Set.of(auditedId));
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(repo.searchByIdIn(eq(Set.of(auditedId)), any(), any()))
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

    CreateOrganizationDto dto = new CreateOrganizationDto("NewCo", "NIT-9", "FIN", "LARGE", null);
    OrganizationDto result = service.create(dto);

    assertEquals("NewCo", result.name());
    assertEquals("NIT-9", result.rut());
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
        new CreateOrganizationDto("NewName", "NIT-X", "HEALTH", "MEDIUM", null);
    OrganizationDto result = service.update(org.getId(), dto);

    assertEquals("NewName", result.name());
    assertEquals("NIT-X", result.rut());
    assertEquals("HEALTH", result.sector());
    assertEquals("MEDIUM", result.size());
  }

  @Test
  void createNormalizesBlankRutToNull() {
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto = new CreateOrganizationDto("SinRut", "   ", "FIN", "LARGE", null);
    OrganizationDto result = service.create(dto);

    // No "" -- una segunda organización sin RUT tiene que poder crearse sin chocar contra la
    // UNIQUE constraint (que sí distingue NULL de "", ver el comentario en
    // OrganizationService.blankToNull).
    assertNull(result.rut());
  }

  @Test
  void updateNormalizesBlankRutToNull() {
    when(repo.findById(org.getId())).thenReturn(Optional.of(org));
    when(repo.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateOrganizationDto dto = new CreateOrganizationDto("NewName", "", "HEALTH", "MEDIUM", null);
    OrganizationDto result = service.update(org.getId(), dto);

    assertNull(result.rut());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    CreateOrganizationDto dto = new CreateOrganizationDto("A", "B", null, null, null);

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

  @Test
  void deleteThrowsWhenOrganizationMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
    verify(repo, never()).save(any());
  }
}
