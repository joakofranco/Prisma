package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus;
import uy.edu.prisma.domain.entity.ImprovementPlan.Priority;
import uy.edu.prisma.web.dto.Dto.PlanBaselineDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineItemDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineSummaryDto;

class PlanBaselineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

  private static ImprovementPlan.ImprovementPlanBuilder action(
      LocalDate baselineDue, LocalDate due, PlanStatus status) {
    return ImprovementPlan.builder()
        .id(UUID.randomUUID())
        .action("Acción")
        .responsible("Ana")
        .baselineResponsible("Ana")
        .priority(Priority.MEDIUM)
        .baselinePriority(Priority.MEDIUM)
        .baselineDueDate(baselineDue)
        .dueDate(due)
        .status(status);
  }

  private static OffsetDateTime closedOn(LocalDate date) {
    return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime();
  }

  private static PlanBaselineItemDto only(ImprovementPlan plan) {
    return PlanBaseline.compare(List.of(plan), TODAY).items().get(0);
  }

  @Test
  void anEmptyPlanHasNoPercentages() {
    PlanBaselineDto result = PlanBaseline.compare(List.of(), TODAY);

    assertTrue(result.items().isEmpty());
    assertEquals(0, result.summary().total());
    assertNull(result.summary().plannedPercent());
    assertNull(result.summary().actualPercent());
    assertNull(result.summary().deviationPoints());
    assertNull(result.summary().averageShiftDays());
  }

  @Test
  void anUntouchedOpenActionIsInTermWithoutShift() {
    PlanBaselineItemDto item =
        only(action(TODAY.plusDays(10), TODAY.plusDays(10), PlanStatus.PENDING).build());

    assertFalse(item.replanned());
    assertEquals(0, item.dueDateShiftDays());
    assertFalse(item.priorityChanged());
    assertFalse(item.responsibleChanged());
    assertEquals(PlanBaseline.IN_TERM, item.compliance());
    assertNull(item.delayDays());
  }

  @Test
  void anActionDueTodayIsStillInTerm() {
    assertEquals(
        PlanBaseline.IN_TERM,
        only(action(TODAY, TODAY, PlanStatus.IN_PROGRESS).build()).compliance());
  }

  @Test
  void aPostponedActionShowsAPositiveShiftAndAnAdvancedOneANegativeShift() {
    PlanBaselineItemDto postponed =
        only(action(TODAY.plusDays(10), TODAY.plusDays(25), PlanStatus.PENDING).build());
    PlanBaselineItemDto advanced =
        only(action(TODAY.plusDays(10), TODAY.plusDays(3), PlanStatus.PENDING).build());

    assertTrue(postponed.replanned());
    assertEquals(15, postponed.dueDateShiftDays());
    assertTrue(advanced.replanned());
    assertEquals(-7, advanced.dueDateShiftDays());
  }

  @Test
  void movingTheDueDateDoesNotHideTheDelayAgainstTheOriginalDate() {
    PlanBaselineItemDto item =
        only(action(TODAY.minusDays(4), TODAY.plusDays(30), PlanStatus.PENDING).build());

    assertEquals(PlanBaseline.DELAYED, item.compliance());
    assertEquals(4, item.delayDays());
  }

  @Test
  void aCompletedActionIsOnTimeUpToTheOriginalDateAndLateAfterIt() {
    LocalDate due = TODAY.minusDays(10);
    PlanBaselineItemDto onTime =
        only(action(due, due, PlanStatus.COMPLETED).completedAt(closedOn(due)).build());
    PlanBaselineItemDto late =
        only(action(due, due, PlanStatus.COMPLETED).completedAt(closedOn(due.plusDays(6))).build());

    assertEquals(PlanBaseline.ON_TIME, onTime.compliance());
    assertNull(onTime.delayDays());
    assertEquals(due.toString(), onTime.completedAt());
    assertEquals(PlanBaseline.LATE, late.compliance());
    assertEquals(6, late.delayDays());
  }

  @Test
  void aCompletedActionWithoutCloseDateIsReportedAsSuch() {
    PlanBaselineItemDto item =
        only(action(TODAY.minusDays(3), TODAY.minusDays(3), PlanStatus.COMPLETED).build());

    assertEquals(PlanBaseline.NO_CLOSE_DATE, item.compliance());
  }

  @Test
  void anActionWithoutOriginalDateHasNoComplianceAndCountsAsReplannedOnceItGetsADate() {
    PlanBaselineItemDto undated = only(action(null, null, PlanStatus.PENDING).build());
    PlanBaselineItemDto dated = only(action(null, TODAY.plusDays(5), PlanStatus.PENDING).build());

    assertEquals(PlanBaseline.NO_DATE, undated.compliance());
    assertFalse(undated.replanned());
    assertTrue(dated.replanned());
    assertNull(dated.dueDateShiftDays());
  }

  @Test
  void priorityAndResponsibleChangesAreFlagged() {
    PlanBaselineItemDto item =
        only(
            action(TODAY.plusDays(5), TODAY.plusDays(5), PlanStatus.PENDING)
                .priority(Priority.HIGH)
                .responsible("Bruno")
                .control(CatalogControl.builder().code("PS.1-1").build())
                .build());

    assertTrue(item.priorityChanged());
    assertEquals("MEDIUM", item.baselinePriority());
    assertEquals("HIGH", item.priority());
    assertTrue(item.responsibleChanged());
    assertEquals("Ana", item.baselineResponsible());
    assertEquals("PS.1-1", item.controlCode());
  }

  @Test
  void aBlankResponsibleIsTheSameAsNoResponsible() {
    PlanBaselineItemDto item =
        only(
            action(TODAY.plusDays(5), TODAY.plusDays(5), PlanStatus.PENDING)
                .baselineResponsible(null)
                .responsible("  ")
                .build());

    assertFalse(item.responsibleChanged());
  }

  @Test
  void theSummaryComparesPlannedAgainstActualProgress() {
    LocalDate past = TODAY.minusDays(10);
    List<ImprovementPlan> plans =
        List.of(
            action(past, past, PlanStatus.COMPLETED).completedAt(closedOn(past)).build(),
            action(past, past, PlanStatus.COMPLETED)
                .completedAt(closedOn(past.plusDays(2)))
                .build(),
            action(past, TODAY.plusDays(20), PlanStatus.PENDING).build(),
            action(TODAY.plusDays(30), TODAY.plusDays(40), PlanStatus.PENDING)
                .priority(Priority.LOW)
                .build());

    PlanBaselineSummaryDto summary = PlanBaseline.compare(plans, TODAY).summary();

    assertEquals(4, summary.total());
    assertEquals(3, summary.plannedDue());
    assertEquals(2, summary.completed());
    assertEquals(75, summary.plannedPercent());
    assertEquals(50, summary.actualPercent());
    assertEquals(-25, summary.deviationPoints());
    assertEquals(1, summary.onTime());
    assertEquals(1, summary.late());
    assertEquals(1, summary.delayed());
    assertEquals(2, summary.replanned());
    assertEquals(20, summary.averageShiftDays());
    assertEquals(1, summary.priorityChanged());
    assertEquals(0, summary.responsibleChanged());
  }

  @Test
  void itemsAreOrderedByOriginalDueDateWithUndatedActionsLast() {
    ImprovementPlan undated = action(null, null, PlanStatus.PENDING).build();
    ImprovementPlan later =
        action(TODAY.plusDays(9), TODAY.plusDays(9), PlanStatus.PENDING).build();
    ImprovementPlan sooner =
        action(TODAY.plusDays(2), TODAY.plusDays(2), PlanStatus.PENDING).build();

    List<PlanBaselineItemDto> items =
        PlanBaseline.compare(List.of(undated, later, sooner), TODAY).items();

    assertEquals(
        List.of(sooner.getId(), later.getId(), undated.getId()),
        items.stream().map(PlanBaselineItemDto::id).toList());
  }
}
