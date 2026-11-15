package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.EvaluationResponse;

class MaturityGapCalculatorTest {

  private static CatalogControl control(String code, int level) {
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .code(code)
        .description("desc " + code)
        .targetLevel(level)
        .build();
  }

  private static void answer(Map<UUID, EvaluationResponse> map, CatalogControl c, boolean ok) {
    map.put(
        c.getId(),
        EvaluationResponse.builder().id(UUID.randomUUID()).control(c).compliant(ok).build());
  }

  @Test
  void nextLevelIsTheFirstLevelAboveCurrentWithPendingControls() {
    CatalogControl c1 = control("C1", 1);
    CatalogControl c2 = control("C2", 2);
    CatalogControl c3 = control("C3", 3);
    Map<UUID, EvaluationResponse> responses = new HashMap<>();
    answer(responses, c1, true);
    answer(responses, c2, false);

    MaturityGapCalculator.Gap gap =
        MaturityGapCalculator.compute(1, List.of(c1, c2, c3), responses, null);

    assertEquals(2, gap.nextLevel());
    assertEquals(1, gap.missingControls().size());
    assertEquals("C2", gap.missingControls().get(0).code());
    assertEquals(MaturityGapCalculator.STATUS_NON_COMPLIANT, gap.missingControls().get(0).status());
  }

  @Test
  void unansweredControlIsReportedAsUnanswered() {
    CatalogControl c1 = control("C1", 1);

    MaturityGapCalculator.Gap gap =
        MaturityGapCalculator.compute(0, List.of(c1), new HashMap<>(), null);

    assertEquals(1, gap.nextLevel());
    assertEquals(MaturityGapCalculator.STATUS_UNANSWERED, gap.missingControls().get(0).status());
  }

  @Test
  void controlsOutsideTheProfileAreExcluded() {
    CatalogControl inProfile = control("C1", 2);
    CatalogControl outOfProfile = control("C2", 2);

    MaturityGapCalculator.Gap gap =
        MaturityGapCalculator.compute(
            1, List.of(inProfile, outOfProfile), new HashMap<>(), Set.of(inProfile.getId()));

    assertEquals(2, gap.nextLevel());
    assertEquals(1, gap.missingControls().size());
    assertEquals("C1", gap.missingControls().get(0).code());
  }

  @Test
  void skipsALevelWhoseOnlyPendingControlsAreOutsideTheProfile() {
    CatalogControl lvl2OutOfProfile = control("C2", 2);
    CatalogControl lvl3InProfile = control("C3", 3);

    MaturityGapCalculator.Gap gap =
        MaturityGapCalculator.compute(
            1,
            List.of(lvl2OutOfProfile, lvl3InProfile),
            new HashMap<>(),
            Set.of(lvl3InProfile.getId()));

    assertEquals(3, gap.nextLevel());
    assertEquals(List.of("C3"), gap.missingControls().stream().map(m -> m.code()).toList());
  }

  @Test
  void noGapAtMaxLevel() {
    CatalogControl c1 = control("C1", 1);
    Map<UUID, EvaluationResponse> responses = new HashMap<>();
    answer(responses, c1, true);

    MaturityGapCalculator.Gap gap = MaturityGapCalculator.compute(1, List.of(c1), responses, null);

    assertNull(gap.nextLevel());
    assertTrue(gap.missingControls().isEmpty());
  }

  @Test
  void noGapWhenOnlyOutOfProfileControlsRemain() {
    CatalogControl c2 = control("C2", 2);

    MaturityGapCalculator.Gap gap =
        MaturityGapCalculator.compute(1, List.of(c2), new HashMap<>(), Set.of());

    assertNull(gap.nextLevel());
    assertTrue(gap.missingControls().isEmpty());
  }
}
