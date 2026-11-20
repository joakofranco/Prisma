package uy.edu.prisma.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogFunctionRepository;
import uy.edu.prisma.domain.repository.CatalogSubcategoryRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.web.dto.Dto.ComparedEvaluationDto;
import uy.edu.prisma.web.dto.Dto.ComparisonCandidateDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;
import uy.edu.prisma.web.dto.Dto.FunctionDeltaDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendPointDto;
import uy.edu.prisma.web.dto.Dto.SubcategoryDeltaDto;

/**
 * RF-VIZ-06 (PRI-320): comparativo entre dos evaluaciones finalizadas de la MISMA organización.
 *
 * <p>Usa los niveles por subcategoría que PRISMA ya calcula para cada evaluación ({@code
 * maturity_results}, los mismos del reporte) y la medición de la tendencia (controles cumplidos y
 * madurez promedio). Devuelve el cambio por función (nivel promedio de sus subcategorías) y por
 * subcategoría, más un resumen y una lectura en una frase.
 *
 * <p>Multitenant: las dos evaluaciones tienen que ser de la misma organización y el usuario tiene
 * que poder verla ({@link CurrentUserService#assertOrganizationAccess}). Solo lee.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationComparisonService {

  private final EvaluationRepository evalRepo;
  private final MaturityResultRepository matRepo;
  private final CatalogSubcategoryRepository subcategoryRepo;
  private final CatalogFunctionRepository functionRepo;
  private final MaturityTrendService trendService;
  private final CurrentUserService currentUser;

  public EvaluationComparisonService(
      EvaluationRepository evalRepo,
      MaturityResultRepository matRepo,
      CatalogSubcategoryRepository subcategoryRepo,
      CatalogFunctionRepository functionRepo,
      MaturityTrendService trendService,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.matRepo = matRepo;
    this.subcategoryRepo = subcategoryRepo;
    this.functionRepo = functionRepo;
    this.trendService = trendService;
    this.currentUser = currentUser;
  }

  /**
   * Evaluaciones finalizadas de la organización, la más reciente primero. Sin organización: la del
   * usuario (el responsable); el administrador y el auditor tienen que indicarla.
   */
  public List<ComparisonCandidateDto> candidates(UUID organizationId) {
    UUID orgId = organizationId != null ? organizationId : currentUser.currentTenantId();
    if (orgId == null) {
      throw new InvalidRequestException("Elegí la organización a comparar");
    }
    currentUser.assertOrganizationAccess(orgId);
    return evalRepo
        .findByOrganizationIdAndStatusIn(
            orgId, EnumSet.of(Evaluation.Status.APPROVED, Evaluation.Status.ARCHIVED))
        .stream()
        .filter(ReportService::isReportable)
        .sorted(
            Comparator.comparing(
                Evaluation::getApprovedAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .map(
            e ->
                new ComparisonCandidateDto(
                    e.getId(),
                    e.getName(),
                    profileName(e),
                    e.getApprovedAt(),
                    e.getGlobalMaturity()))
        .toList();
  }

  public EvaluationComparisonDto compare(UUID evaluationAId, UUID evaluationBId) {
    if (evaluationAId == null || evaluationBId == null) {
      throw new InvalidRequestException("Elegí las dos evaluaciones a comparar");
    }
    if (evaluationAId.equals(evaluationBId)) {
      throw new InvalidRequestException("Elegí dos evaluaciones distintas");
    }
    Evaluation a = load(evaluationAId);
    Evaluation b = load(evaluationBId);
    UUID orgId = a.getOrganization().getId();
    if (!orgId.equals(b.getOrganization().getId())) {
      throw new InvalidRequestException(
          "Solo se pueden comparar evaluaciones de la misma organización");
    }
    currentUser.assertOrganizationAccess(orgId);
    for (Evaluation e : List.of(a, b)) {
      if (!ReportService.isReportable(e)) {
        throw new ConflictException(
            "Solo se comparan evaluaciones finalizadas: «"
                + e.getName()
                + "» está "
                + ReportService.statusLabel(e.getStatus()));
      }
    }

    List<MaturityResult> resultsA = matRepo.findByEvaluationId(a.getId());
    List<MaturityResult> resultsB = matRepo.findByEvaluationId(b.getId());
    Map<UUID, MaturityResult> byA = bySubcategory(resultsA);
    Map<UUID, MaturityResult> byB = bySubcategory(resultsB);

    List<UUID> subcategoryIds = new ArrayList<>(byA.keySet());
    byB.keySet().stream().filter(id -> !byA.containsKey(id)).forEach(subcategoryIds::add);
    Map<UUID, CatalogSubcategory> subcategories =
        subcategoryRepo.findAllById(subcategoryIds).stream()
            .collect(Collectors.toMap(CatalogSubcategory::getId, s -> s));
    Map<UUID, Integer> functionOrder = functionOrder(resultsA, resultsB);

    List<SubcategoryDeltaDto> rows = new ArrayList<>();
    for (UUID id : subcategoryIds) {
      MaturityResult ra = byA.get(id);
      MaturityResult rb = byB.get(id);
      MaturityResult any = rb != null ? rb : ra;
      CatalogSubcategory s = subcategories.get(id);
      Integer levelA = ra != null ? ra.getCurrentLevel() : null;
      Integer levelB = rb != null ? rb.getCurrentLevel() : null;
      rows.add(
          new SubcategoryDeltaDto(
              id,
              s != null ? s.getCode() : null,
              any.getSubcategoryName(),
              any.getFunctionId(),
              any.getFunctionName(),
              any.getCategoryName(),
              levelA,
              levelB,
              levelA != null && levelB != null ? levelB - levelA : null,
              any.getTargetLevel()));
    }
    rows.sort(
        Comparator.comparing(
                (SubcategoryDeltaDto r) ->
                    functionOrder.getOrDefault(r.functionId(), Integer.MAX_VALUE))
            .thenComparing(r -> r.code() != null ? r.code() : r.name()));

    List<FunctionDeltaDto> functions = functions(rows, functionOrder);
    int up = (int) rows.stream().filter(r -> r.delta() != null && r.delta() > 0).count();
    int down = (int) rows.stream().filter(r -> r.delta() != null && r.delta() < 0).count();
    int same = (int) rows.stream().filter(r -> r.delta() != null && r.delta() == 0).count();
    int onlyA = (int) rows.stream().filter(r -> r.levelB() == null).count();
    int onlyB = (int) rows.stream().filter(r -> r.levelA() == null).count();

    ComparedEvaluationDto ca = compared(a);
    ComparedEvaluationDto cb = compared(b);
    return new EvaluationComparisonDto(
        orgId,
        a.getOrganization().getName(),
        ca,
        cb,
        Objects.equals(profileName(a), profileName(b)),
        up,
        down,
        same,
        onlyA,
        onlyB,
        reading(ca, cb, functions),
        functions,
        rows);
  }

  private Evaluation load(UUID id) {
    return evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
  }

  private ComparedEvaluationDto compared(Evaluation e) {
    MaturityTrendPointDto m = trendService.measure(e);
    return new ComparedEvaluationDto(
        e.getId(),
        e.getName(),
        profileName(e),
        e.getApprovedAt(),
        e.getGlobalMaturity(),
        m.maturityAverage(),
        m.compliantControls(),
        m.totalControls(),
        m.compliancePercent());
  }

  private static String profileName(Evaluation e) {
    return e.getCommunityProfile() != null
        ? e.getCommunityProfile().getName()
        : MaturityTrendService.FULL_CATALOG;
  }

  private static Map<UUID, MaturityResult> bySubcategory(List<MaturityResult> results) {
    Map<UUID, MaturityResult> map = new LinkedHashMap<>();
    for (MaturityResult r : results) {
      map.putIfAbsent(r.getSubcategoryId(), r);
    }
    return map;
  }

  private Map<UUID, Integer> functionOrder(List<MaturityResult> a, List<MaturityResult> b) {
    List<UUID> ids =
        java.util.stream.Stream.concat(a.stream(), b.stream())
            .map(MaturityResult::getFunctionId)
            .distinct()
            .toList();
    Map<UUID, Integer> order = new HashMap<>();
    for (CatalogFunction f : functionRepo.findAllById(ids)) {
      order.put(f.getId(), f.getSortOrder() != null ? f.getSortOrder() : Integer.MAX_VALUE);
    }
    return order;
  }

  /**
   * Nivel promedio de cada función en A y en B, sobre las subcategorías que cada evaluación tiene.
   */
  private static List<FunctionDeltaDto> functions(
      List<SubcategoryDeltaDto> rows, Map<UUID, Integer> functionOrder) {
    Map<UUID, List<SubcategoryDeltaDto>> byFunction = new LinkedHashMap<>();
    for (SubcategoryDeltaDto r : rows) {
      byFunction.computeIfAbsent(r.functionId(), k -> new ArrayList<>()).add(r);
    }
    List<FunctionDeltaDto> out = new ArrayList<>();
    for (Map.Entry<UUID, List<SubcategoryDeltaDto>> e : byFunction.entrySet()) {
      Double levelA = average(e.getValue().stream().map(SubcategoryDeltaDto::levelA).toList());
      Double levelB = average(e.getValue().stream().map(SubcategoryDeltaDto::levelB).toList());
      out.add(
          new FunctionDeltaDto(
              e.getKey(),
              e.getValue().get(0).functionName(),
              levelA,
              levelB,
              levelA != null && levelB != null ? round(levelB - levelA) : null));
    }
    out.sort(
        Comparator.comparing(f -> functionOrder.getOrDefault(f.functionId(), Integer.MAX_VALUE)));
    return out;
  }

  private static Double average(List<Integer> levels) {
    List<Integer> present = levels.stream().filter(Objects::nonNull).toList();
    return present.isEmpty()
        ? null
        : round(present.stream().mapToInt(Integer::intValue).average().orElse(0));
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }

  /**
   * Una frase para quien lee: cuánto cambió la madurez global, la función que más mejoró y la que
   * más bajó (si alguna bajó).
   */
  static String reading(
      ComparedEvaluationDto a, ComparedEvaluationDto b, List<FunctionDeltaDto> functions) {
    double delta = round(b.maturityAverage() - a.maturityAverage());
    StringBuilder text = new StringBuilder("Entre ambas evaluaciones la madurez global ");
    if (delta > 0) {
      text.append("subió ").append(number(delta));
    } else if (delta < 0) {
      text.append("bajó ").append(number(-delta));
    } else {
      text.append("se mantuvo");
    }
    // «de X a Y» y no una flecha: la fuente embebida de los PDF (PDF/A) no la tiene.
    text.append(" (de ").append(number(a.maturityAverage())).append(" a ");
    text.append(number(b.maturityAverage())).append(").");
    functions.stream()
        .filter(f -> f.delta() != null && f.delta() > 0)
        .max(Comparator.comparing(FunctionDeltaDto::delta))
        .ifPresent(
            f ->
                text.append(" La mayor mejora fue en ")
                    .append(f.functionName())
                    .append(" (+")
                    .append(number(f.delta()))
                    .append(")."));
    functions.stream()
        .filter(f -> f.delta() != null && f.delta() < 0)
        .min(Comparator.comparing(FunctionDeltaDto::delta))
        .ifPresent(
            f ->
                text.append(" ")
                    .append(f.functionName())
                    .append(" bajó ")
                    .append(number(-f.delta()))
                    .append(" y es la función a revisar."));
    return text.toString();
  }

  static String number(double v) {
    return String.format(Locale.forLanguageTag("es-UY"), "%.2f", v);
  }
}
