package uy.edu.prisma.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiConfig.AiProperties;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;

/**
 * Máquina de estados de la cola de IA, del lado del trabajador. Cada método es una transacción
 * corta: el llamado a backend-ai (decenas de segundos) se hace FUERA de cualquier transacción, en
 * {@link AiJobRunner}.
 *
 * <p>QUEUED → RUNNING (reclamo) → SUCCEEDED | FAILED, o de vuelta a QUEUED con espera creciente si
 * la falla es reintentable y quedan intentos.
 */
@Service
public class AiJobQueue {

  private static final Logger log = LoggerFactory.getLogger(AiJobQueue.class);

  /** Margen sobre el timeout de lectura antes de dar por muerta a la réplica que lo reclamó. */
  private static final Duration LOCK_GRACE = Duration.ofSeconds(60);

  static final String WORKER_LOST = "WORKER_LOST";

  private final AiJobRepository repo;
  private final AiGeneratedGuideRepository guideRepo;
  private final CatalogControlRepository controlRepo;
  private final AiJobProperties props;
  private final AiProperties aiProps;
  private final AiJobMetrics metrics;

  public AiJobQueue(
      AiJobRepository repo,
      AiGeneratedGuideRepository guideRepo,
      CatalogControlRepository controlRepo,
      AiJobProperties props,
      AiProperties aiProps,
      AiJobMetrics metrics) {
    this.repo = repo;
    this.guideRepo = guideRepo;
    this.controlRepo = controlRepo;
    this.props = props;
    this.aiProps = aiProps;
    this.metrics = metrics;
  }

  /**
   * Reclama los trabajos que se pueden empezar ahora sin pasar el tope GLOBAL de concurrencia
   * (contando los que corren en cualquier réplica). Se llama bajo ShedLock para que el conteo y el
   * reclamo sean atómicos entre réplicas.
   */
  @Transactional
  public List<AiJob> claimNext() {
    int free = props.concurrency() - (int) repo.countByStatus(Status.RUNNING);
    if (free <= 0) {
      return List.of();
    }
    List<AiJob> jobs = repo.lockQueued(free);
    OffsetDateTime now = OffsetDateTime.now();
    for (AiJob job : jobs) {
      job.setStatus(Status.RUNNING);
      job.setAttempts(job.getAttempts() + 1);
      job.setStartedAt(now);
      job.setLockedUntil(now.plus(aiProps.guideReadTimeout()).plus(LOCK_GRACE));
      if (job.getQueueWaitMs() == null) {
        long waited = Duration.between(job.getCreatedAt(), now).toMillis();
        job.setQueueWaitMs(waited);
        metrics.recordQueueWait(waited);
      }
    }
    repo.saveAll(jobs);
    return jobs;
  }

  /** Reencola (o da por fallidos) los trabajos cuya réplica murió a mitad de camino. */
  @Transactional
  public int recoverStale() {
    List<AiJob> stale = repo.findExpiredLocks(Status.RUNNING, OffsetDateTime.now());
    for (AiJob job : stale) {
      log.warn("Trabajo de IA {} sin heartbeat: se reintenta o se da por fallido", job.getId());
      applyFailure(
          job,
          WORKER_LOST,
          "El procesamiento se interrumpió antes de terminar",
          true,
          job.getRunMs() == null ? 0 : job.getRunMs());
    }
    repo.saveAll(stale);
    return stale.size();
  }

  @Transactional
  public void complete(UUID jobId, GuideResult result, long runMs) {
    complete(jobId, result, runMs, null);
  }

