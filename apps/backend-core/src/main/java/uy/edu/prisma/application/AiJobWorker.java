package uy.edu.prisma.application;

import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uy.edu.prisma.domain.entity.AiJob;

/**
 * Trabajador de la cola de IA. Cada réplica de backend-core mira la cola cada pocos segundos; el
 * reclamo corre bajo ShedLock (una réplica a la vez) para que contar los trabajos en ejecución y
 * reclamar sea atómico entre réplicas y el tope GLOBAL de concurrencia contra Ollama se respete. El
 * reclamo en sí usa {@code FOR UPDATE SKIP LOCKED}, así que un trabajo nunca se ejecuta dos veces.
 *
 * <p>Se apaga con {@code prisma.ai.jobs.enabled=false} (la API de encolado sigue funcionando).
 */
@Component
@ConditionalOnProperty(name = "prisma.ai.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class AiJobWorker {

  private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

  private final AiJobQueue queue;
  private final AiJobRunner runner;
  private final TaskExecutor executor;

  public AiJobWorker(
      AiJobQueue queue, AiJobRunner runner, @Qualifier("aiJobExecutor") TaskExecutor executor) {
    this.queue = queue;
    this.runner = runner;
    this.executor = executor;
  }

  @Scheduled(
      fixedDelayString = "${prisma.ai.jobs.poll-interval-ms:2000}",
      initialDelayString = "${prisma.ai.jobs.poll-interval-ms:2000}")
  @SchedulerLock(name = "aiJobClaim", lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
  public void poll() {
    try {
      queue.recoverStale();
      List<AiJob> claimed = queue.claimNext();
      for (AiJob job : claimed) {
        executor.execute(() -> runner.run(job));
      }
    } catch (RuntimeException e) {
      // Un fallo transitorio (base caída, p. ej.) no debe cortar el ciclo de sondeo.
      log.warn("No se pudo procesar la cola de IA: {}", e.getMessage());
    }
  }
}
