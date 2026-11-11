package uy.edu.prisma.application;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogCategory;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogRequirement;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.CatalogVersion;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogFunctionRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.CommunityProfileRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.EvaluationOverviewDto;
import uy.edu.prisma.web.dto.Dto.OverviewCategoryDto;
import uy.edu.prisma.web.dto.Dto.OverviewControlDto;
import uy.edu.prisma.web.dto.Dto.OverviewFunctionDto;
import uy.edu.prisma.web.dto.Dto.OverviewPriorityCountsDto;
import uy.edu.prisma.web.dto.Dto.OverviewRequirementDto;
import uy.edu.prisma.web.dto.Dto.OverviewSubcategoryDto;

@Service
@Transactional(readOnly = true)
public class EvaluationOverviewService {

  static final String PRIORITY_HIGH = "ALTA";
  static final String PRIORITY_MEDIUM = "MEDIA";
  static final String PRIORITY_LOW = "BAJA";

  private final EvaluationRepository evalRepo;
  private final CatalogVersionRepository versionRepo;
  private final CatalogFunctionRepository functionRepo;
  private final CatalogControlRepository controlRepo;
  private final CommunityProfileRepository profileRepo;
  private final CurrentUserService currentUser;

  public EvaluationOverviewService(
      EvaluationRepository evalRepo,
      CatalogVersionRepository versionRepo,
      CatalogFunctionRepository functionRepo,
      CatalogControlRepository controlRepo,
      CommunityProfileRepository profileRepo,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.versionRepo = versionRepo;
    this.functionRepo = functionRepo;
    this.controlRepo = controlRepo;
    this.profileRepo = profileRepo;
    this.currentUser = currentUser;
  }

  public EvaluationOverviewDto getOverview(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());

