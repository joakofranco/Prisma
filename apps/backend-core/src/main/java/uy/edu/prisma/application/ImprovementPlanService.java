package uy.edu.prisma.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus;
import uy.edu.prisma.domain.entity.ImprovementPlan.Priority;
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

@Service
@Transactional
public class ImprovementPlanService {

  private final ImprovementPlanRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationResponseRepository responseRepo;
  private final AiEvidenceClient aiClient;
  private final CurrentUserService currentUser;

  public ImprovementPlanService(
      ImprovementPlanRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      EvaluationResponseRepository responseRepo,
      AiEvidenceClient aiClient,
      CurrentUserService currentUser) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.responseRepo = responseRepo;
    this.aiClient = aiClient;
    this.currentUser = currentUser;
  }

  // On-demand (un control a la vez, ver AiEvidenceClient.suggestRemediation): no necesita
  // evaluationId ni chequeo de organización -- es puramente contexto de catálogo, lo mismo que
  // cualquier usuario autenticado ya puede leer via GET /api/catalog.
  @Transactional(readOnly = true)
  public RemediationTipsDto tips(RemediationTipsRequestDto dto) {
    return aiClient.suggestRemediation(
        dto.controlCode(),
        dto.description(),
        dto.functionName(),
        dto.categoryName(),
        dto.subcategoryName(),
        dto.currentLevel(),
        dto.targetLevel());
  }

  // No es readOnly a proposito: markOverdue() es un UPDATE (recalcula que planes vencieron antes
  // de listarlos). Estuvo marcado readOnly=true hasta ahora, lo que hacia fallar ESTE metodo
  // completo con "cannot execute UPDATE in a read-only transaction" en toda llamada real.
  public List<ImprovementPlanDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    repo.markOverdue();
    return repo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId).stream()
        .map(this::toDto)
        .toList();
  }

  public ImprovementPlanDto create(CreateImprovementPlanDto dto) {
    Evaluation eval =
        evaluationRepo
            .findById(dto.evaluationId())
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", dto.evaluationId()));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    // dto.controlId() se ignoraba silenciosamente: la accion quedaba creada pero sin vincular al
    // control del catalogo, asi que suggest() nunca podia detectar que ese control ya tenia un
    // plan y lo volvia a sugerir en cada llamada (duplicados).
    CatalogControl control =
        dto.controlId() != null
            ? controlRepo
                .findById(dto.controlId())
                .orElseThrow(() -> new ResourceNotFoundException("Control", dto.controlId()))
            : null;
    ImprovementPlan plan =
        ImprovementPlan.builder()
            .evaluation(eval)
            .control(control)
            .action(dto.action())
            .responsible(dto.responsible())
            .priority(parsePriority(dto.priority()))
            .status(PlanStatus.PENDING)
            .dueDate(parseDueDate(dto.dueDate()))
            .build();
    return toDto(repo.save(plan));
  }

  public ImprovementPlanDto update(UUID id, UpdateImprovementPlanDto dto) {
    ImprovementPlan plan =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan de mejora", id));
    currentUser.assertOrganizationAccess(plan.getEvaluation().getOrganization().getId());
    if (dto.action() != null && !dto.action().isBlank()) {
      plan.setAction(dto.action());
    }
    if (dto.responsible() != null) {
      plan.setResponsible(dto.responsible());
    }
    if (dto.priority() != null && !dto.priority().isBlank()) {
      plan.setPriority(parsePriority(dto.priority()));
    }
    if (dto.status() != null && !dto.status().isBlank()) {
      plan.setStatus(parseStatus(dto.status()));
    }
    if (dto.dueDate() != null && !dto.dueDate().isBlank()) {
      plan.setDueDate(parseDueDate(dto.dueDate()));
    }
    return toDto(repo.save(plan));
  }

  /**
   * RF-PLN-01 — Generacion automatica de plan por brechas. Motor de reglas (no LLM, ver
   * docs/Proyecto.md): recorre los controles del catalogo de la evaluacion y, para cada uno cuya
   * respuesta este por debajo del nivel objetivo, arma una sugerencia con accion, prioridad (segun
   * el tamano de la brecha) y fecha limite sugerida. No persiste nada — el frontend muestra las
   * sugerencias para que el usuario las revise/edite y recien ahi las cree via POST
   * /api/improvement. Los controles que ya tienen un plan de mejora asociado se excluyen para no
   * repetir sugerencias en llamadas sucesivas.
   */
  @Transactional(readOnly = true)
  public List<SuggestedImprovementDto> suggest(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());

    Map<UUID, EvaluationResponse> responsesByControl =
        responseRepo.findByEvaluationId(evaluationId).stream()
            .collect(Collectors.toMap(r -> r.getControl().getId(), r -> r));
    Set<UUID> alreadyPlanned =
        repo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId).stream()
            .map(ImprovementPlan::getControl)
            .filter(Objects::nonNull)
            .map(CatalogControl::getId)
            .collect(Collectors.toSet());

    // Igual que en EvaluationService.calculateMaturity: si la evaluación tiene un perfil
    // comunitario, las sugerencias se acotan a sus controles -- si no, al catálogo completo.
    Set<UUID> profileControlIds =
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getControls().stream()
                .map(CatalogControl::getId)
                .collect(Collectors.toSet())
            : null;

    List<SuggestedImprovementDto> suggestions = new java.util.ArrayList<>();
    for (CatalogControl control : controlRepo.findByVersionOrdered(eval.getCatalogVersion())) {
      if (alreadyPlanned.contains(control.getId())) {
        continue;
      }
      if (profileControlIds != null && !profileControlIds.contains(control.getId())) {
        continue;
      }
      EvaluationResponse response = responsesByControl.get(control.getId());
      boolean compliant = response != null && Boolean.TRUE.equals(response.getCompliant());
      if (compliant) {
        continue;
      }

      // Nivel 1 es el mas urgente: en el modelo acumulativo, la subcategoria no puede avanzar a
      // NINGUN nivel mientras falten controles de nivel 1 -- son el bloqueante de todo lo demas.
      Priority priority = suggestPriority(control.getTargetLevel());

      // Un Requisito puede pertenecer a varias Subcategorias (ver CatalogRequirementSubcategory):
      // se arma una sugerencia por cada una, mismo control con el contexto de esa subcategoria.
      // alreadyPlanned sigue siendo por control (un plan real ya creado sobre el control descarta
      // TODAS sus sugerencias sin importar la subcategoria).
      for (var subcategory : control.getRequirement().getSubcategories()) {
        var category = subcategory.getCategory();
        var function = category.getFunction_();
        suggestions.add(
            new SuggestedImprovementDto(
                control.getId(),
                subcategory.getId(),
                function.getName(),
                category.getName(),
                subcategory.getName(),
                control.getCode(),
                suggestAction(control, subcategory.getName()),
                0,
                control.getTargetLevel(),
                priority.name(),
                suggestDueDate(priority).toString()));
      }
    }

    suggestions.sort(
        Comparator.<SuggestedImprovementDto>comparingInt(s -> priorityRank(s.priority()))
            .thenComparingInt(SuggestedImprovementDto::targetLevel));
    return suggestions;
  }

  private Priority suggestPriority(int controlLevel) {
    if (controlLevel <= 1) {
      return Priority.HIGH;
    }
    return controlLevel == 2 ? Priority.MEDIUM : Priority.LOW;
  }

  private LocalDate suggestDueDate(Priority priority) {
    int daysAhead =
        switch (priority) {
          case HIGH -> 30;
          case MEDIUM -> 60;
          case LOW -> 90;
        };
    return LocalDate.now().plusDays(daysAhead);
  }

  private int priorityRank(String priority) {
    return switch (Priority.valueOf(priority)) {
      case HIGH -> 0;
      case MEDIUM -> 1;
      case LOW -> 2;
    };
  }

  private String suggestAction(CatalogControl control, String subcategoryName) {
    return "Implementar (%s, nivel %d): %s"
        .formatted(subcategoryName, control.getTargetLevel(), control.getDescription());
  }

  private Priority parsePriority(String priority) {
    if (priority == null || priority.isBlank()) {
      return Priority.MEDIUM;
    }
    try {
      return Priority.valueOf(priority);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Prioridad inválida: " + priority);
    }
  }

  private PlanStatus parseStatus(String status) {
    try {
      return PlanStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Estado de plan inválido: " + status);
    }
  }

  private LocalDate parseDueDate(String dueDate) {
    if (dueDate == null || dueDate.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(dueDate);
    } catch (java.time.format.DateTimeParseException e) {
      throw new InvalidRequestException(
          "Fecha de vencimiento inválida (formato esperado AAAA-MM-DD): " + dueDate);
    }
  }

  private ImprovementPlanDto toDto(ImprovementPlan p) {
    Long dueInDays = null;
    if (p.getDueDate() != null) {
      dueInDays = ChronoUnit.DAYS.between(LocalDate.now(), p.getDueDate());
    }
    return new ImprovementPlanDto(
        p.getId(),
        p.getEvaluation().getId(),
        p.getControl() != null ? p.getControl().getId() : null,
        p.getAction(),
        p.getResponsible(),
        p.getPriority().name(),
        p.getStatus().name(),
        p.getDueDate() != null ? p.getDueDate().toString() : null,
        dueInDays,
        p.getCreatedAt());
  }
}
