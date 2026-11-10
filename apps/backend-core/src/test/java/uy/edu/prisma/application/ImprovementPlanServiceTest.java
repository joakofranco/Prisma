package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.CatalogCategory;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogRequirement;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.ImprovementPlanRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.CreateImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.ImprovementPlanDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsRequestDto;
import uy.edu.prisma.web.dto.Dto.SuggestedImprovementDto;
import uy.edu.prisma.web.dto.Dto.UpdateImprovementPlanDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImprovementPlanServiceTest {

  @Mock private ImprovementPlanRepository repo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvaluationResponseRepository responseRepo;
  @Mock private AiEvidenceClient aiClient;
  @Mock private CurrentUserService currentUser;
  @Mock private AuditLogService auditLog;

  private ImprovementPlanService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service =
        new ImprovementPlanService(
            repo, evaluationRepo, controlRepo, responseRepo, aiClient, currentUser, auditLog);
    // IN_PROGRESS: el plan está activo (en DRAFT / ARCHIVED no se puede crear ni editar).
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .catalogVersion("5.0")
            .status(Evaluation.Status.IN_PROGRESS)
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .build();
  }

  private CatalogControl buildControl(int targetLevel) {
    CatalogFunction function = CatalogFunction.builder().id(UUID.randomUUID()).name("F1").build();
    CatalogCategory category =
        CatalogCategory.builder().id(UUID.randomUUID()).name("C1").function_(function).build();
    CatalogSubcategory subcategory =
        CatalogSubcategory.builder().id(UUID.randomUUID()).name("S1").category(category).build();
    CatalogRequirement requirement = CatalogRequirement.builder().id(UUID.randomUUID()).build();
    uy.edu.prisma.domain.entity.CatalogRequirementSubcategory link =
        uy.edu.prisma.domain.entity.CatalogRequirementSubcategory.builder()
            .id(UUID.randomUUID())
            .requirement(requirement)
            .subcategory(subcategory)
            .build();
    requirement.setSubcategoryLinks(List.of(link));
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .requirement(requirement)
        .code("CT-1")
        .description("Cifrar datos en reposo")
        .targetLevel(targetLevel)
        .build();
  }

  @Test
  void listByEvaluationMarksOverdueAndReturnsDtos() {
    ImprovementPlan plan =
        ImprovementPlan.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .action("Capacitar")
            .priority(ImprovementPlan.Priority.HIGH)
            .status(ImprovementPlan.PlanStatus.PENDING)
            .dueDate(LocalDate.now().plusDays(30))
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(plan));

    List<ImprovementPlanDto> result = service.listByEvaluation(eval.getId());

    verify(repo, times(1)).markOverdue();
    assertEquals(1, result.size());
    assertEquals("Capacitar", result.get(0).action());
    assertEquals("HIGH", result.get(0).priority());
    assertEquals("PENDING", result.get(0).status());
    assertEquals(30L, result.get(0).dueInDays());
  }

  @Test
  void listReturnsNullWhenNoDueDate() {
    ImprovementPlan plan =
        ImprovementPlan.builder().id(UUID.randomUUID()).evaluation(eval).action("A").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of(plan));

    List<ImprovementPlanDto> result = service.listByEvaluation(eval.getId());

    assertNull(result.get(0).dueInDays());
  }

  @Test
  void createPersistsPlanWithDefaults() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(
            eval.getId(), null, "Mejorar proceso", "Juan", null, null, null);
    ImprovementPlanDto result = service.create(dto);

    assertEquals(eval.getId(), result.evaluationId());
    assertEquals("MEDIUM", result.priority());
    assertEquals("PENDING", result.status());
    assertNull(result.dueInDays());
  }

  @Test
  void createParsesPriorityAndDueDate() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    String dueDate = LocalDate.now().plusDays(7).toString();
    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", "R", "LOW", dueDate, null);
    ImprovementPlanDto result = service.create(dto);

    assertEquals("LOW", result.priority());
    assertEquals(dueDate, result.dueDate());
    assertEquals(7L, result.dueInDays());
  }

  @Test
  void createLinksControlWhenControlIdProvided() {
    CatalogControl control = buildControl(3);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), control.getId(), "A", "R", null, null, null);
    ImprovementPlanDto result = service.create(dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void createThrowsWhenControlIdMissing() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), controlId, "A", "R", null, null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenEvaluationMissing() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.empty());

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", null, null, null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createRejectsInvalidDate() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", null, null, "not-a-date", null);

    assertThrows(InvalidRequestException.class, () -> service.create(dto));
  }

  @Test
  void updateModifiesFields() {
    ImprovementPlan plan =
        ImprovementPlan.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .action("A")
            .status(ImprovementPlan.PlanStatus.PENDING)
            .build();
    when(repo.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateImprovementPlanDto dto =
        new UpdateImprovementPlanDto("Accion nueva", "R2", "HIGH", null, "IN_PROGRESS", null);
    ImprovementPlanDto result = service.update(plan.getId(), dto);

    assertEquals("Accion nueva", result.action());
    assertEquals("R2", result.responsible());
    assertEquals("HIGH", result.priority());
    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void updateThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    UpdateImprovementPlanDto dto = new UpdateImprovementPlanDto(null, null, null, null, null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.update(id, dto));
  }

  @Test
  void updateRejectsInvalidStatus() {
    ImprovementPlan plan = ImprovementPlan.builder().id(UUID.randomUUID()).evaluation(eval).build();
    when(repo.findById(plan.getId())).thenReturn(Optional.of(plan));

    UpdateImprovementPlanDto dto =
        new UpdateImprovementPlanDto(null, null, null, null, "BOGUS", null);

    assertThrows(InvalidRequestException.class, () -> service.update(plan.getId(), dto));
  }

  // ---- RF-PLN-02 (PRI-303): comentarios del ítem ----

  private ImprovementPlan planWithComments(String comments) {
    ImprovementPlan plan =
        ImprovementPlan.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .action("A")
            .comments(comments)
            .build();
    when(repo.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    return plan;
  }

  @Test
  void createStoresTheCommentsAndTheDtoReturnsThem() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    ImprovementPlanDto result =
        service.create(
            new CreateImprovementPlanDto(
                eval.getId(), null, "A", "R", null, null, "  Esperar el presupuesto 2027  "));

    assertEquals("Esperar el presupuesto 2027", result.comments());
  }

  @Test
  void createWithoutCommentsKeepsThemEmpty() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));

    ImprovementPlanDto withNull =
        service.create(
            new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, null, null));
    ImprovementPlanDto withBlank =
        service.create(
            new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, null, "   "));

    assertNull(withNull.comments());
    assertNull(withBlank.comments());
  }

  @Test
  void updateChangesTheComments() {
    ImprovementPlan plan = planWithComments("viejo");

    ImprovementPlanDto result =
        service.update(
            plan.getId(), new UpdateImprovementPlanDto(null, null, null, null, null, "nuevo"));

    assertEquals("nuevo", result.comments());
    assertEquals("nuevo", plan.getComments());
  }

  @Test
  void updateWithAnEmptyStringDeletesTheComments() {
    ImprovementPlan plan = planWithComments("borrar esto");

    ImprovementPlanDto result =
        service.update(
            plan.getId(), new UpdateImprovementPlanDto(null, null, null, null, null, ""));

    assertNull(result.comments());
    assertNull(plan.getComments());
  }

  @Test
  void updateWithNullCommentsLeavesThemUntouched() {
    ImprovementPlan plan = planWithComments("se queda");

    ImprovementPlanDto result =
        service.update(
            plan.getId(),
            new UpdateImprovementPlanDto(null, null, null, null, "IN_PROGRESS", null));

    assertEquals("se queda", result.comments());
    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void anItemCreatedBeforeTheMigrationHasNoCommentsAndCanBeEdited() {
    // Un ítem que ya existía antes de la columna llega con comments = null.
    ImprovementPlan legacy = planWithComments(null);

    ImprovementPlanDto before =
        service.update(
            legacy.getId(), new UpdateImprovementPlanDto(null, null, null, null, null, null));
    ImprovementPlanDto after =
        service.update(
            legacy.getId(), new UpdateImprovementPlanDto(null, null, null, null, null, "ahora sí"));

    assertNull(before.comments());
    assertEquals("ahora sí", after.comments());
  }

  // ---- RF-PLN-04 (PRI-305): gestión operativa del plan ----

  private ImprovementPlan existingPlan(ImprovementPlan.PlanStatus status, LocalDate dueDate) {
    ImprovementPlan plan =
        ImprovementPlan.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .action("Definir la política")
            .responsible("Ana")
            .priority(ImprovementPlan.Priority.HIGH)
            .status(status)
            .dueDate(dueDate)
            .build();
    when(repo.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    return plan;
  }

  private static UpdateImprovementPlanDto statusOnly(String status) {
    return new UpdateImprovementPlanDto(null, null, null, null, status, null);
  }

  private static UpdateImprovementPlanDto dueDateOnly(LocalDate dueDate) {
    return new UpdateImprovementPlanDto(null, null, null, dueDate.toString(), null, null);
  }

  @Test
  void createIsRejectedWhileTheEvaluationIsADraftOrArchived() {
    for (Evaluation.Status closed : List.of(Evaluation.Status.DRAFT, Evaluation.Status.ARCHIVED)) {
      eval.setStatus(closed);
      when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

      CreateImprovementPlanDto dto =
          new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, null, null);

      assertThrows(InvalidRequestException.class, () -> service.create(dto), closed.name());
    }
    verify(repo, never()).save(any());
  }

  @Test
  void createIsRejectedOnceTheEvaluationIsApproved() {
    eval.setStatus(Evaluation.Status.APPROVED);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, null, null);

    assertThrows(InvalidRequestException.class, () -> service.create(dto));
    verify(repo, never()).save(any());
  }

  @Test
  void createRejectsADueDateBeforeTodayButAcceptsToday() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.save(any(ImprovementPlan.class))).thenAnswer(inv -> inv.getArgument(0));
    String yesterday = LocalDate.now().minusDays(1).toString();
    String today = LocalDate.now().toString();
    CreateImprovementPlanDto past =
        new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, yesterday, null);
    CreateImprovementPlanDto current =
        new CreateImprovementPlanDto(eval.getId(), null, "A", "R", null, today, null);

    assertThrows(InvalidRequestException.class, () -> service.create(past));
    ImprovementPlanDto created = service.create(current);

    assertEquals(today, created.dueDate());
  }

  @Test
  void updateIsRejectedWhileTheEvaluationIsADraftOrArchived() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));
    for (Evaluation.Status closed : List.of(Evaluation.Status.DRAFT, Evaluation.Status.ARCHIVED)) {
      eval.setStatus(closed);

      assertThrows(
          InvalidRequestException.class,
          () -> service.update(plan.getId(), statusOnly("IN_PROGRESS")),
          closed.name());
    }
    verify(repo, never()).save(any());
  }

  @Test
  void withTheEvaluationApprovedOnlyTheStatusCanChange() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));
    eval.setStatus(Evaluation.Status.APPROVED);

    ImprovementPlanDto result = service.update(plan.getId(), statusOnly("COMPLETED"));

    assertEquals("COMPLETED", result.status());
  }

  @Test
  void withTheEvaluationApprovedResponsibleDateActionAndCommentsAreLocked() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));
    eval.setStatus(Evaluation.Status.APPROVED);
    String newDate = LocalDate.now().plusDays(20).toString();

    for (UpdateImprovementPlanDto dto :
        List.of(
            new UpdateImprovementPlanDto(null, "Otro", null, null, null, null),
            new UpdateImprovementPlanDto(null, null, null, newDate, null, null),
            new UpdateImprovementPlanDto("Otra acción", null, null, null, null, null),
            new UpdateImprovementPlanDto(null, null, "LOW", null, null, null),
            new UpdateImprovementPlanDto(null, null, null, null, null, "nota"))) {
      assertThrows(InvalidRequestException.class, () -> service.update(plan.getId(), dto));
    }
    assertEquals("Ana", plan.getResponsible());
    verify(repo, never()).save(any());
  }

  @Test
  void withTheEvaluationApprovedResendingTheSameValuesIsNotAChange() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));
    eval.setStatus(Evaluation.Status.APPROVED);

    ImprovementPlanDto result =
        service.update(
            plan.getId(),
            new UpdateImprovementPlanDto(
                "Definir la política",
                "Ana",
                "HIGH",
                plan.getDueDate().toString(),
                "IN_PROGRESS",
                null));

    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void withTheEvaluationApprovedAnOverdueActionCanOnlyBeCompleted() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.OVERDUE, LocalDate.now().minusDays(5));
    eval.setStatus(Evaluation.Status.APPROVED);

    assertThrows(
        InvalidRequestException.class, () -> service.update(plan.getId(), statusOnly("PENDING")));
    assertThrows(
        InvalidRequestException.class,
        () -> service.update(plan.getId(), statusOnly("IN_PROGRESS")));
    ImprovementPlanDto result = service.update(plan.getId(), statusOnly("COMPLETED"));

    assertEquals("COMPLETED", result.status());
  }

  @Test
  void updateRejectsANewDueDateBeforeTodayButAcceptsToday() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));

    assertThrows(
        InvalidRequestException.class,
        () -> service.update(plan.getId(), dueDateOnly(LocalDate.now().minusDays(1))));
    ImprovementPlanDto result = service.update(plan.getId(), dueDateOnly(LocalDate.now()));

    assertEquals(LocalDate.now().toString(), result.dueDate());
  }

  @Test
  void anOverdueActionCanBeEditedWithoutTouchingItsPastDueDate() {
    LocalDate past = LocalDate.now().minusDays(5);
    ImprovementPlan plan = existingPlan(ImprovementPlan.PlanStatus.OVERDUE, past);

    ImprovementPlanDto result =
        service.update(
            plan.getId(),
            new UpdateImprovementPlanDto(null, "Beto", null, past.toString(), null, null));

    assertEquals("Beto", result.responsible());
    assertEquals("OVERDUE", result.status());
  }

  @Test
  void overdueCannotBeSetByHand() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));

    assertThrows(
        InvalidRequestException.class, () -> service.update(plan.getId(), statusOnly("OVERDUE")));
    assertEquals(ImprovementPlan.PlanStatus.PENDING, plan.getStatus());
  }

  @Test
  void anOverdueActionWithANewDueDateGoesBackToPending() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.OVERDUE, LocalDate.now().minusDays(5));

    ImprovementPlanDto result =
        service.update(plan.getId(), dueDateOnly(LocalDate.now().plusDays(15)));

    assertEquals("PENDING", result.status());
  }

  @Test
  void anOverdueActionWithANewDueDateKeepsTheStatusTheUserPicked() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.OVERDUE, LocalDate.now().minusDays(5));
    String newDate = LocalDate.now().plusDays(15).toString();
    UpdateImprovementPlanDto dto =
        new UpdateImprovementPlanDto(null, null, null, newDate, "IN_PROGRESS", null);

    ImprovementPlanDto result = service.update(plan.getId(), dto);

    assertEquals("IN_PROGRESS", result.status());
  }

  @Test
  void anOverdueActionCannotBeReopenedWithoutAValidDueDate() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.OVERDUE, LocalDate.now().minusDays(5));

    assertThrows(
        InvalidRequestException.class,
        () -> service.update(plan.getId(), statusOnly("IN_PROGRESS")));
    assertEquals(ImprovementPlan.PlanStatus.OVERDUE, plan.getStatus());
  }

  @Test
  void updateRecordsTheChangesInTheAuditLog() {
    LocalDate oldDate = LocalDate.now().plusDays(10);
    LocalDate newDate = LocalDate.now().plusDays(20);
    ImprovementPlan plan = existingPlan(ImprovementPlan.PlanStatus.PENDING, oldDate);

    service.update(
        plan.getId(),
        new UpdateImprovementPlanDto(
            null, "Equipo \"TI\"", null, newDate.toString(), "IN_PROGRESS", null));

    verify(auditLog)
        .record(
            "UPDATE_IMPROVEMENT_PLAN",
            "improvement_plan:" + plan.getId(),
            "{\"changes\":{"
                + "\"responsible\":{\"from\":\"Ana\",\"to\":\"Equipo \\\"TI\\\"\"},"
                + "\"dueDate\":{\"from\":\""
                + oldDate
                + "\",\"to\":\""
                + newDate
                + "\"},"
                + "\"status\":{\"from\":\"PENDING\",\"to\":\"IN_PROGRESS\"}}}");
  }

  @Test
  void updateWithoutRealChangesIsNotRecorded() {
    ImprovementPlan plan =
        existingPlan(ImprovementPlan.PlanStatus.PENDING, LocalDate.now().plusDays(10));

    service.update(plan.getId(), statusOnly("PENDING"));

    verify(auditLog, never()).record(anyString(), anyString(), any());
  }

  // ---- suggest() — RF-PLN-01, generacion automatica de plan por brechas ----

  @Test
  void suggestOrdersByPriorityThenByLevel() {
    CatalogControl level1 = buildControl(1); // sin respuesta -> no cumple -> HIGH
    CatalogControl level2 = buildControl(2); // respuesta no cumple -> MEDIUM
    CatalogControl level3 = buildControl(3); // respuesta no cumple -> LOW
    EvaluationResponse respLevel2 =
        EvaluationResponse.builder().control(level2).compliant(false).build();
    EvaluationResponse respLevel3 =
        EvaluationResponse.builder().control(level3).compliant(false).build();

    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(level3, level1, level2));
    when(responseRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(respLevel2, respLevel3));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());

    List<SuggestedImprovementDto> result = service.suggest(eval.getId());

    assertEquals(3, result.size());
    assertEquals(level1.getId(), result.get(0).controlId());
    assertEquals("HIGH", result.get(0).priority());
    assertEquals(0, result.get(0).currentLevel());
    assertTrue(result.get(0).action().startsWith("Implementar"));
    assertEquals(level2.getId(), result.get(1).controlId());
    assertEquals("MEDIUM", result.get(1).priority());
    assertEquals(level3.getId(), result.get(2).controlId());
    assertEquals("LOW", result.get(2).priority());
  }

  @Test
  void suggestSkipsCompliantControls() {
    CatalogControl met = buildControl(3);
    EvaluationResponse resp = EvaluationResponse.builder().control(met).compliant(true).build();

    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(met));
    when(responseRepo.findByEvaluationId(eval.getId())).thenReturn(List.of(resp));
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId())).thenReturn(List.of());

    assertTrue(service.suggest(eval.getId()).isEmpty());
  }

  @Test
  void suggestSkipsControlsThatAlreadyHaveAPlan() {
    CatalogControl gap = buildControl(4);
    ImprovementPlan existingPlan = ImprovementPlan.builder().control(gap).evaluation(eval).build();

    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(gap));
    when(responseRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(repo.findByEvaluationIdOrderByCreatedAtDesc(eval.getId()))
        .thenReturn(List.of(existingPlan));

    assertTrue(service.suggest(eval.getId()).isEmpty());
  }

  @Test
  void suggestThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evaluationRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.suggest(id));
  }

  @Test
  void tipsDelegatesToAiClientWithDtoFields() {
    RemediationTipsDto expected = new RemediationTipsDto("Resumen", List.of("Paso 1", "Paso 2"));
    when(aiClient.suggestRemediation(
            "PR.AC-1", "descripción", "Proteger", "Control de Acceso", "Identidades", 0, 1))
        .thenReturn(expected);

    RemediationTipsDto result =
        service.tips(
            new RemediationTipsRequestDto(
                "PR.AC-1", "descripción", "Proteger", "Control de Acceso", "Identidades", 0, 1));

    assertEquals(expected, result);
  }
}
