package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AiJobQueue;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.GuideResult;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * La cola de IA contra un Postgres real (migración V41): lo que importa acá lo hace el MOTOR de la
 * base y no se ve con mocks: {@code FOR UPDATE SKIP LOCKED} (seguro con varias réplicas), el índice
 * único de trabajos activos (idempotencia) y el mapeo JSONB de las entidades.
 *
 * <p>El trabajador está apagado ({@code prisma.ai.jobs.enabled=false}) para manejar la cola a mano;
 * el ciclo completo con trabajador va en {@link AiJobWorkerIT}.
 */
@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = {"prisma.ai.jobs.enabled=false", "prisma.ai.jobs.concurrency=2"})
class AiJobQueueIT {

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

  // Sin Keycloak levantado (solo Postgres): ver PrismaApplicationIT.
  @MockitoBean private KeycloakAdminClient keycloakAdmin;
  @MockitoBean private AiEvidenceClient aiClient;

  @Autowired private AiJobRepository repo;
  @Autowired private AiJobQueue queue;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager txManager;

  private UUID controlId;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM prisma.ai_generated_guide");
    jdbc.update("DELETE FROM prisma.ai_job");
    controlId = jdbc.queryForObject("SELECT id FROM prisma.catalog_controls LIMIT 1", UUID.class);
    assertNotNull(controlId, "la migración del catálogo siembra controles");
  }

  private AiJob queued(String key) {
    return repo.saveAndFlush(
        AiJob.builder()
            .type(AiJob.Type.GUIDE)
            .requestedBy(UUID.randomUUID())
            .dedupKey(key)
            .params(
                Map.of(
                    "controlId",
                    controlId.toString(),
                    "controlCode",
                    "X",
                    "currentLevel",
                    0,
                    "targetLevel",
                    2))
            .build());
  }

  @Test
  void twoConcurrentClaimersNeverTakeTheSameJob() throws Exception {
    for (int i = 0; i < 20; i++) {
      queued("job-" + i);
    }
    TransactionTemplate tx = new TransactionTemplate(txManager);
    CountDownLatch firstHasLocked = new CountDownLatch(1);
    CountDownLatch secondDone = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // Réplica A: toma 10 filas y mantiene la transacción abierta (locks tomados).
      Future<Set<UUID>> a =
          pool.submit(
              () ->
                  tx.execute(
                      status -> {
                        Set<UUID> ids = ids(repo.lockQueued(10));
                        firstHasLocked.countDown();
                        await(secondDone);
                        return ids;
                      }));
      assertTrue(firstHasLocked.await(30, TimeUnit.SECONDS));
      // Réplica B: con las de A bloqueadas, SKIP LOCKED le da OTRAS, sin esperar ni repetir.
      Future<Set<UUID>> b =
          pool.submit(
              () ->
                  tx.execute(
                      status -> {
                        Set<UUID> ids = ids(repo.lockQueued(10));
                        secondDone.countDown();
                        return ids;
                      }));
      Set<UUID> fromB = b.get(30, TimeUnit.SECONDS);
      Set<UUID> fromA = a.get(30, TimeUnit.SECONDS);

      assertEquals(10, fromA.size());
      assertEquals(10, fromB.size());
      Set<UUID> all = new HashSet<>(fromA);
      all.addAll(fromB);
      assertEquals(20, all.size(), "ningún trabajo reclamado por las dos réplicas");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void claimRespectsTheGlobalConcurrencyLimit() {
    for (int i = 0; i < 5; i++) {
      queued("limit-" + i);
    }

    List<AiJob> first = queue.claimNext();
    List<AiJob> second = queue.claimNext();

    assertEquals(2, first.size(), "tope global = 2");
    assertTrue(second.isEmpty(), "con 2 corriendo no se reclama nada más");
    assertEquals(2, repo.countByStatus(Status.RUNNING));
    assertEquals(3, repo.countByStatus(Status.QUEUED));
    first.forEach(job -> assertEquals(1, job.getAttempts()));

    // Al terminar uno, se libera un lugar.
    queue.complete(
        first.get(0).getId(), new GuideResult("Resumen", List.of("A"), "llama3:8b", Map.of()), 100);
    assertEquals(1, queue.claimNext().size());
  }

  @Test
  void interactiveJobsGoBeforeBatchJobs() {
    AiJob batch = queued("batch");
    batch.setPriority(AiJob.PRIORITY_BATCH);
    repo.saveAndFlush(batch);
    AiJob interactive = queued("interactive");

    List<AiJob> claimed = queue.claimNext();

    assertEquals(interactive.getId(), claimed.get(0).getId());
  }

  @Test
  void onlyOneActiveJobPerRequestIsAllowedButAFinishedOneDoesNotBlockANewOne() {
    AiJob first = queued("same-request");

    assertThrows(DataIntegrityViolationException.class, () -> queued("same-request"));

    first.setStatus(Status.SUCCEEDED);
    repo.saveAndFlush(first);
    assertNotNull(queued("same-request").getId());
  }

  @Test
  void aJobWhoseReplicaDiedIsRequeuedAndThenFailsWhenAttemptsRunOut() {
    AiJob job = queued("stale");
    List<AiJob> claimed = queue.claimNext();
    assertEquals(job.getId(), claimed.get(0).getId());
    // La réplica murió: el reclamo ya venció.
    jdbc.update(
        "UPDATE prisma.ai_job SET locked_until = ?, next_attempt_at = now() WHERE id = ?",
        OffsetDateTime.now().minusMinutes(1),
        job.getId());

    assertEquals(1, queue.recoverStale());
    AiJob requeued = repo.findById(job.getId()).orElseThrow();
    assertEquals(Status.QUEUED, requeued.getStatus());
    assertEquals(1, requeued.getAttempts());

    // Segundo intento: tampoco termina.
    jdbc.update("UPDATE prisma.ai_job SET next_attempt_at = now() WHERE id = ?", job.getId());
    queue.claimNext();
    jdbc.update(
        "UPDATE prisma.ai_job SET locked_until = ? WHERE id = ?",
        OffsetDateTime.now().minusMinutes(1),
        job.getId());
    queue.recoverStale();

    AiJob failed = repo.findById(job.getId()).orElseThrow();
    assertEquals(Status.FAILED, failed.getStatus());
    assertEquals("WORKER_LOST", failed.getErrorCode());
  }

  @Test
  void aSucceededJobStoresTheGuideAndItsMetricsInJsonb() {
    AiJob job = queued("complete");
    queue.claimNext();

    queue.complete(
        job.getId(),
        new GuideResult(
            "Resumen", List.of("Paso 1", "Paso 2"), "llama3:8b", Map.of("eval_count", 42)),
        1234);

    AiJob done = repo.findById(job.getId()).orElseThrow();
    assertEquals(Status.SUCCEEDED, done.getStatus());
    assertEquals(1234L, done.getRunMs());
    assertEquals(42, ((Number) done.getMetrics().get("eval_count")).intValue());
    assertNotNull(done.getExpiresAt());
    Map<String, Object> guide =
        jdbc.queryForMap(
            "SELECT status, summary, tips::text AS tips, model, organization_id FROM"
                + " prisma.ai_generated_guide WHERE id = ?",
            done.getResultRef());
    assertEquals("GENERATED", guide.get("status"));
    assertEquals("Resumen", guide.get("summary"));
    assertTrue(guide.get("tips").toString().contains("Paso 2"));
    assertEquals("llama3:8b", guide.get("model"));
  }

  @Test
  void housekeepingExpiresOldResults() {
    AiJob job = queued("expire");
    queue.claimNext();
    queue.complete(job.getId(), new GuideResult("R", List.of(), "m", Map.of()), 10);
    jdbc.update(
        "UPDATE prisma.ai_job SET expires_at = now() - interval '1 day' WHERE id = ?", job.getId());

    queue.housekeeping();

    assertEquals(Status.EXPIRED, repo.findById(job.getId()).orElseThrow().getStatus());
  }

  private static Set<UUID> ids(List<AiJob> jobs) {
    Set<UUID> ids = new HashSet<>();
    jobs.forEach(j -> ids.add(j.getId()));
    return ids;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
