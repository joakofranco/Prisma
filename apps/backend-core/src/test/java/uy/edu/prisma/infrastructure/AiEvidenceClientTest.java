package uy.edu.prisma.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * Todas las llamadas de {@link AiEvidenceClient} son best-effort (no deben propagar nunca una
 * excepción hacia el llamador) -- estos tests verifican tanto el camino feliz (mapeo de la
 * respuesta de backend-ai) como que un backend-ai caído/con error nunca hace fallar al método.
 */
class AiEvidenceClientTest {

  private static final String BASE_URL = "http://backend-ai.test";

  private MockRestServiceServer server;
  private AiEvidenceClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new AiEvidenceClient(builder.build());
  }

  @Test
  void ingestReturnsTrueWhenBackendAiReportsIndexed() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/ingest"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"indexed\":true,\"chunks\":3}", MediaType.APPLICATION_JSON));

    boolean result =
        client.ingest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "evidencia.pdf",
            "http://minio/evidencia.pdf");

    assertTrue(result);
    server.verify();
  }

  @Test
  void ingestAcceptsNullControlId() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/ingest"))
        .andRespond(withSuccess("{\"indexed\":true,\"chunks\":1}", MediaType.APPLICATION_JSON));

    boolean result =
        client.ingest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "a.pdf", "http://x");

    assertTrue(result);
  }

  @Test
  void ingestReturnsFalseWhenBackendAiIsDown() {
    server.expect(requestTo(BASE_URL + "/api/v1/evidence/ingest")).andRespond(withServerError());

    boolean result =
        client.ingest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            "evidencia.pdf",
            "http://minio/x");

    assertFalse(result);
  }

  @Test
  void citationsReturnsEmptyListWhenBackendAiFails() {
    server.expect(requestTo(BASE_URL + "/api/v1/evidence/citations")).andRespond(withServerError());

    List<EvidenceCitationDto> result =
        client.citations(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "backup policy");

    assertTrue(result.isEmpty());
  }

  @Test
  void citationsMapsResponseToDtos() {
    UUID evidenceId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/citations"))
        .andRespond(
            withSuccess(
                """
                {"citations":[{"evidence_id":"%s","file_name":"a.pdf","location":"p1","snippet":"...","score":0.9}]}\
                """
                    .formatted(evidenceId),
                MediaType.APPLICATION_JSON));

    List<EvidenceCitationDto> result =
        client.citations(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "q");

    assertEquals(1, result.size());
    assertEquals(evidenceId, result.get(0).evidenceId());
    assertEquals("a.pdf", result.get(0).fileName());
    assertEquals("p1", result.get(0).location());
    assertEquals(0.9, result.get(0).score());
  }

  @Test
  void citationsReturnsEmptyListWhenBodyHasNoCitations() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/citations"))
        .andRespond(withSuccess("{\"citations\":null}", MediaType.APPLICATION_JSON));

    List<EvidenceCitationDto> result =
        client.citations(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "q");

    assertTrue(result.isEmpty());
  }

  @Test
  void citationsHandlesInvalidEvidenceIdGracefully() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/citations"))
        .andRespond(
            withSuccess(
                "{\"citations\":[{\"evidence_id\":\"not-a-uuid\",\"file_name\":\"a.pdf\","
                    + "\"location\":\"p1\",\"snippet\":\"...\",\"score\":0.9}]}",
                MediaType.APPLICATION_JSON));

    List<EvidenceCitationDto> result =
        client.citations(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "q");

    assertNull(result.get(0).evidenceId());
  }

  @Test
  void deleteEvidenceSucceeds() {
    UUID orgId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/" + orgId + "/" + evidenceId))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());

    assertDoesNotThrow(() -> client.deleteEvidence(orgId, evidenceId));
    server.verify();
  }

  @Test
  void deleteEvidenceSwallowsErrors() {
    UUID orgId = UUID.randomUUID();
    UUID evidenceId = UUID.randomUUID();
    server
        .expect(requestTo(BASE_URL + "/api/v1/evidence/" + orgId + "/" + evidenceId))
        .andRespond(withServerError());

    assertDoesNotThrow(() -> client.deleteEvidence(orgId, evidenceId));
  }

  @Test
  void suggestRemediationMapsSummaryAndTips() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/rag/remediation-tips"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"summary\":\"Definir una política\",\"tips\":[\"Paso 1\",\"Paso 2\"]}",
                MediaType.APPLICATION_JSON));

    var result =
        client.suggestRemediation("PR.AC-1", "descripción", "Proteger", "Acceso", "ID", 0, 1);

    assertEquals("Definir una política", result.summary());
    assertEquals(List.of("Paso 1", "Paso 2"), result.tips());
    server.verify();
  }

  @Test
  void suggestRemediationReturnsFallbackOnServerError() {
    server
        .expect(requestTo(BASE_URL + "/api/v1/rag/remediation-tips"))
        .andRespond(withServerError());

    var result = client.suggestRemediation("PR.AC-1", "descripción", null, null, null, 0, 1);

    assertEquals("", result.summary());
    assertEquals(1, result.tips().size());
  }
}