  /** Igual, y deja en la guía la traza del modo investigación (C8) si se guardó. */
  @Transactional
  public void complete(UUID jobId, GuideResult result, long runMs, UUID traceId) {
    AiJob job = repo.findById(jobId).orElse(null);
    if (job == null || job.getStatus() != Status.RUNNING) {
      return; // cancelado, vencido o ya resuelto por otro camino
    }
    Map<String, Object> params = job.getParams();
    UUID controlId = UUID.fromString(String.valueOf(params.get("controlId")));
    AiGeneratedGuide guide =
        guideRepo.save(
            AiGeneratedGuide.builder()
                .jobId(job.getId())
                .organizationId(job.getOrganizationId())
                .requestedBy(job.getRequestedBy())
                .control(controlRepo.getReferenceById(controlId))
                .currentLevel(((Number) params.get("currentLevel")).intValue())
                .targetLevel(((Number) params.get("targetLevel")).intValue())
                .model(result.model())
                .summary(result.summary())
                .tips(result.tips())
                .traceId(traceId)
                .build());
    OffsetDateTime now = OffsetDateTime.now();
    job.setStatus(Status.SUCCEEDED);
    job.setResultRef(guide.getId());
    job.setFinishedAt(now);
    job.setExpiresAt(now.plusDays(props.resultTtlDays()));
    job.setLockedUntil(null);
    job.setErrorCode(null);
    job.setErrorMessage(null);
    job.setRunMs(runMs);
    job.setMetrics(new HashMap<>(result.metrics()));
    repo.save(job);
    metrics.recordRun(runMs);
    metrics.recordOllama(result.metrics());
    metrics.recordFinished(job.getType().name(), "SUCCEEDED", null);
  }

  /** Termina bien un trabajo que no produce una guía (indexar evidencia, precalcular citas). */
  @Transactional
  public void completeWithoutResult(UUID jobId, long runMs) {
    AiJob job = repo.findById(jobId).orElse(null);
    if (job == null || job.getStatus() != Status.RUNNING) {
      return;
    }
    OffsetDateTime now = OffsetDateTime.now();
    job.setStatus(Status.SUCCEEDED);
    job.setFinishedAt(now);
    job.setExpiresAt(now.plusDays(props.resultTtlDays()));
    job.setLockedUntil(null);
    job.setErrorCode(null);
    job.setErrorMessage(null);
    job.setRunMs(runMs);
    repo.save(job);
    metrics.recordRun(runMs);
    metrics.recordFinished(job.getType().name(), "SUCCEEDED", null);
  }

  @Transactional
  public void fail(UUID jobId, String code, String message, boolean retryable, long runMs) {
    AiJob job = repo.findById(jobId).orElse(null);
    if (job == null || job.getStatus() != Status.RUNNING) {
      return;
    }
    applyFailure(job, code, message, retryable, runMs);
    repo.save(job);
  }

  private void applyFailure(AiJob job, String code, String message, boolean retryable, long runMs) {
    OffsetDateTime now = OffsetDateTime.now();
    job.setErrorCode(code);
    job.setErrorMessage(message);
    job.setLockedUntil(null);
    job.setRunMs(runMs);
    metrics.recordRun(runMs);
    if (retryable && job.getAttempts() < job.getMaxAttempts()) {
      // Espera creciente: base, 2×base, 4×base...
      long delay = (long) props.retryBackoffSeconds() << (job.getAttempts() - 1);
      job.setStatus(Status.QUEUED);
      job.setNextAttemptAt(now.plusSeconds(delay));
      metrics.recordRetry();
      log.info(
          "Trabajo de IA {} reencolado ({}): intento {}/{}, reintenta en {} s",
          job.getId(),
          code,
          job.getAttempts(),
          job.getMaxAttempts(),
          delay);
      return;
    }
    job.setStatus(Status.FAILED);
    job.setFinishedAt(now);
    log.warn("Trabajo de IA {} falló ({}): {}", job.getId(), code, message);
    metrics.recordFinished(job.getType().name(), "FAILED", code);
  }

  /** Limpieza diaria: vence los resultados viejos y borra trabajos terminados hace tiempo. */
  @Transactional
  public void housekeeping() {
    OffsetDateTime now = OffsetDateTime.now();
    int expired = repo.expireSucceeded(now);
    int purged =
        repo.deleteFinishedBefore(
            Set.of(Status.EXPIRED, Status.FAILED, Status.CANCELLED),
            now.minusDays(props.resultTtlDays()));
    if (expired > 0 || purged > 0) {
      log.info("Trabajos de IA: {} vencidos, {} borrados", expired, purged);
    }
  }
}
