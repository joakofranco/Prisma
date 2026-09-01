package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
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
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.CatalogVersion;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.web.dto.Dto.AuditLogDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogServiceTest {

  @Mock private AuditLogRepository auditRepo;
  @Mock private UserRepository userRepo;
  @Mock private OrganizationRepository orgRepo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogVersionRepository catalogVersionRepo;
  @Mock private CurrentUserService currentUser;

  private AuditLogService service;
  private Organization tenant;
  private User actor;
  private AuditLog logEntry;

  @BeforeEach
  void setUp() {
    service =
        new AuditLogService(
            auditRepo, userRepo, orgRepo, evaluationRepo, catalogVersionRepo, currentUser);

    tenant =
        Organization.builder().id(UUID.randomUUID()).name("Acme").rut("NIT-1").enabled(true)
            .build();
    actor =
        User.builder()
            .id(UUID.randomUUID())
            .email("actor@test.com")
            .firstName("Jane")
            .lastName("Doe")
            .tenant(tenant)
            .build();
    logEntry =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .tenantId(tenant.getId())
            .action("CREATE")
            .resource("user:" + UUID.randomUUID())
            .createdAt(OffsetDateTime.now())
            .build();
  }

  @Test
  void prismaAdminSeesAllTenants() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant));

    PaginatedDto<AuditLogDto> result = service.list(null, null, 0, 20);

    assertEquals(1, result.total());
    AuditLogDto dto = result.data().get(0);
    assertEquals("actor@test.com", dto.userEmail());
    assertEquals("Acme", dto.tenantName());
    assertEquals("CREATE", dto.action());
    // PRISMA_ADMIN: se consulta sin acotar por tenant.
    verify(auditRepo).search(isNull(), isNull(), isNull(), any());
  }

  @Test
  void orgResponsibleIsScopedToOwnTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(eq(tenant.getId()), isNull(), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant));

    PaginatedDto<AuditLogDto> result = service.list(null, null, 0, 20);

    assertEquals(1, result.total());
    verify(auditRepo).search(eq(tenant.getId()), isNull(), isNull(), any());
  }

  @Test
  void userWithoutTenantGetsEmptyPageInsteadOfEveryTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(null);

    PaginatedDto<AuditLogDto> result = service.list(null, null, 0, 20);

    assertEquals(0, result.total());
    assertTrue(result.data().isEmpty());
    verifyNoInteractions(auditRepo);
  }

  @Test
  void deletedActorOrTenantResolvesToNullNamesInsteadOfFailing() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    // El usuario/organización de la acción ya no existen -- findAllById no los devuelve.
    when(userRepo.findAllById(any())).thenReturn(List.of());
    when(orgRepo.findAllById(any())).thenReturn(List.of());

    AuditLogDto dto = service.list(null, null, 0, 20).data().get(0);

    assertNull(dto.userEmail());
    assertNull(dto.tenantName());
    assertEquals(logEntry.getUserId(), dto.userId());
    assertEquals(logEntry.getTenantId(), dto.tenantId());
  }

  @Test
  void resolvesResourceNameForEachKnownResourceType() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra Org").build();
    Evaluation eval =
        Evaluation.builder().id(UUID.randomUUID()).name("Evaluación Q3").build();
    CatalogVersion catalogVersion =
        CatalogVersion.builder().id(UUID.randomUUID()).version("5.0").label("MCU 5.0").build();

    AuditLog onOrg =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .action("UPDATE")
            .resource("organization:" + otherOrg.getId())
            .createdAt(OffsetDateTime.now())
            .build();
    AuditLog onEvaluation =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .action("CREATE")
            .resource("evaluation:" + eval.getId())
            .createdAt(OffsetDateTime.now())
            .build();
    AuditLog onCatalogVersion =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .action("CREATE")
            .resource("catalog_version:" + catalogVersion.getId())
            .createdAt(OffsetDateTime.now())
            .build();
    // Recurso singleton, sin id propio -- no debería intentar resolver nombre ni fallar.
    AuditLog onSingleton =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .action("UPDATE")
            .resource("email-settings")
            .createdAt(OffsetDateTime.now())
            .build();

    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any()))
        .thenReturn(
            new PageImpl<>(List.of(onOrg, onEvaluation, onCatalogVersion, onSingleton), pr, 4));
    when(orgRepo.findAllById(any())).thenReturn(List.of(otherOrg));
    when(evaluationRepo.findAllById(any())).thenReturn(List.of(eval));
    when(catalogVersionRepo.findAllById(any())).thenReturn(List.of(catalogVersion));

    List<AuditLogDto> dtos = service.list(null, null, 0, 20).data();

    assertEquals("Otra Org", dtos.get(0).resourceName());
    assertEquals("Evaluación Q3", dtos.get(1).resourceName());
    assertEquals("MCU 5.0", dtos.get(2).resourceName());
    assertNull(dtos.get(3).resourceName());
  }

  @Test
  void resourceNameIsNullWhenReferencedEntityWasDeleted() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    // logEntry.resource() es "user:<id-random>" -- ningún findAllById lo devuelve, como si el
    // usuario referenciado ya no existiera.
    when(userRepo.findAllById(any())).thenReturn(List.of());

    AuditLogDto dto = service.list(null, null, 0, 20).data().get(0);

    assertNull(dto.resourceName());
    assertEquals(logEntry.getResource(), dto.resource());
  }

  @Test
  void recordLoginFailureLinksActorAndTenantWhenEmailBelongsToRealUser() {
    when(userRepo.findByEmail("actor@test.com")).thenReturn(java.util.Optional.of(actor));
    OffsetDateTime when = OffsetDateTime.now().minusMinutes(5);

    service.recordLoginFailure("actor@test.com", "203.0.113.5", when);

    var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
    verify(auditRepo).save(captor.capture());
    AuditLog saved = captor.getValue();
    assertEquals("LOGIN_FAILED", saved.getAction());
    assertEquals("actor@test.com", saved.getResource());
    assertEquals("203.0.113.5", saved.getIpAddress());
    assertEquals(when, saved.getCreatedAt());
    // El email SÍ pertenece a un usuario real: queda linkeado como actor/tenant para que el
    // aislamiento multi-tenant de list() lo muestre a su ORG_RESPONSIBLE.
    assertEquals(actor.getId(), saved.getUserId());
    assertEquals(tenant.getId(), saved.getTenantId());
  }

  @Test
  void recordLoginFailureLeavesActorAndTenantNullWhenEmailIsUnknown() {
    when(userRepo.findByEmail("nadie@test.com")).thenReturn(java.util.Optional.empty());

    service.recordLoginFailure("nadie@test.com", "203.0.113.5", OffsetDateTime.now());

    var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
    verify(auditRepo).save(captor.capture());
    AuditLog saved = captor.getValue();
    assertEquals("nadie@test.com", saved.getResource());
    assertNull(saved.getUserId());
    assertNull(saved.getTenantId());
  }
}
