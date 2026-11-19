package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CommunityProfile;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaturityTrendServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private OrganizationRepository orgRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CatalogSubcategoryRepository subcategoryRepo;
  @Mock private EvaluationResponseRepository responseRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private CurrentUserService currentUser;

  private MaturityTrendService service;
  private Organization org;
  private CatalogControl c1;
  private CatalogControl c2;
  private CatalogControl c3;
  private CatalogControl c4;
  private CommunityProfile basico;

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2025-03-14T10:00:00-03:00");

  @BeforeEach
  void setUp() {
    service =
        new MaturityTrendService(
            evalRepo, orgRepo, controlRepo, subcategoryRepo, responseRepo, matRepo, currentUser);
    org = Organization.builder().id(UUID.randomUUID()).name("Intendencia de Rivera").build();
    when(orgRepo.findById(org.getId())).thenReturn(Optional.of(org));
    c1 = control();
    c2 = control();
    c3 = control();
    c4 = control();
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1, c2, c3, c4));
    when(subcategoryRepo.countByCatalogVersion("5.0")).thenReturn(4L);
    basico =
        CommunityProfile.builder()
            .id(UUID.randomUUID())
            .name("Básico")
            .catalogVersion("5.0")
            .controls(new java.util.HashSet<>(Set.of(c1, c2)))
            .build();
    // Por defecto, un responsable de esta organización.
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(org.getId());
  }

  private static CatalogControl control() {
    return CatalogControl.builder().id(UUID.randomUUID()).build();
  }

  private Evaluation evaluation(
      String name, Evaluation.Status status, OffsetDateTime approvedAt, CommunityProfile profile) {
    return Evaluation.builder()
        .id(UUID.randomUUID())
        .name(name)
        .organization(org)
        .status(status)
        .approvedAt(approvedAt)
        .communityProfile(profile)
        .catalogVersion("5.0")
        .globalMaturity(1)
        .build();
  }

  private void responses(Evaluation e, CatalogControl... compliant) {
    List<EvaluationResponse> list = new java.util.ArrayList<>();
    for (CatalogControl c : compliant) {
      list.add(EvaluationResponse.builder().evaluation(e).control(c).compliant(true).build());
    }
    // Un control respondido «No cumple»: no suma.
    list.add(EvaluationResponse.builder().evaluation(e).control(c4).compliant(false).build());
    when(responseRepo.findByEvaluationId(e.getId())).thenReturn(list);
  }

  private void levels(Evaluation e, int... currentLevels) {
    List<MaturityResult> list = new java.util.ArrayList<>();
    for (int level : currentLevels) {
      list.add(MaturityResult.builder().evaluation(e).currentLevel(level).build());
    }
    when(matRepo.findByEvaluationId(e.getId())).thenReturn(list);
  }

  @Test
  void oneOrderedPointPerFinalizedEvaluationWithItsProfileAndMeasures() {
    Evaluation later = evaluation("2026", Evaluation.Status.APPROVED, T0.plusYears(1), null);
    Evaluation first = evaluation("2025", Evaluation.Status.ARCHIVED, T0, basico);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any()))
        .thenReturn(List.of(later, first));
    responses(first, c1, c3); // c3 está fuera del perfil Básico: no cuenta
    responses(later, c1, c2, c3);
    levels(first, 1, 0, 0, 0);
    levels(later, 2, 1, 1, 1);

    MaturityTrendDto trend = service.trend(null);

    assertEquals("Intendencia de Rivera", trend.organizationName());
    assertEquals(2, trend.points().size());
    MaturityTrendPointDto p1 = trend.points().get(0);
    assertEquals("2025", p1.evaluationName());
    assertEquals(basico.getId(), p1.profileId());
    assertEquals("Básico", p1.profileName());
    assertEquals(1, p1.compliantControls());
    assertEquals(2, p1.totalControls());
    assertEquals(50.0, p1.compliancePercent());
    assertEquals(0.25, p1.maturityAverage());
    MaturityTrendPointDto p2 = trend.points().get(1);
    assertNull(p2.profileId());
    assertEquals("Catálogo completo", p2.profileName());
    assertEquals(3, p2.compliantControls());
    assertEquals(4, p2.totalControls());
    assertEquals(75.0, p2.compliancePercent());
    assertEquals(1.25, p2.maturityAverage());
    assertEquals(1, p2.globalMaturity());
  }

  @Test
  void archivedWithoutApprovalIsLeftOut() {
    Evaluation discarded = evaluation("Descartada", Evaluation.Status.ARCHIVED, null, null);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any()))
        .thenReturn(List.of(discarded));

    assertTrue(service.trend(null).points().isEmpty());
  }

  @Test
  void inProgressEvaluationsComeAsProvisionalPointsWithTodaysValue() {
    Evaluation approved = evaluation("2025", Evaluation.Status.APPROVED, T0, basico);
    Evaluation inAudit = evaluation("2026", Evaluation.Status.IN_AUDIT, null, basico);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any()))
        .thenReturn(List.of(inAudit, approved));
    responses(approved, c1);
    responses(inAudit, c1, c2);
    levels(approved, 1, 0, 0, 0);
    levels(inAudit, 1, 1, 0, 0);

    List<MaturityTrendPointDto> points = service.trend(null).points();

    assertEquals(2, points.size());
    assertFalse(points.get(0).provisional());
    assertEquals(T0, points.get(0).date());
    MaturityTrendPointDto provisional = points.get(1);
    assertTrue(provisional.provisional());
    assertEquals("IN_AUDIT", provisional.status());
    assertNull(provisional.approvedAt());
    assertNotNull(provisional.date());
    assertEquals(100.0, provisional.compliancePercent());
  }

  @Test
  void anUnapprovedEvaluationWithoutResponsesIsLeftOut() {
    Evaluation empty = evaluation("Borrador", Evaluation.Status.DRAFT, null, null);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any()))
        .thenReturn(List.of(empty));
    when(responseRepo.findByEvaluationId(empty.getId())).thenReturn(List.of());

    assertTrue(service.trend(null).points().isEmpty());
  }

  @Test
  void everyStatusIsQueried() {
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any())).thenReturn(List.of());

    service.trend(null);

    verify(evalRepo)
        .findByOrganizationIdAndStatusIn(
            org.getId(), java.util.EnumSet.allOf(Evaluation.Status.class));
  }

  @Test
  void theResponsibleCannotAskForAnotherOrganization() {
    assertThrows(AccessDeniedException.class, () -> service.trend(UUID.randomUUID()));
    verify(evalRepo, never()).findByOrganizationIdAndStatusIn(any(), any());
  }

  @Test
  void theResponsibleMayNameTheirOwnOrganization() {
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any())).thenReturn(List.of());

    assertEquals(org.getId(), service.trend(org.getId()).organizationId());
    verify(currentUser).assertOrganizationAccess(org.getId());
  }

  @Test
  void withoutActivityTheAdminMustChooseTheOrganization() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(responseRepo.findOrganizationIdsByLatestResponse(any())).thenReturn(List.of());

    assertThrows(InvalidRequestException.class, () -> service.trend(null));
  }

  @Test
  void withoutChoosingTheAdminSeesTheOrganizationWithTheLatestActivity() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(responseRepo.findOrganizationIdsByLatestResponse(any())).thenReturn(List.of(org.getId()));
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any())).thenReturn(List.of());

    assertEquals(org.getId(), service.trend(null).organizationId());
  }

  @Test
  void theAdminSeesTheChosenOrganization() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any())).thenReturn(List.of());

    assertEquals("Intendencia de Rivera", service.trend(org.getId()).organizationName());
  }

  @Test
  void anOrganizationThatDoesNotExistIsNotFound() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    assertThrows(ResourceNotFoundException.class, () -> service.trend(UUID.randomUUID()));
  }

  @Test
  void anEmptyScopeOrCatalogDoesNotDivideByZero() {
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of());
    when(subcategoryRepo.countByCatalogVersion("5.0")).thenReturn(0L);
    Evaluation e = evaluation("Vacía", Evaluation.Status.APPROVED, T0, null);
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any())).thenReturn(List.of(e));
    when(responseRepo.findByEvaluationId(e.getId())).thenReturn(List.of());
    when(matRepo.findByEvaluationId(e.getId())).thenReturn(List.of());

    MaturityTrendPointDto p = service.trend(null).points().get(0);

    assertEquals(0, p.totalControls());
    assertEquals(0.0, p.compliancePercent());
    assertEquals(0.0, p.maturityAverage());
  }
}
