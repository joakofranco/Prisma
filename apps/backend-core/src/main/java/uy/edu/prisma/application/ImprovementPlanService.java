package uy.edu.prisma.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

  private static final String EXPIRED_APPROVED =
      "La fecha límite ya venció: con la evaluación aprobada solo se puede pasar a Realizado";
  private static final String EXPIRED_OPEN =
      "La fecha límite ya venció: asigne una fecha vigente o pase la acción a Realizado";

  private final ImprovementPlanRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationResponseRepository responseRepo;
  private final AiEvidenceClient aiClient;
  private final CurrentUserService currentUser;
  private final AuditLogService auditLog;
  private final PlanDeadlineSettingsService deadlines;

  public ImprovementPlanService(
      ImprovementPlanRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      EvaluationResponseRepository responseRepo,
      AiEvidenceClient aiClient,
      CurrentUserService currentUser,
      AuditLogService auditLog,
      PlanDeadlineSettingsService deadlines) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.responseRepo = responseRepo;
    this.aiClient = aiClient;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
    this.deadlines = deadlines;
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
    assertPlanOpen(eval);
    if (eval.getStatus() == Evaluation.Status.APPROVED) {
      throw new InvalidRequestException(
          "La evaluación está aprobada: no se pueden agregar acciones al plan de mejora");
    }
    Priority priority = parsePriority(dto.priority());
    LocalDate dueDate = parseDueDate(dto.dueDate());
    // RF-PLN-03: sin fecha, se estampa el plazo por defecto de la prioridad.
    if (dueDate == null) {
      dueDate = deadlines.defaultDueDate(priority, LocalDate.now());
    }
    assertNotPast(dueDate);
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
            .priority(priority)
            .status(PlanStatus.PENDING)
            .dueDate(dueDate)
            .comments(normalizeComments(dto.comments()))
            .build();
    return toDto(repo.save(plan));
  }

  /**
   * RF-PLN-04 — Gestión operativa: editar plazo, responsable y estado de una acción. Reglas:
   *
   * <ul>
   *   <li>Con la evaluación en DRAFT o ARCHIVED el plan no está activo: no se edita.
   *   <li>Con la evaluación APPROVED solo se puede cambiar el estado.
   *   <li>Una fecha límite nueva no puede ser anterior a hoy (si no cambia, no se revalida: una
   *       acción vencida se puede seguir editando sin tocar su fecha).
   *   <li>OVERDUE lo asigna el sistema ({@link ImprovementPlanRepository#markOverdue()}), nunca el
   *       usuario. Si una acción vencida recibe una fecha nueva y no se pide otro estado, vuelve a
   *       PENDING.
   *   <li>No se puede pasar a PENDING / IN_PROGRESS una acción cuya fecha ya venció: el próximo
   *       listado la volvería a marcar vencida. Hay que darle una fecha vigente o pasarla a
   *       COMPLETED (con la evaluación aprobada, como la fecha no se edita, solo queda COMPLETED).
   * </ul>
   *
   * Cada cambio real queda en la bitácora como UPDATE_IMPROVEMENT_PLAN.
   */
  public ImprovementPlanDto update(UUID id, UpdateImprovementPlanDto dto) {
    ImprovementPlan plan =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan de mejora", id));
    Evaluation eval = plan.getEvaluation();
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    assertPlanOpen(eval);

    String action = isBlank(dto.action()) ? plan.getAction() : dto.action();
    String responsible = dto.responsible() != null ? dto.responsible() : plan.getResponsible();
    Priority priority =
        isBlank(dto.priority()) ? plan.getPriority() : parsePriority(dto.priority());
    LocalDate dueDate = isBlank(dto.dueDate()) ? plan.getDueDate() : parseDueDate(dto.dueDate());
    // Comentarios: null = sin cambios; una cadena vacía (o en blanco) borra el comentario.
    String comments =
        dto.comments() != null ? normalizeComments(dto.comments()) : plan.getComments();

    boolean dueDateChanged = !Objects.equals(dueDate, plan.getDueDate());
    if (dueDateChanged) {
      assertNotPast(dueDate);
    }

    PlanStatus requested = isBlank(dto.status()) ? null : parseStatus(dto.status());
    if (requested == PlanStatus.OVERDUE) {
      if (plan.getStatus() != PlanStatus.OVERDUE) {
        throw new InvalidRequestException(
            "El estado Vencida lo asigna el sistema según la fecha límite");
      }
      requested = null; // reenviar el estado actual no es un cambio
    }
    PlanStatus status;
    if (requested != null) {
      status = requested;
    } else if (plan.getStatus() == PlanStatus.OVERDUE && dueDateChanged) {
      status = PlanStatus.PENDING;
    } else {
      status = plan.getStatus();
    }

    Map<String, String[]> changes = new LinkedHashMap<>();
    addChange(changes, "action", plan.getAction(), action);
    addChange(changes, "responsible", plan.getResponsible(), responsible);
    addChange(changes, "priority", plan.getPriority(), priority);
    addChange(changes, "dueDate", plan.getDueDate(), dueDate);
    addChange(changes, "status", plan.getStatus(), status);
    addChange(changes, "comments", plan.getComments(), comments);

    boolean approved = eval.getStatus() == Evaluation.Status.APPROVED;
    if (approved && changes.keySet().stream().anyMatch(field -> !field.equals("status"))) {
      throw new InvalidRequestException(
          "La evaluación está aprobada: solo se puede cambiar el estado de la acción");
    }

    boolean reopening =
        status != plan.getStatus()
            && (status == PlanStatus.PENDING || status == PlanStatus.IN_PROGRESS);
    if (reopening && dueDate != null && dueDate.isBefore(LocalDate.now())) {
      throw new InvalidRequestException(approved ? EXPIRED_APPROVED : EXPIRED_OPEN);
    }

    plan.setAction(action);
    plan.setResponsible(responsible);
    plan.setPriority(priority);
    plan.setDueDate(dueDate);
    plan.setStatus(status);
    plan.setComments(comments);
    ImprovementPlan saved = repo.save(plan);

    if (!changes.isEmpty()) {
      auditLog.record("UPDATE_IMPROVEMENT_PLAN", "improvement_plan:" + id, changesJson(changes));
    }
    return toDto(saved);
  }

  /** El plan solo está activo mientras la evaluación no sea un borrador ni esté archivada. */
  private static void assertPlanOpen(Evaluation eval) {
    if (eval.getStatus() == Evaluation.Status.DRAFT
        || eval.getStatus() == Evaluation.Status.ARCHIVED) {
      throw new InvalidRequestException(
          "El plan de mejora no está activo: la evaluación está en borrador o archivada");
    }
  }

  private static void assertNotPast(LocalDate dueDate) {
    if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
      throw new InvalidRequestException("La fecha límite no puede ser anterior a hoy");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void addChange(
      Map<String, String[]> changes, String field, Object from, Object to) {
    if (!Objects.equals(from, to)) {
      changes.put(field, new String[] {Objects.toString(from, null), Objects.toString(to, null)});
    }
  }

  /**
   * {"changes":{"campo":{"from":…,"to":…}}}. La columna payload es jsonb, así que los textos libres
   * (acción, responsable, comentarios) se escapan.
   */
  private static String changesJson(Map<String, String[]> changes) {
    StringBuilder sb = new StringBuilder("{\"changes\":{");
    boolean first = true;
    for (Map.Entry<String, String[]> e : changes.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append(jsonString(e.getKey()))
          .append(":{\"from\":")
          .append(jsonString(e.getValue()[0]))
          .append(",\"to\":")
          .append(jsonString(e.getValue()[1]))
          .append('}');
    }
    return sb.append("}}").toString();
  }

  private static String jsonString(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
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

    // RF-PLN-03: plazos por defecto vigentes (una sola lectura para todas las sugerencias).
    Map<Priority, Integer> monthsByPriority = deadlines.currentMonths();
    LocalDate today = LocalDate.now();

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
                today.plusMonths(monthsByPriority.get(priority)).toString()));
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

  /** Sin espacios de más y {@code null} si quedó vacío: un comentario en blanco no se guarda. */
  private static String normalizeComments(String comments) {
    if (comments == null || comments.isBlank()) {
      return null;
    }
    return comments.strip();
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
        p.getComments(),
        p.getCreatedAt());
  }
}
