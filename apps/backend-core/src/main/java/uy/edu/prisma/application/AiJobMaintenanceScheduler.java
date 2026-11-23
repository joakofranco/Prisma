package uy.edu.prisma.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limpieza diaria de la IA asíncrona: vence y borra trabajos viejos, y cierra las guías mostradas y
 * no usadas (a los 30 días cuentan como rechazadas, D11). Con ShedLock: corre en una sola réplica.
 */
@Component
public class AiJobMaintenanceScheduler {

  private static final Logger log = LoggerFactory.getLogger(AiJobMaintenanceScheduler.class);

  private final AiJobQueue queue;
  private final AiGuideService guides;
  private final AiTraceService traces;
  // Días que se conservan las trazas del modo investigación. 0 (por defecto) = no se borran.
  // [POR VERIFICAR] el plazo de retención: el documento no lo define y no se inventa uno.
  private final int traceRetentionDays;

  public AiJobMaintenanceScheduler(
      AiJobQueue queue,
      AiGuideService guides,
      AiTraceService traces,
      @Value("${prisma.ai.trace.retention-days:0}") int traceRetentionDays) {
    this.queue = queue;
    this.guides = guides;
    this.traces = traces;
    this.traceRetentionDays = traceRetentionDays;
  }

  @Scheduled(cron = "${prisma.ai.jobs.maintenance-cron:0 15 3 * * *}")
  @SchedulerLock(name = "aiJobMaintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
  public void run() {
    try {
      queue.housekeeping();
      guides.expireOpenGuides();
      int purged = traces.purgeOlderThan(traceRetentionDays);
      if (purged > 0) {
        log.info("Trazas del modo investigación vencidas y borradas: {}", purged);
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo completar el mantenimiento de la IA asíncrona: {}", e.getMessage());
    }
  }
}
