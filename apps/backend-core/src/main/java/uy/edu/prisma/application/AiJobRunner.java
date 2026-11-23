package uy.edu.prisma.application;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.exception.AiGuideException;
import uy.edu.prisma.domain.exception.AiUnavailableException;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;

/**
 * Ejecuta UN trabajo ya reclamado: llama a backend-ai (sin transacción abierta, tarda decenas de
 * segundos) y deja el resultado o la falla en la cola. Nunca lanza: un trabajo que explota no debe
 * tumbar al hilo del trabajador.
 */
@Component
public class AiJobRunner {

  private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

  private final AiEvidenceClient aiClient;
  private final AiJobQueue queue;
  private final AiJobRepository jobRepo;
  private final EvidenceIndexService indexService;
  private final CitationPrecomputeService precomputeService;
  private final EvidenceClassificationService classificationService;

  public AiJobRunner(
      AiEvidenceClient aiClient,
      AiJobQueue queue,
      AiJobRepository jobRepo,
      EvidenceIndexService indexService,
      CitationPrecomputeService precomputeService,
      EvidenceClassificationService classificationService) {
    this.aiClient = aiClient;
    this.queue = queue;
    this.jobRepo = jobRepo;
    this.indexService = indexService;
    this.precomputeService = precomputeService;
    this.classificationService = classificationService;
  }

  // Modo investigación (C8). Por setter y opcional: el servicio funciona igual sin trazas.
  private AiTraceService traceService;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTraceService(AiTraceService traceService) {
    this.traceService = traceService;
  }

  public void run(AiJob job) {
    switch (job.getType()) {
      case GUIDE -> runGuide(job);
      case EVIDENCE_INDEX -> runIndex(job);
      case CITATIONS_PRECOMPUTE -> runPrecompute(job);
      case EVIDENCE_CLASSIFY -> runClassify(job);
    }
  }

  /** Indexa una evidencia y deja su estado al día; si falla, la cola reintenta o queda en ERROR. */
  private void runIndex(AiJob job) {
    long started = System.nanoTime();
    UUID evidenceId = UUID.fromString(String.valueOf(job.getParams().get("evidenceId")));
    try {
      EvidenceIndexService.IndexInput input = indexService.prepare(evidenceId);
      if (input == null) {
        queue.completeWithoutResult(job.getId(), elapsedMs(started)); // la evidencia ya no existe
        return;
      }
      boolean indexed =
          input.fileUrl() != null
              && aiClient.ingest(
                  input.organizationId(),
                  input.evaluationId(),
                  input.evidenceId(),
                  input.controlId(),
                  input.fileName(),
                  input.fileUrl());
      if (indexed) {
        indexService.markIndexed(evidenceId);
        queue.completeWithoutResult(job.getId(), elapsedMs(started));
      } else {
        failIndex(job, evidenceId, started);
      }
    } catch (RuntimeException e) {
      log.error("Indexación de la evidencia {} falló de forma inesperada", evidenceId, e);
      failIndex(job, evidenceId, started);
    }
  }

  private void failIndex(AiJob job, UUID evidenceId, long started) {
    String reason =
        "No se pudo indexar la evidencia: el asistente de IA no respondió o no pudo leerla.";
    queue.fail(job.getId(), EvidenceIndexService.INDEX_FAILED, reason, true, elapsedMs(started));
    // La cola decidió: si reencoló, la evidencia vuelve a QUEUED; si se acabaron los intentos,
    // ERROR.
    Status after = jobRepo.findById(job.getId()).map(AiJob::getStatus).orElse(Status.FAILED);
    if (after == Status.QUEUED) {
      indexService.markQueued(evidenceId);
    } else {
      indexService.markError(evidenceId, reason);
    }
  }

  /** Sugiere controles para una evidencia global ya indexada; si falla la IA, la cola reintenta. */
  private void runClassify(AiJob job) {
    long started = System.nanoTime();
    try {
      UUID evidenceId = UUID.fromString(String.valueOf(job.getParams().get("evidenceId")));
      classificationService.run(evidenceId);
      queue.completeWithoutResult(job.getId(), elapsedMs(started));
    } catch (AiUnavailableException e) {
      queue.fail(
          job.getId(),
          AiGuideException.AI_UNAVAILABLE,
          "El asistente de IA no está disponible en este momento",
          true,
          elapsedMs(started));
    } catch (RuntimeException e) {
      log.error("Preclasificación {} falló de forma inesperada", job.getId(), e);
      queue.fail(
          job.getId(),
          "INTERNAL_ERROR",
          "Error interno al preclasificar la evidencia",
          false,
          elapsedMs(started));
    }
  }

  private void runPrecompute(AiJob job) {
    long started = System.nanoTime();
    try {
      UUID evaluationId = UUID.fromString(String.valueOf(job.getParams().get("evaluationId")));
      precomputeService.run(evaluationId);
      queue.completeWithoutResult(job.getId(), elapsedMs(started));
    } catch (AiUnavailableException e) {
      queue.fail(
          job.getId(),
          AiGuideException.AI_UNAVAILABLE,
          "El asistente de IA no está disponible en este momento",
          true,
          elapsedMs(started));
    } catch (RuntimeException e) {
      log.error("Precálculo de citas {} falló de forma inesperada", job.getId(), e);
      queue.fail(
          job.getId(),
          "INTERNAL_ERROR",
          "Error interno al precalcular las citas",
          false,
          elapsedMs(started));
    }
  }

  private void runGuide(AiJob job) {
    long started = System.nanoTime();
    try {
      Map<String, Object> p = job.getParams();
      // Modo investigación (C8): solo si la organización lo tiene encendido se pide la traza y
      // se fija una semilla aleatoria por pedido. Apagado, no viaja ni se guarda nada (D2).
      boolean research = traceService != null && traceService.isEnabled(job.getOrganizationId());
      Long seed = research ? traceService.newSeed() : null;
      GuideResult result =
          aiClient.generateGuide(
              str(p, "controlCode"),
              str(p, "description"),
              str(p, "functionName"),
              str(p, "categoryName"),
              str(p, "subcategoryName"),
              ((Number) p.get("currentLevel")).intValue(),
              ((Number) p.get("targetLevel")).intValue(),
              research,
              seed);
      UUID traceId = null;
      if (research) {
        try {
          traceId = traceService.recordGuide(result.trace(), seed);
        } catch (RuntimeException e) {
          log.warn("No se pudo guardar la traza del trabajo {}: {}", job.getId(), e.getMessage());
        }
      }
      queue.complete(job.getId(), result, elapsedMs(started), traceId);
    } catch (AiGuideException e) {
      queue.fail(job.getId(), e.getCode(), e.getMessage(), e.isRetryable(), elapsedMs(started));
    } catch (RuntimeException e) {
      log.error("Trabajo de IA {} falló de forma inesperada", job.getId(), e);
      queue.fail(
          job.getId(),
          "INTERNAL_ERROR",
          "Error interno al generar la guía",
          false,
          elapsedMs(started));
    }
  }

  private static String str(Map<String, Object> params, String key) {
    Object value = params.get(key);
    return value == null ? null : value.toString();
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }
}
