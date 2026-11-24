package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import uy.edu.prisma.domain.exception.InvalidRequestException;
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
    when(repo.countByOrganizationIdAndTypeAndBatchIdIsNullAndStatusIn(
            eq(orgId), eq(AiJob.Type.GUIDE), any()))
        .thenReturn(20L);

    assertThrows(TooManyRequestsException.class, () -> service.enqueueGuide(request(2)));
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void withoutOrganizationTheLimitAppliesPerUser() {
    when(currentUser.currentTenantId()).thenReturn(null);
    when(repo.countByOrganizationIdIsNullAndRequestedByAndTypeAndBatchIdIsNullAndStatusIn(
            eq(userId), eq(AiJob.Type.GUIDE), any()))
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

  // ---- Lote de guías (F3, hito 4) ----

  private uy.edu.prisma.web.dto.Dto.GuideBatchRequestDto batchOf(int n) {
    java.util.List<GuideJobRequestDto> items = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      UUID controlId = UUID.randomUUID();
      CatalogControl c =
          CatalogControl.builder().id(controlId).code("PR.AA-0" + i).description("d" + i).build();
      when(controlRepo.findById(controlId)).thenReturn(Optional.of(c));
      items.add(new GuideJobRequestDto(controlId, null, "F", "C", "S", 0, 2));
    }
    return new uy.edu.prisma.web.dto.Dto.GuideBatchRequestDto(items);
  }

  @Test
  void aBatchCreatesOneBatchPriorityJobPerControlSharingTheBatchId() {
    var accepted = service.enqueueGuideBatch(batchOf(3));

    assertEquals(3, accepted.jobs().size());
    assertNotNull(accepted.batchId());
    ArgumentCaptor<AiJob> saved = ArgumentCaptor.forClass(AiJob.class);
    verify(repo, org.mockito.Mockito.times(3)).saveAndFlush(saved.capture());
    for (AiJob job : saved.getAllValues()) {
      assertEquals(AiJob.PRIORITY_BATCH, job.getPriority(), "un pedido interactivo pasa antes");
      assertEquals(accepted.batchId(), job.getBatchId());
      assertEquals(AiJob.Type.GUIDE, job.getType());
    }
    assertEquals("QUEUED", accepted.jobs().get(0).status());
  }

  @Test
  void aControlThatAlreadyHasAnActiveJobReusesItInsteadOfDuplicatingIt() {
    var request = batchOf(2);
    AiJob running = activeJob(Status.RUNNING);
    // Solo el primer control tiene ya un trabajo activo.
    when(repo.findFirstByDedupKeyAndStatusIn(
            eq(
                AiJobService.dedupKey(
                    AiJob.Type.GUIDE, orgId, userId, request.items().get(0).controlId(), 0, 2)),
            any()))
        .thenReturn(Optional.of(running));

    var accepted = service.enqueueGuideBatch(request);

    assertEquals(running.getId(), accepted.jobs().get(0).jobId());
    assertEquals("RUNNING", accepted.jobs().get(0).status());
    verify(repo, org.mockito.Mockito.times(1)).saveAndFlush(any(AiJob.class));
  }

  @Test
  void aBatchBiggerThanTheLimitIsRejected() {
    assertThrows(InvalidRequestException.class, () -> service.enqueueGuideBatch(batchOf(21)));
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void aBatchThatWouldExceedThePendingBatchLimitOfTheOrganizationIsRejectedWhole() {
    when(repo.countByOrganizationIdAndBatchIdIsNotNullAndStatusIn(eq(orgId), any()))
        .thenReturn(55L);

    assertThrows(TooManyRequestsException.class, () -> service.enqueueGuideBatch(batchOf(10)));
    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void batchJobsDoNotCountAgainstTheLimitOfSingleRequests() {
    // Lo suelto usa su propio conteo (guías sin lote): un lote lleno no lo agota.
    when(repo.countByOrganizationIdAndBatchIdIsNotNullAndStatusIn(eq(orgId), any()))
        .thenReturn(60L);

    var accepted = service.enqueueGuide(request(2));

    assertNotNull(accepted.jobId());
  }

  @Test
  void theBatchLimitsOfAnOrganizationWithoutContextAreCountedPerUser() {
    when(currentUser.currentTenantId()).thenReturn(null);
    when(repo.countByOrganizationIdIsNullAndRequestedByAndBatchIdIsNotNullAndStatusIn(
            eq(userId), any()))
        .thenReturn(60L);

    assertThrows(TooManyRequestsException.class, () -> service.enqueueGuideBatch(batchOf(1)));
  }

  @Test
  void getManyReturnsTheJobsAndSkipsTheOnesThatNoLongerExist() {
    AiJob a = activeJob(Status.QUEUED);
    when(repo.findById(a.getId())).thenReturn(Optional.of(a));
    UUID gone = UUID.randomUUID();
    when(repo.findById(gone)).thenReturn(Optional.empty());
    when(repo.countAhead(anyInt(), any())).thenReturn(0L);

    List<AiJobDto> result = service.getMany(List.of(a.getId(), gone, a.getId()));

    assertEquals(1, result.size(), "sin duplicados ni los que ya no existen");
    assertEquals(a.getId(), result.get(0).id());
  }

  @Test
  void getManyStillVerifiesTheOrganizationOfEachJob() {
    AiJob a = activeJob(Status.QUEUED);
    when(repo.findById(a.getId())).thenReturn(Optional.of(a));
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(orgId);

    assertThrows(AccessDeniedException.class, () -> service.getMany(List.of(a.getId())));
  }

  @Test
  void theJobDtoCarriesTheControlAndTheBatchSoTheScreenCanLocateIt() {
    UUID controlId = UUID.randomUUID();
    UUID batchId = UUID.randomUUID();
    AiJob a = activeJob(Status.RUNNING);
    a.setBatchId(batchId);
    a.setParams(Map.of("controlId", controlId.toString()));
    when(repo.findById(a.getId())).thenReturn(Optional.of(a));

    AiJobDto dto = service.get(a.getId());

    assertEquals(controlId, dto.controlId());
    assertEquals(batchId, dto.batchId());
  }

  @Test
  void cancellingABatchCancelsWhatIsStillQueued() {
    UUID batchId = UUID.randomUUID();
    AiJob a = activeJob(Status.QUEUED);
    when(repo.findByBatchId(batchId)).thenReturn(List.of(a));
    when(repo.cancelQueuedOfBatch(eq(batchId), any(OffsetDateTime.class))).thenReturn(7);

    assertEquals(7, service.cancelBatch(batchId));
  }

  @Test
  void cancellingAnUnknownBatchIsNotFound() {
    UUID batchId = UUID.randomUUID();
    when(repo.findByBatchId(batchId)).thenReturn(List.of());

    assertThrows(ResourceNotFoundException.class, () -> service.cancelBatch(batchId));
  }

  @Test
  void cancellingABatchOfAnotherOrganizationIsDenied() {
    UUID batchId = UUID.randomUUID();
    when(repo.findByBatchId(batchId)).thenReturn(List.of(activeJob(Status.QUEUED)));
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(orgId);

    assertThrows(AccessDeniedException.class, () -> service.cancelBatch(batchId));
    verify(repo, never()).cancelQueuedOfBatch(any(), any());
  }
}
