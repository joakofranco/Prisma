package uy.edu.prisma.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.repository.AiJobRepository;

/**
 * Métricas de la cola de IA (WP3), expuestas por /actuator/prometheus: espera en cola, tiempo de
 * ejecución, resultado, reintentos y tokens/tiempos que informa Ollama.
 *
 * <p>Nombres (Prometheus): {@code prisma_ai_job_queue_wait_seconds}, {@code
 * prisma_ai_job_run_seconds}, {@code prisma_ai_jobs_finished_total{outcome}}, {@code
 * prisma_ai_job_retries_total}, {@code prisma_ai_ollama_tokens_total{kind}}, {@code
 * prisma_ai_ollama_seconds_total{phase}} y {@code prisma_ai_jobs{status}} (en cola / corriendo).
 */
@Component
public class AiJobMetrics {

  private static final Logger log = LoggerFactory.getLogger(AiJobMetrics.class);

  private final MeterRegistry registry;
  private final Timer queueWait;
  private final Timer run;
  private final Counter retries;

  public AiJobMetrics(MeterRegistry registry, AiJobRepository repo) {
    this.registry = registry;
    this.queueWait =
        Timer.builder("prisma.ai.job.queue.wait")
            .description("Tiempo que un trabajo de IA espera en cola antes de correr")
            .publishPercentileHistogram()
            .register(registry);
    this.run =
        Timer.builder("prisma.ai.job.run")
            .description("Tiempo de ejecución de un trabajo de IA contra backend-ai")
            .publishPercentileHistogram()
            .register(registry);
    this.retries =
        Counter.builder("prisma.ai.job.retries")
            .description("Trabajos de IA reencolados para reintentar")
            .register(registry);
    for (Status status : new Status[] {Status.QUEUED, Status.RUNNING}) {
      Gauge.builder("prisma.ai.jobs", () -> count(repo, status))
          .description("Trabajos de IA por estado")
          .tag("status", status.name().toLowerCase())
          .register(registry);
    }
  }

  private static double count(AiJobRepository repo, Status status) {
    try {
      return repo.countByStatus(status);
    } catch (RuntimeException e) {
      log.debug("No se pudo contar trabajos de IA {}: {}", status, e.getMessage());
      return Double.NaN;
    }
  }

  public void recordQueueWait(long millis) {
    queueWait.record(Duration.ofMillis(Math.max(0, millis)));
  }

  public void recordRun(long millis) {
    run.record(Duration.ofMillis(Math.max(0, millis)));
  }

  public void recordRetry() {
    retries.increment();
  }

  /**
   * @param outcome SUCCEEDED | FAILED | CANCELLED
   */
  public void recordFinished(String type, String outcome, String errorCode) {
    Counter.builder("prisma.ai.jobs.finished")
        .description("Trabajos de IA terminados, por resultado")
        .tag("type", type)
        .tag("outcome", outcome)
        .tag("error", errorCode == null ? "none" : errorCode)
        .register(registry)
        .increment();
  }

  /** Tokens y tiempos (ns) de Ollama tal como los devuelve backend-ai en {@code metrics}. */
  public void recordOllama(Map<String, Object> metrics) {
    if (metrics == null) {
      return;
    }
    token("prompt", metrics.get("prompt_eval_count"));
    token("completion", metrics.get("eval_count"));
    seconds("prompt_eval", metrics.get("prompt_eval_duration"));
    seconds("generation", metrics.get("eval_duration"));
    seconds("load", metrics.get("load_duration"));
  }

  private void token(String kind, Object value) {
    if (value instanceof Number n) {
      Counter.builder("prisma.ai.ollama.tokens")
          .tag("kind", kind)
          .register(registry)
          .increment(n.doubleValue());
    }
  }

  private void seconds(String phase, Object nanos) {
    if (nanos instanceof Number n) {
      Counter.builder("prisma.ai.ollama.seconds")
          .tag("phase", phase)
          .register(registry)
          .increment(n.doubleValue() / 1_000_000_000d);
    }
  }
}
