package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
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
import uy.edu.prisma.application.EvaluationProgressService.Granularity;
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.CatalogCategory;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.web.dto.Dto.EvaluationAuditCycleEventDto;
import uy.edu.prisma.web.dto.Dto.EvaluationProgressDto;
import uy.edu.prisma.web.dto.Dto.ProgressPeriodDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationProgressServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvaluationResponseRepository responseRepo;
  @Mock private AuditLogRepository auditRepo;
  @Mock private EvidenceRepository evidenceRepo;
  @Mock private AuditLogService auditLogService;
  @Mock private UserRepository userRepo;
  @Mock private CurrentUserService currentUser;

  private EvaluationProgressService service;
  private Organization org;
  private Evaluation eval;
  private CatalogControl protect1;
  private CatalogControl protect2;
  private CatalogControl identify1;
  private CatalogControl outOfProfile;
  private User ana;

  // Lunes 2026-06-01 al mediodía en la zona del servidor: semanas predecibles.
  private static final OffsetDateTime MONDAY =
      LocalDate.of(2026, 6, 1).atTime(12, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime();

  @BeforeEach
  void setUp() {
    service =
        new EvaluationProgressService(
            evalRepo,
            controlRepo,
            responseRepo,
            auditRepo,
            evidenceRepo,
            auditLogService,
            userRepo,
            currentUser);
    org = Organization.builder().id(UUID.randomUUID()).name("Ministerio").build();
    protect1 = control("PR.AA-1", "Proteger");
    protect2 = control("PR.DS-2", "Proteger");
    identify1 = control("ID.AM-1", "Identificar");
    outOfProfile = control("DE.CM-1", "Detectar");
    when(controlRepo.findByVersionOrdered("5.0"))
        .thenReturn(List.of(protect1, protect2, identify1, outOfProfile));
    CommunityProfile estandar =
        CommunityProfile.builder()
            .id(UUID.randomUUID())
            .name("Estándar")
            .catalogVersion("5.0")
            .controls(new HashSet<>(Set.of(protect1, protect2, identify1)))
            .build();
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("EVA-1")
            .organization(org)
            .status(Evaluation.Status.IN_PROGRESS)
            .communityProfile(estandar)
            .catalogVersion("5.0")
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    ana = User.builder().id(UUID.randomUUID()).firstName("Ana").lastName("Pérez").build();
    when(userRepo.findAllById(any())).thenReturn(List.of(ana));
    when(responseRepo.findByEvaluationId(eval.getId())).thenReturn(List.of());
    when(auditLogService.getEvaluationAuditTrail(eval.getId())).thenReturn(List.of());
    when(currentUser.isPrismaAdmin()).thenReturn(false);
    when(currentUser.currentTenantId()).thenReturn(org.getId());
  }

  private static CatalogControl control(String code, String functionName) {
    CatalogFunction function =
        CatalogFunction.builder().id(UUID.randomUUID()).name(functionName).build();
    CatalogCategory category =
        CatalogCategory.builder().id(UUID.randomUUID()).function_(function).build();
    CatalogSubcategory sub =
        CatalogSubcategory.builder().id(UUID.randomUUID()).category(category).build();
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .code(code)
        .description("Control " + code)
        .subcategories(new HashSet<>(Set.of(sub)))
        .build();
  }

  private AuditLog saved(CatalogControl c, boolean compliant, OffsetDateTime at, String spacing) {
    return AuditLog.builder()
        .id(UUID.randomUUID())
        .userId(ana.getId())
        .action("SAVE_RESPONSE")
        .resource("evaluation:" + eval.getId())
        .payload(
            "{\"controlId\":"
                + spacing
                + "\""
                + c.getId()
                + "\",\"compliant\":"
                + spacing
                + compliant
                + "}")
        .createdAt(at)
        .build();
  }

  private void history(AuditLog... logs) {
    when(auditRepo.findByResourceAndActionInOrderByCreatedAtAsc(
            "evaluation:" + eval.getId(), List.of("SAVE_RESPONSE")))
        .thenReturn(List.of(logs));
  }

  @Test
  void rebuildsWeekByWeekWhichControlsWereGainedAndLostAndTheActivity() {
    history(
        saved(protect1, true, MONDAY, ""),
        saved(identify1, false, MONDAY.plusHours(1), " "),
        saved(protect1, true, MONDAY.plusDays(2), ""), // mismo valor: no es un cambio
        saved(protect2, true, MONDAY.plusWeeks(1), ""),
        saved(identify1, true, MONDAY.plusWeeks(1).plusDays(1), ""),
        saved(protect1, false, MONDAY.plusWeeks(2), ""),
        saved(outOfProfile, true, MONDAY.plusWeeks(2), "")); // fuera del perfil: no cuenta
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()))
        .thenReturn(
            List.of(
                Evidence.builder().uploadedAt(MONDAY.plusDays(3)).build(),
                Evidence.builder().uploadedAt(MONDAY.plusWeeks(1)).build()));

    EvaluationProgressDto progress = service.progress(eval.getId(), Granularity.WEEK);

    assertEquals("EVA-1", progress.evaluationName());
    assertEquals("Estándar", progress.profileName());
    assertEquals(3, progress.totalControls());
    assertEquals(MONDAY, progress.startedAt());
    assertEquals("WEEK", progress.granularity());
    List<ProgressPeriodDto> weeks = progress.periods();
    assertTrue(weeks.size() >= 4);
    // El primero es la semana anterior, vacía: la curva arranca en 0.
    ProgressPeriodDto start = weeks.get(0);
    assertEquals(LocalDate.of(2026, 5, 25), start.periodStart());
    assertEquals(0, start.compliantControls());
    assertEquals(0, start.answeredControls());
    ProgressPeriodDto w1 = weeks.get(1);
    assertEquals(LocalDate.of(2026, 6, 1), w1.periodStart());
    assertEquals(LocalDate.of(2026, 6, 7), w1.periodEnd());
    assertEquals(1, w1.compliantControls());
    assertEquals(2, w1.answeredControls());
    assertEquals(3, w1.responseActivity()); // el guardado repetido cuenta como actividad
    assertEquals(1, w1.evidenceActivity());
    assertEquals(List.of("PR.AA-1"), w1.gained().stream().map(c -> c.code()).toList());
    assertEquals("Proteger", w1.gained().get(0).functionName());
    assertEquals("Ana Pérez", w1.gained().get(0).changedBy());
    assertTrue(w1.lost().isEmpty());
    ProgressPeriodDto w2 = weeks.get(2);
    assertEquals(1, w2.evidenceActivity());
    assertEquals(3, w2.compliantControls());
    assertEquals(
        Set.of("PR.DS-2", "ID.AM-1"),
        w2.gained().stream().map(c -> c.code()).collect(java.util.stream.Collectors.toSet()));
    ProgressPeriodDto w3 = weeks.get(3);
    assertEquals(2, w3.compliantControls());
    assertEquals(List.of("PR.AA-1"), w3.lost().stream().map(c -> c.code()).toList());
    assertEquals(2, progress.compliantControls());
    assertEquals(3, progress.answeredControls());
  }

  @Test
  void aResponseWithoutLogEntersWithItsCurrentValue() {
    history(saved(protect1, true, MONDAY, ""));
    when(responseRepo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                EvaluationResponse.builder()
                    .evaluation(eval)
                    .control(identify1)
                    .compliant(true)
                    .respondedBy(ana)
                    .respondedAt(MONDAY.plusDays(1))
                    .build(),
                EvaluationResponse.builder()
                    .evaluation(eval)
                    .control(protect1)
                    .compliant(true)
                    .respondedAt(MONDAY)
                    .build()));

    EvaluationProgressDto progress = service.progress(eval.getId(), Granularity.WEEK);

    assertEquals(2, progress.compliantControls());
    assertEquals(2, progress.periods().get(1).gained().size());
  }

  @Test
  void countsTheChangeOfTheLast30Days() {
    OffsetDateTime now = OffsetDateTime.now();
    history(
        saved(protect1, true, now.minusDays(60), ""),
        saved(protect2, true, now.minusDays(5), ""),
        saved(identify1, true, now.minusDays(2), ""));

    EvaluationProgressDto progress = service.progress(eval.getId());
    assertEquals(2, progress.last30DaysDelta());
    assertEquals(2, progress.last30DaysAnsweredDelta());
  }

  @Test
  void anApprovedEvaluationEndsOnItsApprovalWeek() {
    eval.setStatus(Evaluation.Status.APPROVED);
    eval.setApprovedAt(MONDAY.plusWeeks(1));
    history(saved(protect1, true, MONDAY, ""));

    // Semana vacía de arranque + la de la primera respuesta + la de la aprobación.
    assertEquals(3, service.progress(eval.getId(), Granularity.WEEK).periods().size());
  }

  @Test
  void withoutResponsesThereAreNoPeriods() {
    history();

    EvaluationProgressDto progress = service.progress(eval.getId());

    assertTrue(progress.periods().isEmpty());
    assertNull(progress.startedAt());
    assertEquals(0, progress.compliantControls());
  }

  @Test
  void statusChangesBecomeMarksWithTheirLabel() {
    history();
    when(auditLogService.getEvaluationAuditTrail(eval.getId()))
        .thenReturn(
            List.of(
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(), "UPDATE_STATUS", "READY_FOR_AUDIT", null, null, MONDAY, 1),
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(), "CERTIFY", null, null, null, MONDAY, 1),
                new EvaluationAuditCycleEventDto(
                    UUID.randomUUID(), "UPDATE_STATUS", "UNKNOWN", null, null, MONDAY, 1)));

    var events = service.progress(eval.getId()).statusEvents();

    assertEquals(1, events.size());
    assertEquals("READY_FOR_AUDIT", events.get(0).status());
    assertEquals(MONDAY, events.get(0).occurredAt());
  }

  @Test
  void theResponsibleCannotSeeAnotherOrganization() {
    when(currentUser.currentTenantId()).thenReturn(UUID.randomUUID());

    assertThrows(AccessDeniedException.class, () -> service.progress(eval.getId()));
    verify(auditRepo, never()).findByResourceAndActionInOrderByCreatedAtAsc(any(), any());
  }

  @Test
  void theAdminCanSeeAnyOrganization() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.currentTenantId()).thenReturn(null);
    history();

    assertNotNull(service.progress(eval.getId()));
  }

  @Test
  void anEvaluationThatDoesNotExistIsNotFound() {
    assertThrows(ResourceNotFoundException.class, () -> service.progress(UUID.randomUUID()));
  }

  @Test
  void malformedPayloadsAreIgnored() {
    when(auditRepo.findByResourceAndActionInOrderByCreatedAtAsc(any(), any()))
        .thenReturn(
            List.of(
                AuditLog.builder().payload(null).createdAt(MONDAY).build(),
                AuditLog.builder().payload("{\"controlId\":\"x\"}").createdAt(MONDAY).build()));

    assertTrue(service.progress(eval.getId()).periods().isEmpty());
    assertTrue(EvaluationProgressService.parseCompliant(null).isEmpty());
    assertNull(EvaluationProgressService.parseControl("{}"));
  }

  @Test
  void aControlWithoutFunctionSaysSo() {
    CatalogControl bare =
        CatalogControl.builder()
            .id(UUID.randomUUID())
            .requirement(uy.edu.prisma.domain.entity.CatalogRequirement.builder().build())
            .build();

    assertEquals(
        EvaluationProgressService.NO_FUNCTION, EvaluationProgressService.functionName(bare));
  }

  @Test
  void everythingLoadedInOneDayIsShownByDayStartingAtZero() {
    eval.setStatus(Evaluation.Status.APPROVED);
    eval.setApprovedAt(MONDAY.plusHours(5));
    history(saved(protect1, true, MONDAY, ""), saved(protect2, false, MONDAY.plusHours(1), ""));

    EvaluationProgressDto progress = service.progress(eval.getId());

    assertEquals("DAY", progress.granularity());
    assertEquals(2, progress.periods().size());
    assertEquals(LocalDate.of(2026, 5, 31), progress.periods().get(0).periodStart());
    assertEquals(0, progress.periods().get(0).answeredControls());
    assertEquals(2, progress.periods().get(1).answeredControls());
    assertEquals(2, progress.periods().get(1).responseActivity());
  }

  @Test
  void byMonthThePeriodsAreWholeMonths() {
    eval.setStatus(Evaluation.Status.APPROVED);
    eval.setApprovedAt(MONDAY.plusMonths(1));
    history(saved(protect1, true, MONDAY, ""));

    List<ProgressPeriodDto> months = service.progress(eval.getId(), Granularity.MONTH).periods();

    assertEquals(LocalDate.of(2026, 5, 1), months.get(0).periodStart());
    assertEquals(LocalDate.of(2026, 6, 1), months.get(1).periodStart());
    assertEquals(LocalDate.of(2026, 6, 30), months.get(1).periodEnd());
    assertEquals(3, months.size());
  }

  @Test
  void theAutomaticScaleDependsOnHowLongTheHistoryIs() {
    LocalDate first = LocalDate.of(2026, 1, 1);

    assertEquals(
        Granularity.DAY, EvaluationProgressService.resolve(null, first, first.plusDays(14)));
    assertEquals(
        Granularity.WEEK,
        EvaluationProgressService.resolve(Granularity.AUTO, first, first.plusDays(15)));
    assertEquals(
        Granularity.WEEK,
        EvaluationProgressService.resolve(Granularity.AUTO, first, first.plusDays(183)));
    assertEquals(
        Granularity.MONTH,
        EvaluationProgressService.resolve(Granularity.AUTO, first, first.plusDays(184)));
    assertEquals(
        Granularity.DAY,
        EvaluationProgressService.resolve(Granularity.DAY, first, first.plusYears(1)));
  }
}
