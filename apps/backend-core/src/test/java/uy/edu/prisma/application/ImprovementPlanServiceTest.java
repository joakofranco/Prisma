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

  private ImprovementPlanService service;
  private Evaluation eval;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service =
        new ImprovementPlanService(
            repo, evaluationRepo, controlRepo, responseRepo, aiClient, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .catalogVersion("5.0")
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
        new CreateImprovementPlanDto(eval.getId(), null, "Mejorar proceso", "Juan", null, null);
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
        new CreateImprovementPlanDto(eval.getId(), null, "A", "R", "LOW", dueDate);
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
        new CreateImprovementPlanDto(eval.getId(), control.getId(), "A", "R", null, null);
    ImprovementPlanDto result = service.create(dto);

    assertEquals(control.getId(), result.controlId());
  }

  @Test
  void createThrowsWhenControlIdMissing() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), controlId, "A", "R", null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createThrowsWhenEvaluationMissing() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.empty());

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", null, null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.create(dto));
  }

  @Test
  void createRejectsInvalidDate() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    CreateImprovementPlanDto dto =
        new CreateImprovementPlanDto(eval.getId(), null, "A", null, null, "not-a-date");

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
        new UpdateImprovementPlanDto("Accion nueva", "R2", "HIGH", null, "IN_PROGRESS");
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

    UpdateImprovementPlanDto dto = new UpdateImprovementPlanDto(null, null, null, null, null);

    assertThrows(ResourceNotFoundException.class, () -> service.update(id, dto));
  }

  @Test
  void updateRejectsInvalidStatus() {
    ImprovementPlan plan = ImprovementPlan.builder().id(UUID.randomUUID()).evaluation(eval).build();
    when(repo.findById(plan.getId())).thenReturn(Optional.of(plan));

    UpdateImprovementPlanDto dto = new UpdateImprovementPlanDto(null, null, null, null, "BOGUS");

    assertThrows(InvalidRequestException.class, () -> service.update(plan.getId(), dto));
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
    RemediationTipsDto expected =
        new RemediationTipsDto("Resumen", List.of("Paso 1", "Paso 2"));
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
