package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
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
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogSubcategoryRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.web.dto.Dto.MaturityTrendDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendPointDto;

/**
 * RF-VIZ-07 (PRI-321): tendencia histórica de la madurez de UNA organización, para el Panel.
 *
 * <p>Un punto por evaluación: las finalizadas (Aprobada, o Archivada después de aprobarse: el mismo
 * criterio que los reportes, {@link ReportService#isReportable}) en su fecha de aprobación, y las
 * que siguen en curso o en auditoría como punto provisorio con su valor de hoy. Cada punto trae su
 * perfil, para que la pantalla dibuje una línea por perfil sin mezclar alcances distintos, y dos
 * medidas: los controles cumplidos sobre los del alcance de la evaluación y la madurez promedio con
 * decimales (el nivel global, antes de redondear).
 *
 * <p>Multitenant: un ORG_RESPONSIBLE ve siempre SU organización (no la puede elegir); un
 * PRISMA_ADMIN elige una. Nunca se mezclan organizaciones. Solo lee.
 */
@Service
@Transactional(readOnly = true)
public class MaturityTrendService {

  /** Nombre del «perfil» de una evaluación sin perfil comunitario: evalúa todo el catálogo. */
  static final String FULL_CATALOG = "Catálogo completo";

  private final EvaluationRepository evalRepo;
  private final OrganizationRepository orgRepo;
  private final CatalogControlRepository controlRepo;
  private final CatalogSubcategoryRepository subcategoryRepo;
  private final EvaluationResponseRepository responseRepo;
  private final MaturityResultRepository matRepo;
  private final CurrentUserService currentUser;

  public MaturityTrendService(
      EvaluationRepository evalRepo,
      OrganizationRepository orgRepo,
      CatalogControlRepository controlRepo,
      CatalogSubcategoryRepository subcategoryRepo,
      EvaluationResponseRepository responseRepo,
      MaturityResultRepository matRepo,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.orgRepo = orgRepo;
    this.controlRepo = controlRepo;
    this.subcategoryRepo = subcategoryRepo;
    this.responseRepo = responseRepo;
    this.matRepo = matRepo;
    this.currentUser = currentUser;
  }

  public MaturityTrendDto trend(UUID requestedOrganizationId) {
    UUID organizationId = resolveOrganization(requestedOrganizationId);
    Organization organization =
        orgRepo
            .findById(organizationId)
            .orElseThrow(() -> new ResourceNotFoundException("Organización", organizationId));

    // Todas menos las descartadas (archivadas sin aprobar). Las que no están aprobadas entran como
    // provisorias con su valor de hoy, si ya tienen alguna respuesta: una evaluación con su
    // auditoría puede llevar meses y la tendencia no debe quedar vacía todo ese tiempo.
    OffsetDateTime now = OffsetDateTime.now();
    List<Evaluation> evaluations =
        evalRepo
            .findByOrganizationIdAndStatusIn(organizationId, EnumSet.allOf(Evaluation.Status.class))
            .stream()
            .filter(
                e -> ReportService.isReportable(e) || e.getStatus() != Evaluation.Status.ARCHIVED)
            .toList();

    // Una sola lectura del catálogo por versión aunque haya varias evaluaciones de la misma.
    Map<String, Set<UUID>> controlsByVersion = new HashMap<>();
    Map<String, Long> subcategoriesByVersion = new HashMap<>();
    List<MaturityTrendPointDto> points =
        evaluations.stream()
            .map(e -> point(e, now, controlsByVersion, subcategoriesByVersion))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(MaturityTrendPointDto::date))
            .toList();
    return new MaturityTrendDto(organization.getId(), organization.getName(), points);
  }

  /**
   * El responsable no elige: es su organización, y pedir otra es un intento de ver datos ajenos. El
   * administrador elige una (la tendencia es siempre de una sola organización); si todavía no
   * eligió, se usa la organización activa con la respuesta más reciente, para que el Panel no
   * arranque vacío.
   */
  private UUID resolveOrganization(UUID requested) {
    if (currentUser.isPrismaAdmin()) {
      if (requested != null) {
        return requested;
      }
      return responseRepo
          .findOrganizationIdsByLatestResponse(org.springframework.data.domain.PageRequest.of(0, 1))
          .stream()
          .findFirst()
          .orElseThrow(
              () -> new InvalidRequestException("Elegí la organización para ver su tendencia"));
    }
    UUID own = currentUser.currentTenantId();
    if (requested != null && !requested.equals(own)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Solo podés ver la tendencia de tu organización");
    }
    currentUser.assertOrganizationAccess(own);
    return own;
  }

  /**
   * Mide UNA evaluación (controles cumplidos y madurez promedio) con el mismo cálculo que la
   * tendencia. La usa el comparativo (PRI-320). No valida acceso: lo hace quien llama.
   */
  public MaturityTrendPointDto measure(Evaluation e) {
    MaturityTrendPointDto p = point(e, OffsetDateTime.now(), new HashMap<>(), new HashMap<>());
    if (p == null) {
      throw new IllegalStateException("La evaluación " + e.getId() + " no tiene respuestas");
    }
    return p;
  }

  /** {@code null} para una evaluación sin aprobar que todavía no tiene ninguna respuesta. */
  private MaturityTrendPointDto point(
      Evaluation e,
      OffsetDateTime now,
      Map<String, Set<UUID>> controlsByVersion,
      Map<String, Long> subcategoriesByVersion) {
    String version = e.getCatalogVersion();
    Set<UUID> versionControls =
        controlsByVersion.computeIfAbsent(
            version,
            v ->
                controlRepo.findByVersionOrdered(v).stream()
                    .map(CatalogControl::getId)
                    .collect(Collectors.toSet()));
    // Alcance = los controles del perfil (o todo el catálogo si no tiene), como en los reportes.
    Set<UUID> scope =
        e.getCommunityProfile() != null
            ? e.getCommunityProfile().getControls().stream()
                .map(CatalogControl::getId)
                .filter(versionControls::contains)
                .collect(Collectors.toSet())
            : versionControls;
    boolean finalized = ReportService.isReportable(e);
    List<EvaluationResponse> responses = responseRepo.findByEvaluationId(e.getId());
    if (!finalized && responses.isEmpty()) {
      return null;
    }
    long compliant =
        responses.stream()
            .filter(r -> Boolean.TRUE.equals(r.getCompliant()))
            .map(EvaluationResponse::getControl)
            .map(CatalogControl::getId)
            .filter(scope::contains)
            .distinct()
            .count();

    long subcategories =
        subcategoriesByVersion.computeIfAbsent(version, subcategoryRepo::countByCatalogVersion);
    int levelSum =
        matRepo.findByEvaluationId(e.getId()).stream()
            .mapToInt(MaturityResult::getCurrentLevel)
            .sum();

    return new MaturityTrendPointDto(
        e.getId(),
        e.getName(),
        e.getStatus().name(),
        !finalized,
        finalized ? e.getApprovedAt() : now,
        e.getApprovedAt(),
        e.getCommunityProfile() != null ? e.getCommunityProfile().getId() : null,
        e.getCommunityProfile() != null ? e.getCommunityProfile().getName() : FULL_CATALOG,
        (int) compliant,
        scope.size(),
        scope.isEmpty() ? 0.0 : round(compliant * 100.0 / scope.size(), 1),
        subcategories == 0 ? 0.0 : round(levelSum / (double) subcategories, 2),
        e.getGlobalMaturity());
  }

  private static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
