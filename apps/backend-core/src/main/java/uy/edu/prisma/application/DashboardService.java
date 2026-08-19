package uy.edu.prisma.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.web.dto.Dto.DashboardStatsDto;
import uy.edu.prisma.web.dto.Dto.MaturityByFunctionDto;

@Service
@Transactional(readOnly = true)
public class DashboardService {

  private final EvaluationRepository evalRepo;
  private final OrganizationRepository orgRepo;
  private final MaturityResultRepository matRepo;
  private final ImprovementPlanRepository planRepo;
  private final CurrentUserService currentUser;

  public DashboardService(
      EvaluationRepository evalRepo,
      OrganizationRepository orgRepo,
      MaturityResultRepository matRepo,
      ImprovementPlanRepository planRepo,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.orgRepo = orgRepo;
    this.matRepo = matRepo;
    this.planRepo = planRepo;
    this.currentUser = currentUser;
  }

  // Para roles acotados a un tenant, TODAS las metricas se restringen a su propia organizacion.
  // Antes esto era global para cualquier usuario autenticado: un ORG_RESPONSIBLE veia conteos y
  // el widget de "Madurez por Funcion" de la evaluacion mas reciente de CUALQUIER organizacion,
  // no la propia (leak de datos entre tenants).
  //
  // orgIds == null: sin restriccion (PRISMA_ADMIN, unico rol verdaderamente global). Un Set (con
  // uno o mas elementos): AUDITOR ve la union de las organizaciones que tiene asignadas (antes
  // era global igual que PRISMA_ADMIN -- veia metricas de TODAS las organizaciones sin importar
  // cuales le tocaba auditar); el resto de los roles ve exactamente su propio tenant (un set de
  // un elemento). Un Set vacio (auditor sin organizaciones asignadas, o usuario sin tenant) no
  // consulta nada: no hay "sin restriccion" implicito para ellos.
  public DashboardStatsDto getStats() {
    Set<UUID> orgIds;
    if (currentUser.isPrismaAdmin()) {
      orgIds = null;
    } else if (currentUser.isAuditor()) {
      orgIds = currentUser.auditedOrganizationIds();
    } else {
      UUID tenantId = currentUser.currentTenantId();
      orgIds = tenantId == null ? Set.of() : Set.of(tenantId);
    }
    if (orgIds != null && orgIds.isEmpty()) {
      return new DashboardStatsDto(0, 0, 0.0, 0, new LinkedHashMap<>(), new ArrayList<>());
    }

    long totalEvals = orgIds == null ? evalRepo.count() : evalRepo.countByOrganizationIdIn(orgIds);
    long activeOrgs =
        orgIds == null ? orgRepo.countByEnabledTrue() : orgRepo.countByIdInAndEnabledTrue(orgIds);
    long pendingImprovements =
        orgIds == null
            ? planRepo.countByStatusNot(ImprovementPlan.PlanStatus.COMPLETED)
            : planRepo.countByEvaluation_Organization_IdInAndStatusNot(
                orgIds, ImprovementPlan.PlanStatus.COMPLETED);

    Map<String, Long> statusMap = new LinkedHashMap<>();
    for (Evaluation.Status s : Evaluation.Status.values()) {
      statusMap.put(
          s.name(),
          orgIds == null
              ? evalRepo.countByStatus(s)
              : evalRepo.countByOrganizationIdInAndStatus(orgIds, s));
    }

    // Get latest evaluations with maturity results to compute averages
    org.springframework.data.domain.Pageable recentPage =
        org.springframework.data.domain.PageRequest.of(
            0, 100, org.springframework.data.domain.Sort.by("createdAt").descending());
    List<Evaluation> recentEvals =
        (orgIds == null
                ? evalRepo.findAll(recentPage)
                : evalRepo.findByOrganizationIdInOrderByCreatedAtDesc(orgIds, recentPage))
            .getContent();

    double avgMaturity =
        recentEvals.stream()
            .filter(e -> e.getGlobalMaturity() != null)
            .mapToInt(Evaluation::getGlobalMaturity)
            .average()
            .orElse(0.0);

    // Maturity by function from latest evaluation
    List<MaturityByFunctionDto> maturityByFunction = new ArrayList<>();
    if (!recentEvals.isEmpty()) {
      UUID latestEvalId = recentEvals.get(0).getId();
      List<MaturityResult> latestResults = matRepo.findByEvaluationId(latestEvalId);

      Map<UUID, List<MaturityResult>> byFunction =
          latestResults.stream().collect(Collectors.groupingBy(MaturityResult::getFunctionId));

      for (Map.Entry<UUID, List<MaturityResult>> entry : byFunction.entrySet()) {
        String funcName = entry.getValue().get(0).getFunctionName();
        int avgLevel =
            (int)
                Math.round(
                    entry.getValue().stream()
                        .mapToInt(MaturityResult::getCurrentLevel)
                        .average()
                        .orElse(0));
        maturityByFunction.add(new MaturityByFunctionDto(funcName, avgLevel));
      }
    }

    return new DashboardStatsDto(
        totalEvals, activeOrgs, avgMaturity, pendingImprovements, statusMap, maturityByFunction);
  }
}
