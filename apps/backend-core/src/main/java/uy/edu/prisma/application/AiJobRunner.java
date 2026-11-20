package uy.edu.prisma.application;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.exception.AiGuideException;
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

  public AiJobRunner(AiEvidenceClient aiClient, AiJobQueue queue) {
    this.aiClient = aiClient;
    this.queue = queue;
  }

  public void run(AiJob job) {
    long started = System.nanoTime();
    try {
      Map<String, Object> p = job.getParams();
      GuideResult result =
          aiClient.generateGuide(
              str(p, "controlCode"),
              str(p, "description"),
              str(p, "functionName"),
              str(p, "categoryName"),
              str(p, "subcategoryName"),
              ((Number) p.get("currentLevel")).intValue(),
              ((Number) p.get("targetLevel")).intValue());
      queue.complete(job.getId(), result, elapsedMs(started));
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
