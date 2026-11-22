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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.exception.TooManyRequestsException;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.AiJobDto;
import uy.edu.prisma.web.dto.Dto.GuideJobRequestDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiJobServiceTest {

  @Mock private AiJobRepository repo;
  @Mock private AiGeneratedGuideRepository guideRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CurrentUserService currentUser;

  private AiJobService service;
  private final UUID orgId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service =
        new AiJobService(
            repo, guideRepo, controlRepo, evaluationRepo, currentUser, new AiJobProperties());
    control =
        CatalogControl.builder().id(UUID.randomUUID()).code("PR.AA-01").description("desc").build();
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(currentUser.currentUserId()).thenReturn(userId);
    when(currentUser.currentTenantId()).thenReturn(orgId);
    when(repo.saveAndFlush(any(AiJob.class)))
        .thenAnswer(
            inv -> {
              AiJob job = inv.getArgument(0);
              job.setId(UUID.randomUUID());
              return job;
            });
  }

  private GuideJobRequestDto request(int target) {
    return new GuideJobRequestDto(
        control.getId(), null, "Proteger", "Acceso", "Identidad", 0, target);
  }

  private AiJob activeJob(Status status) {
    return AiJob.builder()
        .id(UUID.randomUUID())
        .type(AiJob.Type.GUIDE)
        .status(status)
        .organizationId(orgId)
        .requestedBy(userId)
        .dedupKey("k")
        .build();
  }

  @Test
  void enqueueSavesAQueuedJobWithTheControlFromTheCatalog() {
    var accepted = service.enqueueGuide(request(2));

    assertNotNull(accepted.jobId());
    assertEquals("QUEUED", accepted.status());
    ArgumentCaptor<AiJob> saved = ArgumentCaptor.forClass(AiJob.class);
    verify(repo).saveAndFlush(saved.capture());
    AiJob job = saved.getValue();
    assertEquals(AiJob.Type.GUIDE, job.getType());
    assertEquals(orgId, job.getOrganizationId());
    assertEquals(userId, job.getRequestedBy());
    assertEquals(AiJob.PRIORITY_INTERACTIVE, job.getPriority());
    assertEquals("PR.AA-01", job.getParams().get("controlCode"));
    assertEquals("desc", job.getParams().get("description"));
    assertEquals(2, job.getParams().get("targetLevel"));
    assertEquals(64, job.getDedupKey().length());
  }

  @Test
  void askingForTheSameThingTwiceReturnsTheActiveJobInsteadOfDuplicatingIt() {
    AiJob running = activeJob(Status.RUNNING);
    when(repo.findFirstByDedupKeyAndStatusIn(anyString(), any())).thenReturn(Optional.of(running));

    var accepted = service.enqueueGuide(request(2));

    assertEquals(running.getId(), accepted.jobId());
    assertEquals("RUNNING", accepted.status());
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void dedupKeyDependsOnOrganizationControlAndLevels() {
    UUID control2 = UUID.randomUUID();
    String base = AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control.getId(), 0, 2);
    assertEquals(
        base, AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control.getId(), 0, 2));
    assertNotEquals(
        base,
        AiJobService.dedupKey(AiJob.Type.GUIDE, UUID.randomUUID(), userId, control.getId(), 0, 2));
    assertNotEquals(base, AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control2, 0, 2));
    assertNotEquals(
        base, AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control.getId(), 1, 2));
    assertNotEquals(
        base, AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control.getId(), 0, 3));
  }

  @Test
  void dedupKeyOfAnOrganizationIsSharedByItsUsersButNotWithoutOrganization() {
    UUID other = UUID.randomUUID();
    assertEquals(
        AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, userId, control.getId(), 0, 2),
        AiJobService.dedupKey(AiJob.Type.GUIDE, orgId, other, control.getId(), 0, 2));
    assertNotEquals(
        AiJobService.dedupKey(AiJob.Type.GUIDE, null, userId, control.getId(), 0, 2),
        AiJobService.dedupKey(AiJob.Type.GUIDE, null, other, control.getId(), 0, 2));
  }

  @Test
  void whenTwoIdenticalRequestsRaceTheLoserGetsTheWinnersJob() {
    AiJob winner = activeJob(Status.QUEUED);
    when(repo.findFirstByDedupKeyAndStatusIn(anyString(), any()))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(winner));
    when(repo.saveAndFlush(any(AiJob.class)))
        .thenThrow(new DataIntegrityViolationException("uq_ai_job_active_dedup"));

    var accepted = service.enqueueGuide(request(2));

    assertEquals(winner.getId(), accepted.jobId());
  }

  @Test
  void enqueueIsRejectedWhenTheOrganizationHasTooManyActiveJobs() {
    when(repo.countByOrganizationIdAndStatusIn(eq(orgId), any())).thenReturn(20L);

    assertThrows(TooManyRequestsException.class, () -> service.enqueueGuide(request(2)));
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void withoutOrganizationTheLimitAppliesPerUser() {
    when(currentUser.currentTenantId()).thenReturn(null);
    when(repo.countByOrganizationIdIsNullAndRequestedByAndStatusIn(eq(userId), any()))
        .thenReturn(20L);

    assertThrows(TooManyRequestsException.class, () -> service.enqueueGuide(request(2)));
  }

  @Test
  void anEvaluationDefinesTheOrganizationAndItsAccessIsChecked() {
    UUID evalId = UUID.randomUUID();
    UUID evalOrg = UUID.randomUUID();
    when(evaluationRepo.findOrganizationId(evalId)).thenReturn(Optional.of(evalOrg));

    service.enqueueGuide(new GuideJobRequestDto(control.getId(), evalId, null, null, null, 0, 1));

    verify(currentUser).assertOrganizationAccess(evalOrg);
    ArgumentCaptor<AiJob> saved = ArgumentCaptor.forClass(AiJob.class);
    verify(repo).saveAndFlush(saved.capture());
    assertEquals(evalOrg, saved.getValue().getOrganizationId());
  }

  @Test
  void enqueueFailsForAnUnknownControl() {
    UUID unknown = UUID.randomUUID();
    when(controlRepo.findById(unknown)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.enqueueGuide(new GuideJobRequestDto(unknown, null, null, null, null, 0, 1)));
  }

  @Test
  void getReverifiesTheOrganizationOnEveryCall() {
    AiJob job = activeJob(Status.QUEUED);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(orgId);

    assertThrows(AccessDeniedException.class, () -> service.get(job.getId()));
  }

  @Test
  void getReportsTheQueuePositionWhileQueued() {
    AiJob job = activeJob(Status.QUEUED);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    when(repo.countAhead(anyInt(), any())).thenReturn(3L);

    AiJobDto dto = service.get(job.getId());

    assertEquals(4, dto.queuePosition());
    assertNull(dto.guide());
  }

  @Test
  void getReturnsTheGuideAndMarksItShownTheFirstTimeTheRequesterSeesIt() {
    AiGeneratedGuide guide =
        AiGeneratedGuide.builder()
            .id(UUID.randomUUID())
            .requestedBy(userId)
            .control(control)
            .summary("Resumen")
            .tips(List.of("Paso 1"))
            .model("llama3:8b")
            .build();
    AiJob job = activeJob(Status.SUCCEEDED);
    job.setResultRef(guide.getId());
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    when(guideRepo.findById(guide.getId())).thenReturn(Optional.of(guide));

    AiJobDto dto = service.get(job.getId());

    assertEquals("Resumen", dto.guide().summary());
    assertEquals(List.of("Paso 1"), dto.guide().tips());
    assertNull(dto.queuePosition());
    assertEquals(AiGeneratedGuide.Status.SHOWN, guide.getStatus());
    assertNotNull(guide.getShownAt());
    verify(guideRepo).save(guide);
  }

  @Test
  void aGuideSeenBySomeoneElseInTheOrganizationIsNotMarkedShown() {
    AiGeneratedGuide guide =
        AiGeneratedGuide.builder()
            .id(UUID.randomUUID())
            .requestedBy(UUID.randomUUID())
            .control(control)
            .build();
    AiJob job = activeJob(Status.SUCCEEDED);
    job.setResultRef(guide.getId());
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    when(guideRepo.findById(guide.getId())).thenReturn(Optional.of(guide));

    service.get(job.getId());

    assertEquals(AiGeneratedGuide.Status.GENERATED, guide.getStatus());
    verify(guideRepo, never()).save(any());
  }

  @Test
  void getFailsForAnUnknownJob() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.get(id));
  }

  @Test
  void aJobWithoutOrganizationIsOnlyVisibleToItsRequesterOrAnAdmin() {
    AiJob job = activeJob(Status.QUEUED);
    job.setOrganizationId(null);
    job.setRequestedBy(UUID.randomUUID());
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(null);

    assertThrows(AccessDeniedException.class, () -> service.get(job.getId()));

    when(currentUser.isPrismaAdmin()).thenReturn(true);
    assertDoesNotThrow(() -> service.get(job.getId()));
  }

  @Test
  void cancelMovesAQueuedJobToCancelled() {
    AiJob job = activeJob(Status.QUEUED);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    when(repo.cancelIfQueued(eq(job.getId()), any(OffsetDateTime.class))).thenReturn(1);

    service.cancel(job.getId());

    verify(repo).cancelIfQueued(eq(job.getId()), any(OffsetDateTime.class));
  }

  @Test
  void aRunningJobCannotBeCancelled() {
    AiJob job = activeJob(Status.RUNNING);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));

    assertThrows(ConflictException.class, () -> service.cancel(job.getId()));
    verify(repo, never()).cancelIfQueued(any(), any());
  }

  @Test
  void cancelLosesGracefullyWhenAnotherReplicaClaimedTheJobFirst() {
    AiJob job = activeJob(Status.QUEUED);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));
    when(repo.cancelIfQueued(eq(job.getId()), any())).thenReturn(0);

    assertThrows(ConflictException.class, () -> service.cancel(job.getId()));
  }

  @Test
  void cancelIsIdempotentForAnAlreadyCancelledJob() {
    AiJob job = activeJob(Status.CANCELLED);
    when(repo.findById(job.getId())).thenReturn(Optional.of(job));

    assertEquals("CANCELLED", service.cancel(job.getId()).status());
  }

  @Test
  void listMineReturnsTheRequestersJobsWithoutTheGuideBody() {
    AiJob job = activeJob(Status.SUCCEEDED);
    when(repo.findByRequestedByOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of(job));

    List<AiJobDto> list = service.listMine();

    assertEquals(1, list.size());
    assertNull(list.get(0).guide());
  }

  @Test
  void listMineIsEmptyWhenTheUserIsNotTraceable() {
    when(currentUser.currentUserId()).thenReturn(null);

    assertTrue(service.listMine().isEmpty());
    verifyNoInteractions(repo);
  }

  @Test
  void activeStatusesAreQueuedAndRunning() {
    assertEquals(
        Set.of(Status.QUEUED, Status.RUNNING),
        Set.of(Status.values()).stream()
            .filter(Status::isActive)
            .collect(java.util.stream.Collectors.toSet()));
  }
}
