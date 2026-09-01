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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private EvaluationResponseRepository respRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private OrganizationRepository orgRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CatalogFunctionRepository functionRepo;
  @Mock private CatalogVersionRepository versionRepo;
  @Mock private CommunityProfileRepository profileRepo;
  @Mock private UserRepository userRepo;
  @Mock private CurrentUserService currentUser;
  @Mock private AuditLogService auditLog;

  private EvaluationService service;
  private Organization org;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    // Ver el comentario en EvaluationService.auditorNamesByOrganization: se llama en cada
    // list()/getById()/create()/updateStatus() sin importar el test -- por defecto no hay ningún
    // auditor asignado, cada test que sí lo necesite lo pisa con su propio stub.
    when(userRepo.findAuditorsForOrganizations(any())).thenReturn(List.of());
    service =
        new EvaluationService(
            evalRepo,
            respRepo,
            matRepo,
            orgRepo,
            controlRepo,
            functionRepo,
            versionRepo,
            profileRepo,
            userRepo,
            currentUser,
            auditLog);

    org = Organization.builder().id(UUID.randomUUID()).name("Acme").rut("N1").build();
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluacion 2026")
            .organization(org)
            .catalogVersion("5.0")
            .status(Evaluation.Status.DRAFT)
            .createdAt(OffsetDateTime.now())
            .build();
  }

  private SubGraph buildSubgraph() {
    CatalogFunction function = CatalogFunction.builder().id(UUID.randomUUID()).name("F1").build();
    CatalogCategory category =
        CatalogCategory.builder().id(UUID.randomUUID()).name("C1").function_(function).build();
    CatalogSubcategory subcategory =
        CatalogSubcategory.builder().id(UUID.randomUUID()).name("S1").category(category).build();
    CatalogRequirement requirement = CatalogRequirement.builder().id(UUID.randomUUID()).build();
    linkRequirementToSubcategory(requirement, subcategory);
    return new SubGraph(function, category, subcategory, requirement);
  }

  // Un Requisito puede estar enlazado a mas de una Subcategoria (ver
  // CatalogRequirementSubcategory) -- agrega el link sin pisar los que ya tenia.
  private void linkRequirementToSubcategory(
      CatalogRequirement requirement, CatalogSubcategory subcategory) {
    CatalogRequirementSubcategory link =
        CatalogRequirementSubcategory.builder()
            .id(UUID.randomUUID())
            .requirement(requirement)
            .subcategory(subcategory)
            .build();
    List<CatalogRequirementSubcategory> links = new java.util.ArrayList<>(requirement.getSubcategoryLinks());
    links.add(link);
    requirement.setSubcategoryLinks(links);
  }

  private record SubGraph(
      CatalogFunction function,
      CatalogCategory category,
      CatalogSubcategory subcategory,
      CatalogRequirement requirement) {}

  @Test
  void listWithStatusAndOrgFilters() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            org.getId(), Evaluation.Status.DRAFT, pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list("DRAFT", org.getId(), 0, 10);

    assertEvaluationDto(result.data().get(0));
    assertEquals(1, result.total());
  }

  @Test
  void listWithStatusOnly() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findByStatusOrderByCreatedAtDesc(Evaluation.Status.APPROVED, pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list("APPROVED", null, 0, 10);

    assertEquals(1, result.total());
  }

  @Test
  void listWithOrgOnly() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findByOrganizationIdOrderByCreatedAtDesc(org.getId(), pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list(null, org.getId(), 0, 10);

    assertEquals(1, result.total());
  }

  @Test
  void listWithoutFilters() {
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findAllByOrderByCreatedAtDesc(pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list(null, null, 0, 10);

    assertEquals(1, result.total());
  }

  // ---- AUDITOR: acotado a las organizaciones que se le asignaron (ya no es un rol global) ----

  @Test
  void listForAuditorWithoutOrgFilterUsesUnionOfAssignedOrganizations() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    UUID otherOrgId = UUID.randomUUID();
    Set<UUID> assigned = Set.of(org.getId(), otherOrgId);
    when(currentUser.auditedOrganizationIds()).thenReturn(assigned);
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findByOrganizationIdInOrderByCreatedAtDesc(assigned, pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list(null, null, 0, 10);

    assertEquals(1, result.total());
    verify(evalRepo, never()).findAllByOrderByCreatedAtDesc(any());
  }

  @Test
  void listForAuditorWithStatusUsesUnionOfAssignedOrganizations() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    Set<UUID> assigned = Set.of(org.getId());
    when(currentUser.auditedOrganizationIds()).thenReturn(assigned);
    PageRequest pr = PageRequest.of(0, 10, Sort.by("createdAt").descending());
    when(evalRepo.findByOrganizationIdInAndStatusOrderByCreatedAtDesc(
            assigned, Evaluation.Status.READY_FOR_AUDIT, pr))
        .thenReturn(new PageImpl<>(List.of(eval), pr, 1));

    PaginatedDto<EvaluationDto> result = service.list("READY_FOR_AUDIT", null, 0, 10);

    assertEquals(1, result.total());
  }

  @Test
  void listForAuditorWithUnassignedOrgFilterIsDenied() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    doThrow(new org.springframework.security.access.AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(org.getId());

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.list(null, org.getId(), 0, 10));
  }

  @Test
  void listForAuditorWithNoOrganizationsAssignedReturnsEmpty() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.auditedOrganizationIds()).thenReturn(Set.of());

    PaginatedDto<EvaluationDto> result = service.list(null, null, 0, 10);

    assertEquals(0, result.total());
    assertTrue(result.data().isEmpty());
    verifyNoInteractions(evalRepo);
  }

  @Test
  void getByIdReturnsDto() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    EvaluationDto dto = service.getById(eval.getId());

    assertEvaluationDto(dto);
  }

  @Test
  void getByIdIncludesCreatedByNameAndAssignedAuditors() {
    User evaluator =
        User.builder().id(UUID.randomUUID()).firstName("Jane").lastName("Doe").build();
    eval.setCreatedBy(evaluator);
    User auditor1 =
        User.builder()
            .id(UUID.randomUUID())
            .firstName("Aud")
            .lastName("Uno")
            .roles(Set.of(UserRole.AUDITOR))
            .auditedOrganizations(Set.of(org))
            .build();
    User auditor2 =
        User.builder()
            .id(UUID.randomUUID())
            .firstName("Aud")
            .lastName("Dos")
            .roles(Set.of(UserRole.AUDITOR))
            .auditedOrganizations(Set.of(org))
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(userRepo.findAuditorsForOrganizations(Set.of(org.getId())))
        .thenReturn(List.of(auditor1, auditor2));

    EvaluationDto dto = service.getById(eval.getId());

    assertEquals("Jane Doe", dto.createdByName());
    assertEquals(Set.of("Aud Uno", "Aud Dos"), Set.copyOf(dto.assignedAuditorNames()));
  }

  @Test
  void getByIdHasNullCreatedByNameAndNoAuditorsWhenNoneAssigned() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    EvaluationDto dto = service.getById(eval.getId());

    assertNull(dto.createdByName());
    assertTrue(dto.assignedAuditorNames().isEmpty());
  }

  @Test
  void getByIdThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getById(id));
  }

  @Test
  void createBuildsDraftEvaluation() {
    when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateEvaluationDto dto = new CreateEvaluationDto("Nueva", org.getId(), "5.0", null);
    EvaluationDto result = service.create(dto);

    assertEquals("Nueva", result.name());
    assertEquals(org.getId(), result.organizationId());
    assertEquals(org.getName(), result.organizationName());
    assertEquals("5.0", result.catalogVersion());
    assertEquals("DRAFT", result.status());
    assertNull(result.createdBy());
  }

  @Test
  void createDefaultsCatalogVersion() {
    when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateEvaluationDto dto = new CreateEvaluationDto("Nueva", org.getId(), null, null);
    EvaluationDto result = service.create(dto);

    assertEquals("5.0", result.catalogVersion());
  }

  @Test
  void createThrowsWhenOrganizationMissing() {
    UUID orgId = UUID.randomUUID();
    when(orgRepo.findById(orgId)).thenReturn(Optional.empty());

    CreateEvaluationDto dto = new CreateEvaluationDto("Nueva", orgId, null, null);

    assertThrows(RuntimeException.class, () -> service.create(dto));
  }

  @Test
  void createWithCommunityProfileAttachesIt() {
    UUID profileId = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder().id(profileId).name("PYME").catalogVersion("5.0").build();
    when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
    when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateEvaluationDto dto = new CreateEvaluationDto("Nueva", org.getId(), "5.0", profileId);
    EvaluationDto result = service.create(dto);

    assertEquals(profileId, result.communityProfileId());
    assertEquals("PYME", result.communityProfileName());
  }

  @Test
  void createThrowsWhenProfileCatalogVersionMismatches() {
    UUID profileId = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder().id(profileId).name("Otra Version").catalogVersion("4.0").build();
    when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
    when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));

    CreateEvaluationDto dto = new CreateEvaluationDto("Nueva", org.getId(), "5.0", profileId);

    assertThrows(RuntimeException.class, () -> service.create(dto));
    verify(evalRepo, never()).save(any());
  }

  @Test
  void updateStatusChangesStatus() {
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    EvaluationDto result = service.updateStatus(eval.getId(), "IN_PROGRESS");

    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void updateStatusThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.updateStatus(id, "APPROVED"));
  }

  @Test
  void updateStatusRejectsSkippingStates() {
    // DRAFT no puede saltar directo a APPROVED: tiene que pasar por IN_PROGRESS,
    // READY_FOR_AUDIT e IN_AUDIT primero.
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.updateStatus(eval.getId(), "APPROVED"));
    verify(evalRepo, never()).save(any());
  }

  @Test
  void updateStatusRejectsReversingFromTerminalState() {
    eval.setStatus(Evaluation.Status.ARCHIVED);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.updateStatus(eval.getId(), "DRAFT"));
    verify(evalRepo, never()).save(any());
  }

  @Test
  void updateStatusDeniesRoleNotAllowedForThatTransition() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    // DRAFT -> IN_PROGRESS es una transición válida, pero este rol no es ORG_RESPONSIBLE.
    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.updateStatus(eval.getId(), "IN_PROGRESS"));
    verify(evalRepo, never()).save(any());
  }

  @Test
  void updateStatusAllowsOrgResponsibleToAdvanceSelfAssessment() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(true);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    EvaluationDto result = service.updateStatus(eval.getId(), "IN_PROGRESS");

    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void updateStatusAllowsInternalEvaluatorToAdvanceSelfAssessment() {
    // Ver el comentario en EvaluationService.assertRoleCanTransitionTo: ORG_RESPONSIBLE e
    // INTERNAL_EVALUATOR comparten el lado de autoevaluación.
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.hasRole(UserRole.INTERNAL_EVALUATOR)).thenReturn(true);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    EvaluationDto result = service.updateStatus(eval.getId(), "IN_PROGRESS");

    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void updateStatusAllowsAuditorToStartAudit() {
    eval.setStatus(Evaluation.Status.READY_FOR_AUDIT);
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    EvaluationDto result = service.updateStatus(eval.getId(), "IN_AUDIT");

    assertEquals("IN_AUDIT", result.status());
  }

  @Test
  void deleteRemovesEvaluation() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.of(eval));

    service.delete(id);

    verify(evalRepo, times(1)).deleteById(id);
  }

  @Test
  void getResponsesMapsDtos() {
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    EvaluationResponse response =
        EvaluationResponse.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(control)
            .compliant(true)
            .observations("obs")
            .respondedAt(OffsetDateTime.now())
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(response));

    List<EvaluationResponseDto> responses = service.getResponses(eval.getId());

    assertEquals(1, responses.size());
    EvaluationResponseDto dto = responses.get(0);
    assertEquals(eval.getId(), dto.evaluationId());
    assertEquals(control.getId(), dto.controlId());
    assertEquals(true, dto.compliant());
    assertEquals("obs", dto.observations());
    assertNull(dto.respondedBy());
  }

  @Test
  void saveResponseCreatesNewResponse() {
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(respRepo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.empty());
    when(respRepo.save(any(EvaluationResponse.class))).thenAnswer(inv -> inv.getArgument(0));

    SaveResponseDto dto = new SaveResponseDto(control.getId(), true, "nota");
    EvaluationResponseDto result = service.saveResponse(eval.getId(), dto);

    assertEquals(true, result.compliant());
    assertEquals("nota", result.observations());
    assertEquals(control.getId(), result.controlId());
    verify(respRepo, times(1)).save(any(EvaluationResponse.class));
  }

  @Test
  void saveResponseUpdatesExisting() {
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    EvaluationResponse existing =
        EvaluationResponse.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(control)
            .compliant(false)
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(respRepo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.of(existing));
    when(respRepo.save(any(EvaluationResponse.class))).thenAnswer(inv -> inv.getArgument(0));

    SaveResponseDto dto = new SaveResponseDto(control.getId(), true, null);
    EvaluationResponseDto result = service.saveResponse(eval.getId(), dto);

    assertEquals(true, result.compliant());
    assertNull(result.observations());
  }

  @Test
  void saveResponseThrowsWhenEvaluationMissing() {
    UUID evalId = UUID.randomUUID();
    when(evalRepo.findById(evalId)).thenReturn(Optional.empty());

    SaveResponseDto dto = new SaveResponseDto(UUID.randomUUID(), true, null);

    assertThrows(RuntimeException.class, () -> service.saveResponse(evalId, dto));
  }

  @Test
  void saveResponseThrowsWhenControlMissing() {
    UUID controlId = UUID.randomUUID();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    SaveResponseDto dto = new SaveResponseDto(controlId, true, null);

    assertThrows(RuntimeException.class, () -> service.saveResponse(eval.getId(), dto));
  }

  @Test
  void saveResponseThrowsWhenControlOutsideCommunityProfile() {
    CatalogControl inProfile = CatalogControl.builder().id(UUID.randomUUID()).code("IN").build();
    CatalogControl outsideProfile =
        CatalogControl.builder().id(UUID.randomUUID()).code("OUT").build();
    CommunityProfile profile =
        CommunityProfile.builder().id(UUID.randomUUID()).controls(Set.of(inProfile)).build();
    eval.setCommunityProfile(profile);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(outsideProfile.getId())).thenReturn(Optional.of(outsideProfile));

    SaveResponseDto dto = new SaveResponseDto(outsideProfile.getId(), true, null);

    assertThrows(RuntimeException.class, () -> service.saveResponse(eval.getId(), dto));
    verify(respRepo, never()).save(any());
  }

  @Test
  void saveResponseAllowsControlInsideCommunityProfile() {
    CatalogControl inProfile = CatalogControl.builder().id(UUID.randomUUID()).code("IN").build();
    CommunityProfile profile =
        CommunityProfile.builder().id(UUID.randomUUID()).controls(Set.of(inProfile)).build();
    eval.setCommunityProfile(profile);
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(inProfile.getId())).thenReturn(Optional.of(inProfile));
    when(respRepo.findByEvaluationIdAndControlId(eval.getId(), inProfile.getId()))
        .thenReturn(Optional.empty());
    when(respRepo.save(any(EvaluationResponse.class))).thenAnswer(inv -> inv.getArgument(0));

    SaveResponseDto dto = new SaveResponseDto(inProfile.getId(), true, null);
    EvaluationResponseDto result = service.saveResponse(eval.getId(), dto);

    assertEquals(true, result.compliant());
  }

  @Test
  void saveResponseRejectsEditingAnArchivedEvaluation() {
    eval.setStatus(Evaluation.Status.ARCHIVED);
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    SaveResponseDto dto = new SaveResponseDto(control.getId(), false, "post-archive");

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.saveResponse(eval.getId(), dto));
    verify(respRepo, never()).save(any());
  }

  @Test
  void saveResponseRejectsEditingAnApprovedEvaluation() {
    eval.setStatus(Evaluation.Status.APPROVED);
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    SaveResponseDto dto = new SaveResponseDto(control.getId(), true, null);

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.saveResponse(eval.getId(), dto));
    verify(respRepo, never()).save(any());
  }

  @Test
  void saveResponseAllowsEditingAReturnedEvaluation() {
    eval.setStatus(Evaluation.Status.RETURNED);
    CatalogControl control = CatalogControl.builder().id(UUID.randomUUID()).build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(respRepo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.empty());
    when(respRepo.save(any(EvaluationResponse.class))).thenAnswer(inv -> inv.getArgument(0));

    SaveResponseDto dto = new SaveResponseDto(control.getId(), true, "corregido");
    EvaluationResponseDto result = service.saveResponse(eval.getId(), dto);

    assertEquals(true, result.compliant());
  }

  @Test
  void getResultsMapsDtos() {
    MaturityResult result =
        MaturityResult.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .functionId(UUID.randomUUID())
            .functionName("F1")
            .categoryId(UUID.randomUUID())
            .categoryName("C1")
            .subcategoryId(UUID.randomUUID())
            .subcategoryName("S1")
            .currentLevel(2)
            .targetLevel(4)
            .gap(2)
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(result));

    List<MaturityResultDto> results = service.getResults(eval.getId());

    MaturityResultDto dto = results.get(0);
    assertEquals("F1", dto.functionName());
    assertEquals("C1", dto.categoryName());
    assertEquals("S1", dto.subcategoryName());
    assertEquals(2, dto.currentLevel());
    assertEquals(4, dto.targetLevel());
    assertEquals(2, dto.gap());
  }

  @Test
  void calculateMaturityComputesResultsAndGlobalMaturity() {
    // Modelo acumulativo: 4 controles nivel 1-4 en la misma subcategoría. Nivel 1 y 2 cumplidos,
    // nivel 3 no -> la subcategoría se detiene en el nivel 2 (nivel 4 ni se llega a evaluar).
    SubGraph graph = buildSubgraph();
    CatalogControl c1 = controlAtLevel(graph, 1);
    CatalogControl c2 = controlAtLevel(graph, 2);
    CatalogControl c3 = controlAtLevel(graph, 3);
    CatalogControl c4 = controlAtLevel(graph, 4);
    List<EvaluationResponse> responses =
        List.of(
            EvaluationResponse.builder().id(UUID.randomUUID()).control(c1).compliant(true).build(),
            EvaluationResponse.builder().id(UUID.randomUUID()).control(c2).compliant(true).build(),
            EvaluationResponse.builder()
                .id(UUID.randomUUID())
                .control(c3)
                .compliant(false)
                .build());
    MaturityResult oldResult =
        MaturityResult.builder().id(UUID.randomUUID()).evaluation(eval).build();

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1, c2, c3, c4));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(responses);
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(oldResult));
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    verify(matRepo, times(1)).deleteAllInBatch(any());
    verify(evalRepo, times(1))
        .save(argThat(e -> e.getGlobalMaturity() != null && e.getGlobalMaturity() == 2));

    List<MaturityResult> saved = captureSaved();
    assertEquals(1, saved.size());
    MaturityResult savedResult = saved.get(0);
    assertEquals(2, savedResult.getCurrentLevel());
    assertEquals(4, savedResult.getTargetLevel());
    assertEquals(2, savedResult.getGap());
    assertEquals(graph.function().getId(), savedResult.getFunctionId());
    assertEquals(graph.category().getId(), savedResult.getCategoryId());
    assertEquals(graph.subcategory().getId(), savedResult.getSubcategoryId());
    assertEquals(eval.getId(), savedResult.getEvaluation().getId());
  }

  @Test
  void calculateMaturityGlobalMaturityIsZeroNotNullWhenTouchedButFailing() {
    // Nivel 0 real (se respondió y no cumplió) es distinto de "nunca se calculó" -- antes ambos
    // casos guardaban null, indistinguibles en la UI ("—" para los dos).
    SubGraph graph = buildSubgraph();
    CatalogControl c1 = controlAtLevel(graph, 1);
    EvaluationResponse response =
        EvaluationResponse.builder().id(UUID.randomUUID()).control(c1).compliant(false).build();

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(response));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    verify(evalRepo, times(1))
        .save(argThat(e -> e.getGlobalMaturity() != null && e.getGlobalMaturity() == 0));
  }

  @Test
  void calculateMaturityGlobalMaturityStaysNullWhenNothingWasTouched() {
    SubGraph graph = buildSubgraph();
    CatalogControl c1 = controlAtLevel(graph, 1);

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    verify(evalRepo, times(1)).save(argThat(e -> e.getGlobalMaturity() == null));
  }

  @Test
  void calculateMaturityScopedToCommunityProfileIgnoresControlsOutsideIt() {
    SubGraph graph = buildSubgraph();
    CatalogControl inProfile = controlAtLevel(graph, 1);
    CatalogControl outsideProfile = controlAtLevel(graph, 4);
    CommunityProfile profile =
        CommunityProfile.builder().id(UUID.randomUUID()).controls(Set.of(inProfile)).build();
    eval.setCommunityProfile(profile);
    EvaluationResponse response =
        EvaluationResponse.builder()
            .id(UUID.randomUUID())
            .control(inProfile)
            .compliant(true)
            .build();

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(inProfile, outsideProfile));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(response));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    // Solo hay un resultado (agrupado por subcategoría, y ambos controles comparten la misma):
    // el target level usado tiene que ser el del control DENTRO del perfil (1), no el de fuera
    // (4) -- si se filtrara mal, el nivel meta incluiría exigencias de un control que no
    // corresponde a este perfil.
    MaturityResult saved = captureSaved().get(0);
    assertEquals(1, saved.getTargetLevel());
    assertEquals(1, saved.getCurrentLevel());
  }

  @Test
  void calculateMaturitySavesUnansweredControlsAtLevelZero() {
    SubGraph graph = buildSubgraph();
    CatalogControl high = controlAtLevel(graph, 4);

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(high));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    MaturityResult savedResult = captureSaved().get(0);
    assertEquals(0, savedResult.getCurrentLevel());
    assertEquals(4, savedResult.getTargetLevel());
    assertEquals(4, savedResult.getGap());
    assertEquals(graph.subcategory().getName(), savedResult.getSubcategoryName());
  }

  @Test
  void calculateMaturityPoolsSharedRequirementControlsIntoEverySubcategoryItBelongsTo() {
    // Un mismo Requisito (y sus Controles) puede pertenecer a 2 Subcategorias -- si su unico
    // control (nivel 1) no cumple, AMBAS subcategorias quedan en nivel 0 (no solo la primera).
    SubGraph graph = buildSubgraph();
    CatalogSubcategory secondSubcategory =
        CatalogSubcategory.builder()
            .id(UUID.randomUUID())
            .name("S2")
            .category(graph.category())
            .build();
    linkRequirementToSubcategory(graph.requirement(), secondSubcategory);
    CatalogControl shared = controlAtLevel(graph, 1);
    EvaluationResponse response =
        EvaluationResponse.builder().id(UUID.randomUUID()).control(shared).compliant(false).build();

    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(shared));
    when(respRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(response));
    when(matRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(matRepo.saveAll(any())).thenAnswer(inv -> new java.util.ArrayList<>(inv.getArgument(0)));
    when(evalRepo.save(any(Evaluation.class))).thenAnswer(inv -> inv.getArgument(0));

    service.calculateMaturity(eval.getId());

    List<MaturityResult> saved = captureSaved();
    assertEquals(2, saved.size(), "una fila por cada Subcategoria a la que aporta el Requisito");
    assertTrue(saved.stream().allMatch(r -> r.getCurrentLevel() == 0));
    assertEquals(
        Set.of(graph.subcategory().getId(), secondSubcategory.getId()),
        saved.stream().map(MaturityResult::getSubcategoryId).collect(java.util.stream.Collectors.toSet()));
  }

  private CatalogControl controlAtLevel(SubGraph graph, int level) {
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .targetLevel(level)
        .requirement(graph.requirement())
        .build();
  }

  @Test
  void calculateMaturityThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evalRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.calculateMaturity(id));
  }

  @SuppressWarnings("unchecked")
  private List<MaturityResult> captureSaved() {
    ArgumentCaptor<Iterable<MaturityResult>> captor = ArgumentCaptor.forClass(Iterable.class);
    verify(matRepo, atLeastOnce()).saveAll(captor.capture());
    java.util.List<MaturityResult> all = new java.util.ArrayList<>();
    for (Iterable<MaturityResult> batch : captor.getAllValues()) {
      batch.forEach(all::add);
    }
    return all;
  }

  private void assertEvaluationDto(EvaluationDto dto) {
    assertEquals(eval.getId(), dto.id());
    assertEquals("Evaluacion 2026", dto.name());
    assertEquals(org.getId(), dto.organizationId());
    assertEquals(org.getName(), dto.organizationName());
    assertEquals("5.0", dto.catalogVersion());
    assertEquals("DRAFT", dto.status());
    assertNull(dto.globalMaturity());
    assertNull(dto.createdBy());
    assertEquals(eval.getCreatedAt(), dto.createdAt());
  }
}