    CatalogVersion catalog =
        versionRepo
            .findByVersion(eval.getCatalogVersion())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("Versión de catálogo", eval.getCatalogVersion()));

    List<CatalogControl> allControls = controlRepo.findByVersionOrdered(catalog.getVersion());
    Map<UUID, List<CatalogControl>> allBySubcategory = groupBySubcategory(allControls);

    CommunityProfile profile = eval.getCommunityProfile();
    List<CatalogControl> visibleControls = allControls;
    if (profile != null) {
      Set<UUID> allowed =
          profile.getControls().stream().map(CatalogControl::getId).collect(Collectors.toSet());
      visibleControls = allControls.stream().filter(c -> allowed.contains(c.getId())).toList();
    }
    Map<UUID, List<CatalogControl>> visibleBySubcategory = groupBySubcategory(visibleControls);
    Map<UUID, String> priorities = derivePriorities(catalog.getVersion(), allBySubcategory);

    List<OverviewFunctionDto> functions = new ArrayList<>();
    Set<UUID> requirementIds = new HashSet<>();
    Set<UUID> controlIds = new HashSet<>();
    int subcategoryCount = 0;
    int alta = 0;
    int media = 0;
    int baja = 0;
    int sinPrioridad = 0;

    for (CatalogFunction function : functionRepo.findByVersionIdOrderBySortOrder(catalog.getId())) {
      List<OverviewCategoryDto> categories = new ArrayList<>();
      for (CatalogCategory category : function.getCategories()) {
        List<OverviewSubcategoryDto> subcategories = new ArrayList<>();
        for (CatalogSubcategory subcategory : category.getSubcategories()) {
          List<CatalogControl> controls = visibleBySubcategory.get(subcategory.getId());
          if (controls == null || controls.isEmpty()) {
            continue;
          }
          String priority = priorities.get(subcategory.getId());
          subcategories.add(toSubcategoryDto(subcategory, controls, priority));
          subcategoryCount++;
          for (CatalogControl control : controls) {
            controlIds.add(control.getId());
            requirementIds.add(control.getRequirement().getId());
          }
          if (PRIORITY_HIGH.equals(priority)) {
            alta++;
          } else if (PRIORITY_MEDIUM.equals(priority)) {
            media++;
          } else if (PRIORITY_LOW.equals(priority)) {
            baja++;
          } else {
            sinPrioridad++;
          }
        }
        if (!subcategories.isEmpty()) {
          categories.add(
              new OverviewCategoryDto(
                  category.getId(), category.getCode(), category.getName(), subcategories));
        }
      }
      if (!categories.isEmpty()) {
        functions.add(
            new OverviewFunctionDto(
                function.getId(), function.getCode(), function.getName(), categories));
      }
    }

    return new EvaluationOverviewDto(
        eval.getId(),
        eval.getName(),
        catalog.getVersion(),
        catalog.getLabel(),
        profile == null ? null : profile.getId(),
        profile == null ? null : profile.getName(),
        subcategoryCount,
        requirementIds.size(),
        controlIds.size(),
        new OverviewPriorityCountsDto(alta, media, baja, sinPrioridad),
        functions);
  }

  private OverviewSubcategoryDto toSubcategoryDto(
      CatalogSubcategory subcategory, List<CatalogControl> controls, String priority) {
    Map<UUID, OverviewRequirementDto> requirements = new LinkedHashMap<>();
    for (CatalogControl control : controls) {
      CatalogRequirement requirement = control.getRequirement();
      requirements.computeIfAbsent(
          requirement.getId(),
          id ->
              new OverviewRequirementDto(
                  requirement.getId(), requirement.getCode(), requirement.getDescription()));
    }
    List<OverviewControlDto> controlDtos =
        controls.stream()
            .sorted(Comparator.comparingInt(CatalogControl::getTargetLevel))
            .map(
                c ->
                    new OverviewControlDto(
                        c.getId(),
                        c.getCode(),
                        c.getDescription(),
                        c.getTargetLevel(),
                        c.getRequirement().getCode()))
            .toList();
    return new OverviewSubcategoryDto(
        subcategory.getId(),
        subcategory.getCode(),
        subcategory.getName(),
        subcategory.getDescription(),
        priority,
        List.copyOf(requirements.values()),
        controlDtos);
  }

  private Map<UUID, List<CatalogControl>> groupBySubcategory(List<CatalogControl> controls) {
    Map<UUID, List<CatalogControl>> bySubcategory = new LinkedHashMap<>();
    for (CatalogControl control : controls) {
      for (CatalogSubcategory subcategory : control.effectiveSubcategories()) {
        bySubcategory.computeIfAbsent(subcategory.getId(), k -> new ArrayList<>()).add(control);
      }
    }
    return bySubcategory;
  }

  Map<UUID, String> derivePriorities(
      String catalogVersion, Map<UUID, List<CatalogControl>> allBySubcategory) {
    Set<UUID> basic = null;
    Set<UUID> standard = null;
    Set<UUID> advanced = null;
    for (CommunityProfile profile :
        profileRepo.findByCatalogVersionOrderByNameAsc(catalogVersion)) {
      Set<UUID> controlIds =
          profile.getControls().stream().map(CatalogControl::getId).collect(Collectors.toSet());
      switch (normalizeName(profile.getName())) {
        case "basico" -> basic = controlIds;
        case "estandar" -> standard = controlIds;
        case "avanzado" -> advanced = controlIds;
        default -> {}
      }
    }
    Map<UUID, String> priorities = new LinkedHashMap<>();
    for (Map.Entry<UUID, List<CatalogControl>> entry : allBySubcategory.entrySet()) {
      List<CatalogControl> controls = entry.getValue();
      priorities.put(
          entry.getKey(),
          priorityFor(
              containsAny(basic, controls),
              containsAny(standard, controls),
              containsAny(advanced, controls)));
    }
    return priorities;
  }

  private static boolean containsAny(Set<UUID> profileControlIds, List<CatalogControl> controls) {
    return profileControlIds != null
        && controls.stream().anyMatch(c -> profileControlIds.contains(c.getId()));
  }

  private static String normalizeName(String name) {
    if (name == null) {
      return "";
    }
    return Normalizer.normalize(name, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .strip();
  }

  static String priorityFor(boolean inBasic, boolean inStandard, boolean inAdvanced) {
    if (inBasic) {
      return PRIORITY_HIGH;
    }
    if (inStandard) {
      return PRIORITY_MEDIUM;
    }
    if (inAdvanced) {
      return PRIORITY_LOW;
    }
    return null;
  }
}
