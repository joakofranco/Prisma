package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import uy.edu.prisma.config.AiConfig.AiProperties;
import uy.edu.prisma.infrastructure.AiEvidenceClient;

/**
 * PRI-358 (RF-EVI-13): backend-ai colgado (acepta la conexión pero no responde) no puede dejar a
 * backend-core esperando para siempre. Usa un servidor HTTP real que demora la respuesta, así se
 * comprueba el timeout de verdad y no solo la configuración.
 */
class AiConfigTimeoutTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private volatile long delayMs;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          String path = exchange.getRequestURI().getPath();
          String body =
              path.endsWith("remediation-tips")
                  ? "{\"summary\":\"Resumen\",\"tips\":[\"Paso 1\"]}"
                  : "{\"chunks_indexed\":3,\"chunksIndexed\":3}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (IOException ignored) {
            // el cliente ya cortó por timeout
          } finally {
            exchange.close();
          }
        });
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AiEvidenceClient client(Duration read, Duration guideRead) {
    AiProperties props =
        new AiProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "",
            Duration.ofSeconds(1),
            read,
            guideRead);
    AiConfig config = new AiConfig();
    RestClient normal = config.aiRestClient(RestClient.builder(), props);
    RestClient guide = config.aiGuideRestClient(RestClient.builder(), props);
    return new AiEvidenceClient(normal, guide);
  }

  @Test
  void defaultsAreThreeSecondsSixtySecondsAndTwoMinutes() {
    AiProperties props = new AiProperties("http://x", "");

    assertEquals(Duration.ofSeconds(3), props.connectTimeout());
    assertEquals(Duration.ofSeconds(60), props.readTimeout());
    assertEquals(Duration.ofSeconds(120), props.guideReadTimeout());
  }

  @Test
  void aHungBackendAiMakesIngestGiveUpInsteadOfWaitingForever() {
    delayMs = 1_500;
    AiEvidenceClient client = client(Duration.ofMillis(300), Duration.ofSeconds(2));

    long start = System.nanoTime();
    boolean indexed =
        client.ingest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "doc.pdf",
            "http://rustfs/doc");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertFalse(indexed, "con la IA colgada la evidencia queda sin indexar");
    assertTrue(elapsedMs < 1_200, "tardó " + elapsedMs + " ms: el timeout no se aplicó");
    assertEquals(1, hits.get());
  }

  @Test
  void aHungBackendAiMakesCitationsFailAsUnavailableInsteadOfWaitingForever() {
    delayMs = 1_500;
    AiEvidenceClient client = client(Duration.ofMillis(300), Duration.ofSeconds(2));

    long start = System.nanoTime();
    assertThrows(
        uy.edu.prisma.domain.exception.AiUnavailableException.class,
        () ->
            client.citations(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "consulta"));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(elapsedMs < 1_200, "tardó " + elapsedMs + " ms: el timeout no se aplicó");
  }

  @Test
  void theGuideWaitsLongerThanTheOtherCalls() {
    // La guía genera con el LLM y tarda casi un minuto: tiene su propia espera, más larga.
    delayMs = 700;
    AiEvidenceClient client = client(Duration.ofMillis(300), Duration.ofSeconds(3));

    var tips = client.suggestRemediation("PR.AC-1", "descripción", null, null, null, 0, 1);

    assertTrue(tips.generated(), "la guía debería esperar los 700 ms y generarse");
    assertEquals("Resumen", tips.summary());
    assertEquals(List.of("Paso 1"), tips.tips());
  }

  @Test
  void aHungBackendAiMakesTheGuideFallBackWithoutBreakingTheScreen() {
    delayMs = 1_500;
    AiEvidenceClient client = client(Duration.ofMillis(300), Duration.ofMillis(400));

    long start = System.nanoTime();
    var tips = client.suggestRemediation("PR.AC-1", "descripción", null, null, null, 0, 1);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertFalse(tips.generated());
    assertEquals(1, tips.tips().size());
    assertTrue(elapsedMs < 1_200, "tardó " + elapsedMs + " ms: el timeout no se aplicó");
  }

  @Test
  void connectionRefusedAlsoFailsFast() throws IOException {
    int port = server.getAddress().getPort();
    server.stop(0);
    AiProperties props =
        new AiProperties(
            "http://127.0.0.1:" + port, "", Duration.ofSeconds(1), Duration.ofSeconds(5), null);
    AiConfig config = new AiConfig();
    AiEvidenceClient client =
        new AiEvidenceClient(
            config.aiRestClient(RestClient.builder(), props),
            config.aiGuideRestClient(RestClient.builder(), props));

    long start = System.nanoTime();
    boolean indexed =
        client.ingest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "d.pdf", "http://x");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertFalse(indexed);
    assertTrue(elapsedMs < 3_000);
  }
}
