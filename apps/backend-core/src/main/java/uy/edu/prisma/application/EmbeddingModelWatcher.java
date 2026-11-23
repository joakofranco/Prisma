package uy.edu.prisma.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vigía del modelo de embedding: unos segundos después de arrancar y cada tantos minutos compara el
 * modelo de backend-ai con el último indexado y, si cambió, dispara la reindexación (ver {@link
 * EmbeddingReindexService}). Con ShedLock: corre en una sola réplica. Si backend-ai todavía no
 * responde (arranca después), el chequeo siguiente lo recupera.
 */
@Component
public class EmbeddingModelWatcher {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingModelWatcher.class);

  private final EmbeddingReindexService reindex;

  public EmbeddingModelWatcher(EmbeddingReindexService reindex) {
    this.reindex = reindex;
  }

  @Scheduled(
      initialDelayString = "${prisma.ai.embedding-check-initial-ms:30000}",
      fixedDelayString = "${prisma.ai.embedding-check-ms:300000}")
  @SchedulerLock(name = "embeddingModelWatcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void run() {
    try {
      reindex.check();
    } catch (RuntimeException e) {
      log.warn("No se pudo verificar el modelo de embedding: {}", e.getMessage());
    }
  }
}
