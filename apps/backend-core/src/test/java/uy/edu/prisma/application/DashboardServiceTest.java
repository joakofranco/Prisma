package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.ImprovementPlanRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private OrganizationRepository orgRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private ImprovementPlanRepository planRepo;
  @Mock private CurrentUserService currentUser;

  private DashboardService service;

  @BeforeEach
  void setUp() {
    // PRISMA_ADMIN: estos tests cubren las metricas sin acotar por organizacion. El aislamiento
    // por tenant y por auditor se prueba aparte, mas abajo.
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    service = new DashboardService(evalRepo, orgRepo, matRepo, planRepo, currentUser);
  }

  @Test
  void getStatsWithEmptyData() {
    when(evalRepo.count()).thenReturn(0L);
    when(orgRepo.countByEnabledTrue()).thenReturn(0L);
    when(evalRepo.countByStatus(any())).thenReturn(0L);
    when(planRepo.countByStatusNot(any())).thenReturn(0L);
    when(evalRepo.findAll(any(PageRequest.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(), PageRequest.of(0, 100, Sort.by("createdAt").descending()), 0));

    DashboardStatsDto stats = service.getStats();

    assertEquals(0L, stats.totalEvaluations());
    assertEquals(0L, stats.activeOrganizations());
    assertEquals(0.0, stats.avgMaturityLevel());
    assertEquals(0L, stats.pendingImprovements());
    assertEquals(Evaluation.Status.values().length, stats.evaluationsByStatus().size());
    assertTrue(stats.maturityByFunction().isEmpty());
  }

  @Test
  void getStatsComputesAveragesAndFunctionLevels() {
    UUID evalId = UUID.randomUUID();
    Evaluation eval =
        Evaluation.builder().id(evalId).name("E").organization(null).globalMaturity(3).build();
    PageRequest pr = PageRequest.of(0, 100, Sort.by("createdAt").descending());

    MaturityResult r1 =
        MaturityResult.builder()
            .id(UUID.randomUUID())
            .functionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .functionName("Riesgos")
            .currentLevel(4)
            .build();
    MaturityResult r2 =
        MaturityResult.builder()
            .id(UUID.randomUUID())
            .functionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .functionName("Riesgos")
            .currentLevel(2)
            .build();

    when(evalRepo.count()).thenReturn(2L);
    when(orgRepo.countByEnabledTrue()).thenReturn(1L);
    when(evalRepo.countByStatus(any())).thenReturn(0L);
    when(evalRepo.countByStatus(Evaluation.Status.DRAFT)).thenReturn(2L);
    when(planRepo.countByStatusNot(any())).thenReturn(5L);
    when(evalRepo.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(eval), pr, 1));
    when(matRepo.findByEvaluationId(evalId)).thenReturn(List.of(r1, r2));

    DashboardStatsDto stats = service.getStats();

    assertEquals(2L, stats.totalEvaluations());
    assertEquals(1L, stats.activeOrganizations());
    assertEquals(3.0, stats.avgMaturityLevel());
    assertEquals(5L, stats.pendingImprovements());
    assertEquals(2L, stats.evaluationsByStatus().get("DRAFT"));
    assertEquals(1, stats.maturityByFunction().size());
    assertEquals("Riesgos", stats.maturityByFunction().get(0).name());
    assertEquals(3, stats.maturityByFunction().get(0).level());
  }

  @Test
  void getStatsSkipsEvaluationsWithoutMaturity() {
    Evaluation noMaturity = Evaluation.builder().id(UUID.randomUUID()).name("E").build();
    PageRequest pr = PageRequest.of(0, 100, Sort.by("createdAt").descending());

    when(evalRepo.count()).thenReturn(1L);
    when(orgRepo.countByEnabledTrue()).thenReturn(0L);
    when(evalRepo.countByStatus(any())).thenReturn(0L);
    when(evalRepo.findAll(any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(noMaturity), pr, 1));
    when(matRepo.findByEvaluationId(noMaturity.getId())).thenReturn(List.of());

    DashboardStatsDto stats = service.getStats();

    assertEquals(0.0, stats.avgMaturityLevel());
    assertTrue(stats.maturityByFunction().isEmpty());
  }

  // ---- Aislamiento por tenant / por organizaciones asignadas a un auditor ----

  @Test
  void getStatsScopesToOwnTenantForNonGlobalRole() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(false);
    UUID tenantId = UUID.randomUUID();
    when(currentUser.currentTenantId()).thenReturn(tenantId);
    Set<UUID> ids = Set.of(tenantId);
    PageRequest pr = PageRequest.of(0, 100, Sort.by("createdAt").descending());

    when(evalRepo.countByOrganizationIdIn(ids)).thenReturn(3L);
    when(orgRepo.countByIdInAndEnabledTrue(ids)).thenReturn(1L);
    when(planRepo.countByEvaluation_Organization_IdInAndStatusNot(eq(ids), any())).thenReturn(2L);
    when(evalRepo.countByOrganizationIdInAndStatus(eq(ids), any())).thenReturn(0L);
    when(evalRepo.findByOrganizationIdInOrderByCreatedAtDesc(eq(ids), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(), pr, 0));

    DashboardStatsDto stats = service.getStats();

    assertEquals(3L, stats.totalEvaluations());
    assertEquals(1L, stats.activeOrganizations());
    assertEquals(2L, stats.pendingImprovements());
    // Nunca debe caer a las variantes sin filtro (fuga de datos entre tenants).
    verify(evalRepo, never()).count();
    verify(orgRepo, never()).countByEnabledTrue();
    verify(evalRepo, never()).findAll(any(PageRequest.class));
  }

  @Test
  void getStatsScopesToUnionOfAuditorOrganizations() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    UUID org1 = UUID.randomUUID();
    UUID org2 = UUID.randomUUID();
    Set<UUID> ids = Set.of(org1, org2);
    when(currentUser.auditedOrganizationIds()).thenReturn(ids);
    PageRequest pr = PageRequest.of(0, 100, Sort.by("createdAt").descending());

    when(evalRepo.countByOrganizationIdIn(ids)).thenReturn(7L);
    when(orgRepo.countByIdInAndEnabledTrue(ids)).thenReturn(2L);
    when(planRepo.countByEvaluation_Organization_IdInAndStatusNot(eq(ids), any())).thenReturn(4L);
    when(evalRepo.countByOrganizationIdInAndStatus(eq(ids), any())).thenReturn(0L);
    when(evalRepo.findByOrganizationIdInOrderByCreatedAtDesc(eq(ids), any(PageRequest.class)))
        .thenReturn(new PageImpl<>(List.of(), pr, 0));

    DashboardStatsDto stats = service.getStats();

    assertEquals(7L, stats.totalEvaluations());
    assertEquals(2L, stats.activeOrganizations());
    assertEquals(4L, stats.pendingImprovements());
  }

  @Test
  void getStatsEmptyWhenAuditorHasNoOrganizationsAssigned() {
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.isAuditor()).thenReturn(true);
    when(currentUser.auditedOrganizationIds()).thenReturn(Set.of());

    DashboardStatsDto stats = service.getStats();

    assertEquals(0L, stats.totalEvaluations());
    assertEquals(0L, stats.activeOrganizations());
    verifyNoInteractions(evalRepo, orgRepo, matRepo, planRepo);
  }
}
