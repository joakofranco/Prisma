package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import uy.edu.prisma.web.dto.Dto.PlanBaselineDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineItemDto;

class BaselineChartTest {

  private static PlanBaselineItemDto item(
      String code, String compliance, boolean replanned, Integer shift) {
    return new PlanBaselineItemDto(
        null,
        code,
        "Acción " + code,
        "PENDING",
        "2026-11-01",
        "2026-11-01",
        shift,
        replanned,
        "HIGH",
        "HIGH",
        false,
        "Ana",
        "Ana",
        false,
        null,
        compliance,
        null);
  }

  private static PlanBaselineDto baseline(List<PlanBaselineItemDto> items) {
    return new PlanBaselineDto(null, items);
  }

  @Test
  void slicesGroupByComplianceAndSkipEmptyOnes() {
    PlanBaselineDto dto =
        baseline(
            List.of(
                item("A", PlanBaseline.ON_TIME, false, 0),
                item("B", PlanBaseline.ON_TIME, false, 0),
                item("C", PlanBaseline.NO_DATE, false, null),
                item("D", PlanBaseline.NO_CLOSE_DATE, false, null)));

    List<BaselineChart.Slice> slices = BaselineChart.slices(dto);

    assertEquals(2, slices.size());
    assertEquals("En plazo", slices.get(0).label());
    assertEquals(2, slices.get(0).count());
    assertEquals("Sin fecha", slices.get(1).label());
    assertEquals(2, slices.get(1).count());
  }

  @Test
  void shiftsKeepOnlyMovedActionsOrderedByAbsoluteShift() {
    PlanBaselineDto dto =
        baseline(
            List.of(
                item("A", PlanBaseline.IN_TERM, true, 3),
                item("B", PlanBaseline.IN_TERM, true, -10),
                item("C", PlanBaseline.IN_TERM, false, 0),
                item("D", PlanBaseline.IN_TERM, true, null)));

    List<BaselineChart.Shift> shifts = BaselineChart.shifts(dto);

    assertEquals(List.of("B", "A"), shifts.stream().map(BaselineChart.Shift::label).toList());
    assertEquals(-10, shifts.get(0).days());
  }

  @Test
  void canRenderRequiresAtLeastOneAction() {
    assertFalse(BaselineChart.canRender(baseline(List.of())));
    assertTrue(
        BaselineChart.canRender(baseline(List.of(item("A", PlanBaseline.IN_TERM, false, 0)))));
  }

  @Test
  void manyShiftsAreCappedAtTheLimit() {
    PlanBaselineDto dto =
        baseline(
            IntStream.rangeClosed(1, 12)
                .mapToObj(i -> item("X" + i, PlanBaseline.IN_TERM, true, i))
                .toList());

    assertEquals(12, BaselineChart.shifts(dto).size());
    assertEquals(8, BaselineChart.SHIFT_LIMIT);
  }
}
