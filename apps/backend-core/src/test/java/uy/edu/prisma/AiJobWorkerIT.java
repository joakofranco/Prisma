package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.exception.AiGuideException;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * Ciclo completo de la cola de IA con el trabajador real (sondeo + ShedLock + executor) contra
 * Postgres, con backend-ai simulado: 20 trabajos se procesan de a 2, ninguno se ejecuta dos veces
 * y, con backend-ai caído, se reintenta y termina en FAILED con un mensaje claro.
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {
      "prisma.ai.jobs.enabled=true",
      "prisma.ai.jobs.concurrency=2",
      "prisma.ai.jobs.poll-interval-ms=100",
      "prisma.ai.jobs.retry-backoff-seconds=0",
      "prisma.ai.jobs.max-attempts=2"
    })
class AiJobWorkerIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("prisma_test")
          .withUsername("test")
          .withPassword("test")
          .withInitScript("db/init-test.sql");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
  }

  @MockitoBean private KeycloakAdminClient keycloakAdmin;
  @MockitoBean private AiEvidenceClient aiClient;

  @Autowired private AiJobRepository repo;
  @Autowired private JdbcTemplate jdbc;

  private UUID controlId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM prisma.ai_generated_guide");
    jdbc.update("DELETE FROM prisma.ai_job");
    controlId = jdbc.queryForObject("SELECT id FROM prisma.catalog_controls LIMIT 1", UUID.class);
  }

  private AiJob enqueue(String code) {
    return repo.saveAndFlush(
        AiJob.builder()
            .type(AiJob.Type.GUIDE)
            .requestedBy(UUID.randomUUID())
            .dedupKey("worker-" + code)
            .maxAttempts(2)
            .params(
                Map.of(
                    "controlId",
                    controlId.toString(),
                    "controlCode",
                    code,
                    "description",
                    "d",
                    "currentLevel",
                    0,
                    "targetLevel",
                    2))
            .build());
  }

  private void awaitAllTerminal(int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 90_000;
    while (System.currentTimeMillis() < deadline) {
      long terminal =
          repo.countByStatus(Status.SUCCEEDED)
              + repo.countByStatus(Status.FAILED)
              + repo.countByStatus(Status.CANCELLED);
      if (terminal >= expected) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("los trabajos no terminaron a tiempo");
  }

  @Test
  void twentyQueuedJobsAreProcessedTwoAtATimeAndEachRunsExactlyOnce() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    when(aiClient.generateGuide(
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            anyInt(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              String code = inv.getArgument(0);
              calls.computeIfAbsent(code, k -> new AtomicInteger()).incrementAndGet();
              int now = running.incrementAndGet();
              maxRunning.accumulateAndGet(now, Math::max);
              try {
                Thread.sleep(250); // el LLM tarda
              } finally {
                running.decrementAndGet();
              }
              return new GuideResult("Resumen " + code, List.of("Paso"), "llama3:8b", Map.of());
            });

    for (int i = 0; i < 20; i++) {
      enqueue("C" + i);
    }
    awaitAllTerminal(20);

    assertEquals(20, repo.countByStatus(Status.SUCCEEDED));
    assertEquals(20, calls.size());
    calls.forEach(
        (code, n) -> assertEquals(1, n.get(), "el trabajo " + code + " corrió una sola vez"));
    assertTrue(maxRunning.get() <= 2, "nunca más de 2 a la vez, hubo " + maxRunning.get());
    assertEquals(2, maxRunning.get(), "y sí se aprovecha el paralelismo de 2");
    Integer guides =
        jdbc.queryForObject("SELECT count(*) FROM prisma.ai_generated_guide", Integer.class);
    assertEquals(20, guides);
    // Medición (WP3): cada trabajo guarda su espera y su tiempo de ejecución.
    repo.findAll()
        .forEach(
            job -> {
              assertNotNull(job.getQueueWaitMs());
              assertTrue(job.getRunMs() >= 250, "runMs=" + job.getRunMs());
            });
  }

  @Test
  void withBackendAiDownTheJobRetriesAndEndsFailedWithAClearMessage() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    when(aiClient.generateGuide(
            eq("DOWN"),
            anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            anyInt(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              attempts.incrementAndGet();
              throw new AiGuideException(
                  AiGuideException.AI_UNAVAILABLE,
                  "El asistente de IA no está disponible en este momento",
                  true,
                  null);
            });

    AiJob job = enqueue("DOWN");
    awaitAllTerminal(1);

    AiJob failed = repo.findById(job.getId()).orElseThrow();
    assertEquals(Status.FAILED, failed.getStatus());
    assertEquals(2, failed.getAttempts());
    assertEquals(2, attempts.get(), "reintentó una vez antes de rendirse");
    assertEquals("AI_UNAVAILABLE", failed.getErrorCode());
    assertEquals("El asistente de IA no está disponible en este momento", failed.getErrorMessage());
    assertNotNull(failed.getFinishedAt());
  }

  @Test
  void aNonRetryableFailureDoesNotRetry() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    when(aiClient.generateGuide(
            eq("BIG"),
            anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            anyInt(),
            anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              attempts.incrementAndGet();
              throw new AiGuideException(
                  AiGuideException.PROMPT_TOO_LARGE,
                  "El contenido es demasiado largo",
                  false,
                  null);
            });

    AiJob job = enqueue("BIG");
    awaitAllTerminal(1);

    AiJob failed = repo.findById(job.getId()).orElseThrow();
    assertEquals(Status.FAILED, failed.getStatus());
    assertEquals(1, attempts.get());
    assertEquals("PROMPT_TOO_LARGE", failed.getErrorCode());
  }
}
