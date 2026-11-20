package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.config.AiConfig.AiProperties;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiJobQueueTest {

  @Mock private AiJobRepository repo;
  @Mock private AiGeneratedGuideRepository guideRepo;
  @Mock private CatalogControlRepository controlRepo;

  private SimpleMeterRegistry registry;
  private AiJobQueue queue;
  private final UUID controlId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    // backoff de 10 s para poder verificar la espera creciente.
    AiJobProperties props = new AiJobProperties(true, 2, 20, 30, 2, 10, 2000L, 30);
    queue =
        new AiJobQueue(
            repo,
            guideRepo,
            controlRepo,
            props,
            new AiProperties("http://ai", "k"),
            new AiJobMetrics(registry, repo));
    when(repo.countByStatus(any())).thenReturn(0L);
  }

  private AiJob job(Status status, int attempts, int maxAttempts) {
    Map<String, Object> params = new HashMap<>();
    params.put("controlId", controlId.toString());
    params.put("currentLevel", 0);
    params.put("targetLevel", 2);
    return AiJob.builder()
        .id(UUID.randomUUID())
        .type(AiJob.Type.GUIDE)
        .status(status)
        .organizationId(UUID.randomUUID())
        .requestedBy(UUID.randomUUID())
        .dedupKey("k")
        .params(params)
        .attempts(attempts)
        .maxAttempts(maxAttempts)
        .createdAt(OffsetDateTime.now().minusSeconds(5))
        .build();
  }

  // ------------------------------------------------------------------ reclamo

  @Test
  void claimOnlyTakesTheFreeSlotsOfTheGlobalConcurrencyLimit() {
    when(repo.countByStatus(Status.RUNNING)).thenReturn(1L); // ya corre 1 (quizá en otra réplica)
    when(repo.lockQueued(1)).thenReturn(List.of(job(Status.QUEUED, 0, 2)));

    List<AiJob> claimed = queue.claimNext();

    assertEquals(1, claimed.size());
    verify(repo).lockQueued(1);
  }

  @Test
  void claimDoesNothingWhenTheLimitIsAlreadyReached() {
    when(repo.countByStatus(Status.RUNNING)).thenReturn(2L);

    assertTrue(queue.claimNext().isEmpty());
    verify(repo, never()).lockQueued(anyInt());
  }

  @Test
  void claimMarksTheJobRunningAndRecordsTheQueueWait() {
    AiJob queued = job(Status.QUEUED, 0, 2);
    when(repo.lockQueued(2)).thenReturn(List.of(queued));

    queue.claimNext();

    assertEquals(Status.RUNNING, queued.getStatus());
    assertEquals(1, queued.getAttempts());
    assertNotNull(queued.getStartedAt());
    assertTrue(queued.getQueueWaitMs() >= 5000);
    // El reclamo vence después del timeout de la guía (300 s por defecto en AiProperties = 120 s).
    assertTrue(queued.getLockedUntil().isAfter(OffsetDateTime.now().plusSeconds(120)));
    assertEquals(1, registry.get("prisma.ai.job.queue.wait").timer().count());
    verify(repo).saveAll(List.of(queued));
  }

  @Test
  void aRetryDoesNotCountTheQueueWaitAgain() {
    AiJob retried = job(Status.QUEUED, 1, 2);
    retried.setQueueWaitMs(1234L);
    when(repo.lockQueued(2)).thenReturn(List.of(retried));

    queue.claimNext();

    assertEquals(1234L, retried.getQueueWaitMs());
    assertEquals(2, retried.getAttempts());
  }

  // ----------------------------------------------------------------- resultado

  @Test
  void completeStoresTheGuideAndMarksTheJobSucceeded() {
    AiJob running = job(Status.RUNNING, 1, 2);
    when(repo.findById(running.getId())).thenReturn(Optional.of(running));
    CatalogControl control = CatalogControl.builder().id(controlId).build();
    when(controlRepo.getReferenceById(controlId)).thenReturn(control);
    UUID guideId = UUID.randomUUID();
    when(guideRepo.save(any(AiGeneratedGuide.class)))
        .thenAnswer(
            inv -> {
              AiGeneratedGuide g = inv.getArgument(0);
              g.setId(guideId);
              return g;
            });

    queue.complete(
        running.getId(),
        new GuideResult(
            "Resumen",
            List.of("A", "B"),
            "llama3:8b",
            Map.of("eval_count", 40, "prompt_eval_count", 900, "eval_duration", 4_000_000_000L)),
        45_000);

    ArgumentCaptor<AiGeneratedGuide> guide = ArgumentCaptor.forClass(AiGeneratedGuide.class);
    verify(guideRepo).save(guide.capture());
    assertEquals(running.getOrganizationId(), guide.getValue().getOrganizationId());
    assertEquals(running.getRequestedBy(), guide.getValue().getRequestedBy());
    assertEquals(2, guide.getValue().getTargetLevel());
    assertEquals("llama3:8b", guide.getValue().getModel());
    assertEquals(List.of("A", "B"), guide.getValue().getTips());

    assertEquals(Status.SUCCEEDED, running.getStatus());
    assertEquals(guideId, running.getResultRef());
    assertEquals(45_000L, running.getRunMs());
    assertNotNull(running.getFinishedAt());
    assertTrue(running.getExpiresAt().isAfter(OffsetDateTime.now().plusDays(29)));
    assertNull(running.getLockedUntil());
    assertEquals(40, ((Number) running.getMetrics().get("eval_count")).intValue());

    assertEquals(1, registry.get("prisma.ai.job.run").timer().count());
    assertEquals(
        900.0, registry.get("prisma.ai.ollama.tokens").tag("kind", "prompt").counter().count());
    assertEquals(
        40.0, registry.get("prisma.ai.ollama.tokens").tag("kind", "completion").counter().count());
    assertEquals(
        4.0, registry.get("prisma.ai.ollama.seconds").tag("phase", "generation").counter().count());
    assertEquals(
        1.0, registry.get("prisma.ai.jobs.finished").tag("outcome", "SUCCEEDED").counter().count());
  }

  @Test
  void completeIgnoresAJobThatIsNoLongerRunning() {
    AiJob cancelled = job(Status.CANCELLED, 1, 2);
    when(repo.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));

    queue.complete(cancelled.getId(), new GuideResult("R", List.of(), "m", Map.of()), 10);

    verify(guideRepo, never()).save(any());
    assertEquals(Status.CANCELLED, cancelled.getStatus());
  }

  // ------------------------------------------------------------------- fallas

  @Test
  void aRetryableFailureRequeuesWithGrowingBackoff() {
    AiJob first = job(Status.RUNNING, 1, 3);
    when(repo.findById(first.getId())).thenReturn(Optional.of(first));

    queue.fail(first.getId(), "AI_UNAVAILABLE", "caído", true, 1000);

    assertEquals(Status.QUEUED, first.getStatus());
    assertNull(first.getFinishedAt());
    assertEquals("AI_UNAVAILABLE", first.getErrorCode());
    Duration firstWait = Duration.between(OffsetDateTime.now(), first.getNextAttemptAt());
    assertTrue(firstWait.getSeconds() >= 8 && firstWait.getSeconds() <= 10, "10 s: " + firstWait);

    AiJob second = job(Status.RUNNING, 2, 3);
    when(repo.findById(second.getId())).thenReturn(Optional.of(second));
    queue.fail(second.getId(), "AI_UNAVAILABLE", "caído", true, 1000);
    Duration secondWait = Duration.between(OffsetDateTime.now(), second.getNextAttemptAt());
    assertTrue(
        secondWait.getSeconds() >= 18 && secondWait.getSeconds() <= 20, "20 s: " + secondWait);

    assertEquals(2.0, registry.get("prisma.ai.job.retries").counter().count());
  }

  @Test
  void whenAttemptsRunOutTheJobFailsWithAClearMessage() {
    AiJob last = job(Status.RUNNING, 2, 2);
    when(repo.findById(last.getId())).thenReturn(Optional.of(last));

    queue.fail(last.getId(), "AI_UNAVAILABLE", "El asistente de IA no está disponible", true, 500);

    assertEquals(Status.FAILED, last.getStatus());
    assertEquals("AI_UNAVAILABLE", last.getErrorCode());
    assertEquals("El asistente de IA no está disponible", last.getErrorMessage());
    assertNotNull(last.getFinishedAt());
    assertEquals(
        1.0,
        registry
            .get("prisma.ai.jobs.finished")
            .tag("outcome", "FAILED")
            .tag("error", "AI_UNAVAILABLE")
            .counter()
            .count());
  }

  @Test
  void aNonRetryableFailureFailsImmediatelyEvenWithAttemptsLeft() {
    AiJob j = job(Status.RUNNING, 1, 3);
    when(repo.findById(j.getId())).thenReturn(Optional.of(j));

    queue.fail(j.getId(), "PROMPT_TOO_LARGE", "no entra", false, 100);

    assertEquals(Status.FAILED, j.getStatus());
    assertEquals(0.0, registry.get("prisma.ai.job.retries").counter().count());
  }

  @Test
  void failIgnoresAJobThatIsNoLongerRunning() {
    AiJob done = job(Status.SUCCEEDED, 1, 2);
    when(repo.findById(done.getId())).thenReturn(Optional.of(done));

    queue.fail(done.getId(), "X", "x", true, 1);

    assertEquals(Status.SUCCEEDED, done.getStatus());
  }

  // ------------------------------------------------------------ réplica muerta

  @Test
  void staleRunningJobsAreRequeuedOrFailedWhenTheReplicaDied() {
    AiJob withAttemptsLeft = job(Status.RUNNING, 1, 2);
    AiJob exhausted = job(Status.RUNNING, 2, 2);
    when(repo.findExpiredLocks(eq(Status.RUNNING), any()))
        .thenReturn(List.of(withAttemptsLeft, exhausted));

    assertEquals(2, queue.recoverStale());

    assertEquals(Status.QUEUED, withAttemptsLeft.getStatus());
    assertEquals(Status.FAILED, exhausted.getStatus());
    assertEquals(AiJobQueue.WORKER_LOST, exhausted.getErrorCode());
  }

  // ----------------------------------------------------------------- limpieza

  @Test
  void housekeepingExpiresResultsAndPurgesOldTerminalJobs() {
    queue.housekeeping();

    verify(repo).expireSucceeded(any(OffsetDateTime.class));
    verify(repo).deleteFinishedBefore(any(), any(OffsetDateTime.class));
  }
}
