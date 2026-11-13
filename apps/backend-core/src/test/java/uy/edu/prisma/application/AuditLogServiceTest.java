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
import uy.edu.prisma.web.dto.Dto.EvaluationAuditCycleEventDto;
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
        Organization.builder()
            .id(UUID.randomUUID())
            .name("Acme")
            .identifier("NIT-1")
            .enabled(true)
            .build();
    actor =
        User.builder()
            .id(UUID.randomUUID())
            .email("actor@test.com")
            .firstName("Jane")
            .lastName("Doe")
            .organizations(java.util.Set.of(tenant))
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
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
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
    verify(auditRepo).search(isNull(), isNull(), isNull(), any(), any(), any());
  }

  @Test
  void orgResponsibleIsScopedToOwnTenant() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    PageRequest pr = PageRequest.of(0, 20, Sort.by("createdAt").descending());
    when(auditRepo.search(eq(tenant.getId()), isNull(), isNull(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant));

    PaginatedDto<AuditLogDto> result = service.list(null, null, 0, 20);

    assertEquals(1, result.total());
    verify(auditRepo).search(eq(tenant.getId()), isNull(), isNull(), any(), any(), any());
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
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
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
  void exportCsvIncludesHeaderAndMatchingRows() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    PageRequest pr = PageRequest.of(0, 50_000, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant));

    String csv = service.exportCsv(null, null);

    String[] lines = csv.split("\r\n");
    assertEquals("Fecha,Usuario,Email,Organizacion,Accion,Recurso,IP", lines[0]);
    assertEquals(2, lines.length);
    assertTrue(lines[1].contains("Jane Doe"));
    assertTrue(lines[1].contains("actor@test.com"));
    assertTrue(lines[1].contains("Acme"));
    assertTrue(lines[1].contains("CREATE"));
  }

  @Test
  void exportCsvScopesToOwnTenantForNonAdmin() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(tenant.getId());
    PageRequest pr = PageRequest.of(0, 50_000, Sort.by("createdAt").descending());
    when(auditRepo.search(eq(tenant.getId()), isNull(), isNull(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));
    when(orgRepo.findAllById(any())).thenReturn(List.of(tenant));

    service.exportCsv(null, null);

    verify(auditRepo).search(eq(tenant.getId()), isNull(), isNull(), any(), any(), any());
  }

  @Test
  void exportCsvWithoutTenantReturnsOnlyHeader() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(null);

    String csv = service.exportCsv(null, null);

    assertEquals("Fecha,Usuario,Email,Organizacion,Accion,Recurso,IP\r\n", csv);
    verifyNoInteractions(auditRepo);
  }

  @Test
  void exportCsvQuotesFieldsContainingComma() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    Organization orgWithComma =
        Organization.builder().id(UUID.randomUUID()).name("Acme, Inc.").build();
    User actorWithComma =
        User.builder()
            .id(UUID.randomUUID())
            .email("x@test.com")
            .firstName("Jane")
            .lastName("Doe")
            .build();
    AuditLog log =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actorWithComma.getId())
            .tenantId(orgWithComma.getId())
            .action("CREATE")
            .resource("user:" + UUID.randomUUID())
            .createdAt(OffsetDateTime.now())
            .build();
    PageRequest pr = PageRequest.of(0, 50_000, Sort.by("createdAt").descending());
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(log), pr, 1));
    when(userRepo.findAllById(any())).thenReturn(List.of(actorWithComma));
    when(orgRepo.findAllById(any())).thenReturn(List.of(orgWithComma));

    String csv = service.exportCsv(null, null);

    assertTrue(csv.contains("\"Acme, Inc.\""));
  }

  @Test
  void resolvesResourceNameForEachKnownResourceType() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    Organization otherOrg = Organization.builder().id(UUID.randomUUID()).name("Otra Org").build();
    Evaluation eval = Evaluation.builder().id(UUID.randomUUID()).name("Evaluación Q3").build();
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
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
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
    when(auditRepo.search(isNull(), isNull(), isNull(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(logEntry), pr, 1));
    // logEntry.resource() es "user:<id-random>" -- ningún findAllById lo devuelve, como si el
    // usuario referenciado ya no existiera.
    when(userRepo.findAllById(any())).thenReturn(List.of());

    AuditLogDto dto = service.list(null, null, 0, 20).data().get(0);

    assertNull(dto.resourceName());
    assertEquals(logEntry.getResource(), dto.resource());
  }

  @Test
  void recordIndependentlySavesTheSameEntryAsRecord() {
    service.recordIndependently("EVIDENCE_SCAN_CLEAN", "evaluation:1", "{\"size\":1}");

    var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
    verify(auditRepo).save(captor.capture());
    assertEquals("EVIDENCE_SCAN_CLEAN", captor.getValue().getAction());
    assertEquals("evaluation:1", captor.getValue().getResource());
    assertEquals("{\"size\":1}", captor.getValue().getPayload());
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

  @Test
  void evaluationAuditTrailGroupsEventsByCycleAndResolvesActorNames() {
    Evaluation eval = Evaluation.builder().id(UUID.randomUUID()).organization(tenant).build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(java.util.Optional.of(eval));

    OffsetDateTime t0 = OffsetDateTime.now().minusDays(3);
    AuditLog sentToAudit =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .action("UPDATE_STATUS")
            .resource("evaluation:" + eval.getId())
            .payload("{\"status\":\"READY_FOR_AUDIT\"}")
            .createdAt(t0)
            .build();
    AuditLog returned =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .action("UPDATE_STATUS")
            .resource("evaluation:" + eval.getId())
            .payload("{\"status\":\"RETURNED\"}")
            .createdAt(t0.plusHours(1))
            .build();
    AuditLog resentToAudit =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .action("UPDATE_STATUS")
            .resource("evaluation:" + eval.getId())
            .payload("{\"status\":\"READY_FOR_AUDIT\"}")
            .createdAt(t0.plusHours(2))
            .build();
    when(auditRepo.findByResourceAndActionInOrderByCreatedAtAsc(
            "evaluation:" + eval.getId(), List.of("UPDATE_STATUS", "CERTIFY")))
        .thenReturn(List.of(sentToAudit, returned, resentToAudit));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));

    List<EvaluationAuditCycleEventDto> trail = service.getEvaluationAuditTrail(eval.getId());

    assertEquals(3, trail.size());
    assertEquals("READY_FOR_AUDIT", trail.get(0).status());
    assertEquals(1, trail.get(0).cycleNumber());
    assertEquals("Jane Doe", trail.get(0).performedByName());
    assertEquals("RETURNED", trail.get(1).status());
    assertEquals(1, trail.get(1).cycleNumber());
    assertEquals("READY_FOR_AUDIT", trail.get(2).status());
    assertEquals(2, trail.get(2).cycleNumber());
    verify(currentUser).assertOrganizationAccess(tenant.getId());
  }

  @Test
  void evaluationAuditTrailReadsTheStatusAsPostgresReturnsTheJsonb() {
    Evaluation eval = Evaluation.builder().id(UUID.randomUUID()).organization(tenant).build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(java.util.Optional.of(eval));
    // JSONB normalizado por Postgres: con espacio después de «:».
    AuditLog sentToAudit =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .userId(actor.getId())
            .action("UPDATE_STATUS")
            .resource("evaluation:" + eval.getId())
            .payload("{\"status\": \"READY_FOR_AUDIT\"}")
            .createdAt(OffsetDateTime.now())
            .build();
    when(auditRepo.findByResourceAndActionInOrderByCreatedAtAsc(
            "evaluation:" + eval.getId(), List.of("UPDATE_STATUS", "CERTIFY")))
        .thenReturn(List.of(sentToAudit));
    when(userRepo.findAllById(any())).thenReturn(List.of(actor));

    List<EvaluationAuditCycleEventDto> trail = service.getEvaluationAuditTrail(eval.getId());

    assertEquals("READY_FOR_AUDIT", trail.get(0).status());
    assertEquals(1, trail.get(0).cycleNumber());
  }

  @Test
  void evaluationAuditTrailThrowsWhenEvaluationDoesNotExist() {
    UUID missingId = UUID.randomUUID();
    when(evaluationRepo.findById(missingId)).thenReturn(java.util.Optional.empty());

    assertThrows(
        uy.edu.prisma.domain.exception.ResourceNotFoundException.class,
        () -> service.getEvaluationAuditTrail(missingId));
  }

  // ---- PRI-368: el buscador entiende lo que ve el usuario y los códigos internos ----

  @Test
  void elTextoDeBusquedaSePliegaYSeTraduceAlasEtiquetasEnEspanol() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(auditRepo.search(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of()));

    service.list("  Inicio de SESIÓN ", null, 0, 20);

    var pattern = org.mockito.ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    var actions = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
    verify(auditRepo)
        .search(isNull(), isNull(), pattern.capture(), actions.capture(), any(), any());
    // Sin tildes ni mayúsculas, con comodines y sin los espacios de los bordes.
    assertEquals("%inicio de sesion%", pattern.getValue());
    // «Inicio de Sesión» y «Inicio de Sesión Fallido» contienen el texto: las dos acciones.
    assertEquals(
        java.util.Set.of("LOGIN", "LOGIN_FAILED"), new java.util.HashSet<>(actions.getValue()));
  }

  @Test
  void elTipoDeRecursoEnEspanolSeTraduceAlTipoInterno() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(auditRepo.search(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of()));

    service.list("usuario", null, 0, 20);

    @SuppressWarnings("unchecked")
    var types = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
    verify(auditRepo).search(isNull(), isNull(), eq("%usuario%"), any(), types.capture(), any());
    assertEquals(java.util.Set.of("user"), new java.util.HashSet<>(types.getValue()));
  }

  @Test
  void unUuidNoTraduceAningunaEtiqueta() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(auditRepo.search(any(), any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of()));
    String uuid = UUID.randomUUID().toString();

    service.list(uuid, null, 0, 20);

    @SuppressWarnings("unchecked")
    var actions = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
    verify(auditRepo)
        .search(isNull(), isNull(), eq("%" + uuid + "%"), actions.capture(), any(), any());
    // La lista nunca queda vacía (el IN de la consulta no la admite) y no coincide con ninguna.
    assertEquals(1, actions.getValue().size());
    assertFalse(actions.getValue().contains("LOGIN"));
  }

  @Test
  void foldQuitaTildesYMayusculas() {
    assertEquals("organizacion", AuditLogService.fold("Organización"));
    assertEquals("nino", AuditLogService.fold("NIÑO"));
  }
}
