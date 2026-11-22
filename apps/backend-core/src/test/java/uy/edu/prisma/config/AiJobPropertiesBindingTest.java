package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code prisma.ai.jobs.*} se enlaza como lo hace Spring Boot al arrancar (ver
 * AiPropertiesBindingTest).
 */
class AiJobPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @EnableConfigurationProperties(AiJobProperties.class)
  static class Binding {}

  @Test
  void sinConfigurarNadaAplicaLosValoresPorDefecto() {
    runner.run(
        ctx -> {
          AiJobProperties p = ctx.getBean(AiJobProperties.class);
          assertTrue(p.enabled());
          assertEquals(2, p.concurrency());
          assertEquals(20, p.maxPendingPerOrg());
          assertEquals(30, p.resultTtlDays());
          assertEquals(2, p.maxAttempts());
          assertEquals(30, p.retryBackoffSeconds());
          assertEquals(2000L, p.pollIntervalMs());
          assertEquals(30, p.guideFeedbackDays());
        });
  }

  @Test
  void enlazaLosValoresConfigurados() {
    runner
        .withPropertyValues(
            "prisma.ai.jobs.enabled=false",
            "prisma.ai.jobs.concurrency=4",
            "prisma.ai.jobs.max-pending-per-org=5",
            "prisma.ai.jobs.result-ttl-days=7",
            "prisma.ai.jobs.max-attempts=3",
            "prisma.ai.jobs.retry-backoff-seconds=0",
            "prisma.ai.jobs.poll-interval-ms=250",
            "prisma.ai.jobs.guide-feedback-days=15")
        .run(
            ctx -> {
              AiJobProperties p = ctx.getBean(AiJobProperties.class);
              assertEquals(false, p.enabled());
              assertEquals(4, p.concurrency());
              assertEquals(5, p.maxPendingPerOrg());
              assertEquals(7, p.resultTtlDays());
              assertEquals(3, p.maxAttempts());
              assertEquals(0, p.retryBackoffSeconds());
              assertEquals(250L, p.pollIntervalMs());
              assertEquals(15, p.guideFeedbackDays());
            });
  }

  @Test
  void unValorInvalidoCaeAlPorDefectoEnLugarDeRomperLaCola() {
    runner
        .withPropertyValues("prisma.ai.jobs.concurrency=0", "prisma.ai.jobs.max-attempts=-1")
        .run(
            ctx -> {
              AiJobProperties p = ctx.getBean(AiJobProperties.class);
              assertEquals(2, p.concurrency());
              assertEquals(2, p.maxAttempts());
            });
  }
}
