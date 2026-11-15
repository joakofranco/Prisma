package uy.edu.prisma.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.web.dto.Dto.MissingControlDto;

final class MaturityGapCalculator {

  static final String STATUS_NON_COMPLIANT = "NON_COMPLIANT";
  static final String STATUS_UNANSWERED = "UNANSWERED";

  record Gap(Integer nextLevel, List<MissingControlDto> missingControls) {
    static final Gap NONE = new Gap(null, List.of());
  }

  private MaturityGapCalculator() {}

  static Gap compute(
      int currentLevel,
      List<CatalogControl> subcategoryControls,
      Map<UUID, EvaluationResponse> responsesByControl,
      Set<UUID> profileControlIds) {
    TreeSet<Integer> levelsAbove = new TreeSet<>();
    for (CatalogControl control : subcategoryControls) {
      if (control.getTargetLevel() > currentLevel) {
        levelsAbove.add(control.getTargetLevel());
      }
    }
    for (int level : levelsAbove) {
      List<MissingControlDto> missing =
          subcategoryControls.stream()
              .filter(c -> c.getTargetLevel() > currentLevel && c.getTargetLevel() <= level)
              .filter(c -> profileControlIds == null || profileControlIds.contains(c.getId()))
              .filter(c -> !isCompliant(responsesByControl.get(c.getId())))
              .sorted(
                  Comparator.comparingInt(CatalogControl::getTargetLevel)
                      .thenComparing(c -> c.getCode() == null ? "" : c.getCode()))
              .map(c -> toMissing(c, responsesByControl.get(c.getId())))
              .toList();
      if (!missing.isEmpty()) {
        return new Gap(level, missing);
      }
    }
    return Gap.NONE;
  }

  private static boolean isCompliant(EvaluationResponse response) {
    return response != null && Boolean.TRUE.equals(response.getCompliant());
  }

  private static MissingControlDto toMissing(CatalogControl control, EvaluationResponse response) {
    String status = response == null ? STATUS_UNANSWERED : STATUS_NON_COMPLIANT;
    return new MissingControlDto(
        control.getId(),
        control.getCode(),
        control.getDescription(),
        control.getTargetLevel(),
        status);
  }
}
