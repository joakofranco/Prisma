package uy.edu.prisma.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus;
import uy.edu.prisma.web.dto.Dto.PlanBaselineDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineItemDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineSummaryDto;

public final class PlanBaseline {

  public static final String ON_TIME = "ON_TIME";
  public static final String LATE = "LATE";
  public static final String DELAYED = "DELAYED";
  public static final String IN_TERM = "IN_TERM";
  public static final String NO_CLOSE_DATE = "NO_CLOSE_DATE";
  public static final String NO_DATE = "NO_DATE";

  private PlanBaseline() {}

  public static PlanBaselineDto compare(List<ImprovementPlan> plans, LocalDate today) {
    List<ImprovementPlan> ordered =
        plans.stream()
            .sorted(
                Comparator.comparing(
                        ImprovementPlan::getBaselineDueDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                        ImprovementPlan::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    List<PlanBaselineItemDto> items = new ArrayList<>();
    int replanned = 0;
    long shiftSum = 0;
    int shiftCount = 0;
    int priorityChanged = 0;
    int responsibleChanged = 0;
    int onTime = 0;
    int late = 0;
    int delayed = 0;
    int plannedDue = 0;
    int completed = 0;

    for (ImprovementPlan plan : ordered) {
      PlanBaselineItemDto item = itemOf(plan, today);
      items.add(item);
      if (item.replanned()) {
        replanned++;
        if (item.dueDateShiftDays() != null) {
          shiftSum += item.dueDateShiftDays();
          shiftCount++;
        }
      }
      if (item.priorityChanged()) {
        priorityChanged++;
      }
      if (item.responsibleChanged()) {
        responsibleChanged++;
      }
      switch (item.compliance()) {
        case ON_TIME -> onTime++;
        case LATE -> late++;
        case DELAYED -> delayed++;
        default -> {}
      }
      if (plan.getBaselineDueDate() != null && plan.getBaselineDueDate().isBefore(today)) {
        plannedDue++;
      }
      if (plan.getStatus() == PlanStatus.COMPLETED) {
        completed++;
      }
    }

    int total = ordered.size();
    Integer plannedPercent = percent(plannedDue, total);
    Integer actualPercent = percent(completed, total);
    PlanBaselineSummaryDto summary =
        new PlanBaselineSummaryDto(
            total,
            replanned,
            shiftCount == 0 ? null : (int) Math.round((double) shiftSum / shiftCount),
            priorityChanged,
            responsibleChanged,
            onTime,
            late,
            delayed,
            plannedDue,
            completed,
            plannedPercent,
            actualPercent,
            total == 0 ? null : actualPercent - plannedPercent);
    return new PlanBaselineDto(summary, items);
  }

  private static PlanBaselineItemDto itemOf(ImprovementPlan plan, LocalDate today) {
    LocalDate baselineDue = plan.getBaselineDueDate();
    LocalDate due = plan.getDueDate();
    boolean replanned = !Objects.equals(baselineDue, due);
    Integer shift =
        baselineDue != null && due != null ? (int) ChronoUnit.DAYS.between(baselineDue, due) : null;

    boolean done = plan.getStatus() == PlanStatus.COMPLETED;
    LocalDate closedOn =
        plan.getCompletedAt() != null
            ? plan.getCompletedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
            : null;
    String compliance;
    Integer delayDays = null;
    if (baselineDue == null) {
      compliance = NO_DATE;
    } else if (done && closedOn == null) {
      compliance = NO_CLOSE_DATE;
    } else if (done) {
      if (closedOn.isAfter(baselineDue)) {
        compliance = LATE;
        delayDays = (int) ChronoUnit.DAYS.between(baselineDue, closedOn);
      } else {
        compliance = ON_TIME;
      }
    } else if (baselineDue.isBefore(today)) {
      compliance = DELAYED;
      delayDays = (int) ChronoUnit.DAYS.between(baselineDue, today);
    } else {
      compliance = IN_TERM;
    }

    return new PlanBaselineItemDto(
        plan.getId(),
        plan.getControl() != null ? plan.getControl().getCode() : null,
        plan.getAction(),
        plan.getStatus().name(),
        baselineDue != null ? baselineDue.toString() : null,
        due != null ? due.toString() : null,
        shift,
        replanned,
        plan.getBaselinePriority().name(),
        plan.getPriority().name(),
        plan.getBaselinePriority() != plan.getPriority(),
        plan.getBaselineResponsible(),
        plan.getResponsible(),
        !normalize(plan.getBaselineResponsible()).equals(normalize(plan.getResponsible())),
        closedOn != null ? closedOn.toString() : null,
        compliance,
        delayDays);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.strip();
  }

  private static Integer percent(int part, int total) {
    return total == 0 ? null : (int) Math.round(part * 100.0 / total);
  }
}
