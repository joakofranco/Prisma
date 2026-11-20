package uy.edu.prisma.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Cola de trabajos de IA ({@code prisma.ai.jobs.*}). Todo tiene valor por defecto.
 *
 * <ul>
 *   <li>{@code enabled}: enciende el trabajador (no la API). Por defecto sí.
 *   <li>{@code concurrency}: tope GLOBAL de trabajos en ejecución contra Ollama, sumando todas las
 *       réplicas (2: más pedidos simultáneos casi no mejoran el rendimiento total).
 *   <li>{@code maxPendingPerOrg}: trabajos activos (en cola o corriendo) por organización.
 *   <li>{@code resultTtlDays}: cuánto se conserva un resultado antes de marcarlo vencido.
 *   <li>{@code maxAttempts}: intentos por trabajo (reintenta ante 503 o timeout).
 *   <li>{@code retryBackoffSeconds}: espera base entre intentos; se duplica en cada reintento.
 *   <li>{@code pollIntervalMs}: cada cuánto mira la cola cada réplica.
 *   <li>{@code guideFeedbackDays}: a los cuántos días una guía mostrada y no usada cuenta como
 *       rechazada (D11).
 *   <li>{@code maxBatchSize}: controles por lote de guías. {@code maxPendingBatchPerOrg}: trabajos
 *       de lote activos por organización (aparte del tope de los pedidos sueltos).
 * </ul>
 */
@ConfigurationProperties(prefix = "prisma.ai.jobs")
public record AiJobProperties(
    Boolean enabled,
    Integer concurrency,
    Integer maxPendingPerOrg,
    Integer resultTtlDays,
    Integer maxAttempts,
    Integer retryBackoffSeconds,
    Long pollIntervalMs,
    Integer guideFeedbackDays,
    Integer maxBatchSize,
    Integer maxPendingBatchPerOrg) {

  // Con más de un constructor Spring Boot necesita saber cuál usar (ver AiConfig.AiProperties).
  @ConstructorBinding
  public AiJobProperties {
    enabled = enabled != null ? enabled : Boolean.TRUE;
    concurrency = concurrency != null && concurrency > 0 ? concurrency : 2;
    maxPendingPerOrg = maxPendingPerOrg != null && maxPendingPerOrg > 0 ? maxPendingPerOrg : 20;
    resultTtlDays = resultTtlDays != null && resultTtlDays > 0 ? resultTtlDays : 30;
    maxAttempts = maxAttempts != null && maxAttempts > 0 ? maxAttempts : 2;
    retryBackoffSeconds =
        retryBackoffSeconds != null && retryBackoffSeconds >= 0 ? retryBackoffSeconds : 30;
    pollIntervalMs = pollIntervalMs != null && pollIntervalMs > 0 ? pollIntervalMs : 2000L;
    guideFeedbackDays = guideFeedbackDays != null && guideFeedbackDays > 0 ? guideFeedbackDays : 30;
    maxBatchSize = maxBatchSize != null && maxBatchSize > 0 ? maxBatchSize : 20;
    maxPendingBatchPerOrg =
        maxPendingBatchPerOrg != null && maxPendingBatchPerOrg > 0 ? maxPendingBatchPerOrg : 60;
  }

  /** Sin los topes de lote (se aplican los valores por defecto). */
  public AiJobProperties(
      Boolean enabled,
      Integer concurrency,
      Integer maxPendingPerOrg,
      Integer resultTtlDays,
      Integer maxAttempts,
      Integer retryBackoffSeconds,
      Long pollIntervalMs,
      Integer guideFeedbackDays) {
    this(
        enabled,
        concurrency,
        maxPendingPerOrg,
        resultTtlDays,
        maxAttempts,
        retryBackoffSeconds,
        pollIntervalMs,
        guideFeedbackDays,
        null,
        null);
  }

  public AiJobProperties() {
    this(null, null, null, null, null, null, null, null, null, null);
  }
}
