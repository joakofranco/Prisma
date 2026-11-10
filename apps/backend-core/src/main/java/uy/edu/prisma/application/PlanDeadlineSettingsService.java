package uy.edu.prisma.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.ImprovementPlan.Priority;
import uy.edu.prisma.domain.entity.PlanDefaultDeadline;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.PlanDefaultDeadlineRepository;
import uy.edu.prisma.web.dto.Dto.PlanDeadlinesDto;

/**
 * RF-PLN-03 — Plazos por defecto del plan de mejora: cuántos meses se suman a la fecha de creación
 * para sugerir la fecha límite de una acción según su prioridad. Los configura el Administrador de
 * Plataforma; si falta una fila (base sin migrar del todo) se usan los valores del requerimiento.
 * Un cambio solo afecta a las sugerencias y acciones nuevas: las fechas ya cargadas no se tocan.
 */
@Service
@Transactional
public class PlanDeadlineSettingsService {

  public static final int MIN_MONTHS = 1;
  public static final int MAX_MONTHS = 60;

  private static final Map<Priority, Integer> REQUIREMENT_DEFAULTS =
      Map.of(Priority.HIGH, 3, Priority.MEDIUM, 6, Priority.LOW, 12);

  private final PlanDefaultDeadlineRepository repo;
  private final AuditLogService auditLog;

  public PlanDeadlineSettingsService(PlanDefaultDeadlineRepository repo, AuditLogService auditLog) {
    this.repo = repo;
    this.auditLog = auditLog;
  }

  @Transactional(readOnly = true)
  public PlanDeadlinesDto get() {
    Map<Priority, Integer> months = currentMonths();
    return new PlanDeadlinesDto(
        months.get(Priority.HIGH), months.get(Priority.MEDIUM), months.get(Priority.LOW));
  }

  /** Meses vigentes por prioridad (una sola consulta: lo usa el armado de sugerencias). */
  @Transactional(readOnly = true)
  public Map<Priority, Integer> currentMonths() {
    Map<Priority, Integer> months = new EnumMap<>(REQUIREMENT_DEFAULTS);
    repo.findAll().forEach(d -> months.put(d.getPriority(), d.getMonths()));
    return months;
  }

  /** Fecha límite sugerida para una acción de esa prioridad creada en {@code from}. */
  @Transactional(readOnly = true)
  public LocalDate defaultDueDate(Priority priority, LocalDate from) {
    return from.plusMonths(currentMonths().get(priority));
  }

  public PlanDeadlinesDto update(PlanDeadlinesDto dto) {
    int high = requireInRange(dto.highMonths(), "Alta");
    int medium = requireInRange(dto.mediumMonths(), "Media");
    int low = requireInRange(dto.lowMonths(), "Baja");
    if (high > medium) {
      throw new InvalidRequestException(
          "El plazo de prioridad Alta no puede superar al de prioridad Media");
    }
    if (medium > low) {
      throw new InvalidRequestException(
          "El plazo de prioridad Media no puede superar al de prioridad Baja");
    }

    Map<Priority, Integer> before = currentMonths();
    Map<Priority, Integer> after =
        Map.of(Priority.HIGH, high, Priority.MEDIUM, medium, Priority.LOW, low);
    StringJoiner changes = new StringJoiner(",", "{\"changes\":{", "}}");
    boolean changed = false;
    for (Priority priority : Priority.values()) {
      int from = before.get(priority);
      int to = after.get(priority);
      if (from == to) {
        continue;
      }
      changed = true;
      PlanDefaultDeadline row =
          repo.findById(priority)
              .orElseGet(() -> PlanDefaultDeadline.builder().priority(priority).build());
      row.setMonths(to);
      row.setUpdatedAt(OffsetDateTime.now());
      repo.save(row);
      changes.add("\"" + priority + "\":{\"from\":" + from + ",\"to\":" + to + "}");
    }
    if (changed) {
      auditLog.record("UPDATE", "plan-deadline-settings", changes.toString());
    }
    return get();
  }

  private static int requireInRange(Integer months, String label) {
    if (months == null) {
      throw new InvalidRequestException("El plazo de prioridad " + label + " es obligatorio");
    }
    if (months < MIN_MONTHS || months > MAX_MONTHS) {
      throw new InvalidRequestException(
          "El plazo de prioridad %s debe estar entre %d y %d meses"
              .formatted(label, MIN_MONTHS, MAX_MONTHS));
    }
    return months;
  }
}
