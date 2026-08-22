package uy.edu.prisma.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.RemediationTipsDto;

/**
 * Cliente hacia backend-ai: el pipeline RAG de evidencias (app.api.v1.evidence en Python) y,
 * desde acá, también las sugerencias de remediación de brechas (app.api.v1.rag).
 *
 * <p>Ninguna llamada acá lanza hacia el llamador: la ingesta es best-effort (una evidencia ya quedó
 * guardada en MinIO/Postgres aunque backend-ai esté caído), y tanto las citas como las
 * sugerencias de remediación son asistentes opcionales (si fallan, el usuario simplemente no ve
 * esa ayuda puntual, pero puede seguir trabajando).
 */
@Component
public class AiEvidenceClient {

  private static final Logger log = LoggerFactory.getLogger(AiEvidenceClient.class);

  private final RestClient restClient;

  public AiEvidenceClient(RestClient aiRestClient) {
    this.restClient = aiRestClient;
  }

  /** Devuelve true si backend-ai reporta al menos un fragmento indexado. */
  public boolean ingest(
      UUID organizationId,
      UUID evaluationId,
      UUID evidenceId,
      UUID controlId,
      String fileName,
      String fileUrl) {
    try {
      IngestResponse response =
          restClient
              .post()
              .uri("/api/v1/evidence/ingest")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new IngestRequest(
                      organizationId.toString(),
                      evaluationId.toString(),
                      evidenceId.toString(),
                      controlId != null ? controlId.toString() : null,
                      fileName,
                      fileUrl))
              .retrieve()
              .body(IngestResponse.class);
      return response != null && response.indexed();
    } catch (Exception e) {
      log.warn(
          "No se pudo indexar la evidencia {} en backend-ai (se sigue igual): {}",
          evidenceId,
          e.getMessage());
      return false;
    }
  }

  /** Recuperación pura de fragmentos relevantes; nunca un veredicto. Vacía si algo falla. */
  public List<EvidenceCitationDto> citations(
      UUID organizationId, UUID evaluationId, UUID controlId, String query) {
    try {
      CitationsResponse response =
          restClient
              .post()
              .uri("/api/v1/evidence/citations")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new CitationsRequest(
                      organizationId.toString(),
                      evaluationId.toString(),
                      controlId != null ? controlId.toString() : null,
                      query,
                      null))
              .retrieve()
              .body(CitationsResponse.class);
      if (response == null || response.citations() == null) {
        return List.of();
      }
      return response.citations().stream().map(this::toDto).toList();
    } catch (Exception e) {
      log.warn(
          "No se pudieron obtener citas de evidencia para el control {}: {}",
          controlId,
          e.getMessage());
      return List.of();
    }
  }

  // Fallback fijo: mejor un único tip explicando el problema que dejar al usuario sin ningún
  // texto (o peor, propagar la excepción y tumbar toda la pantalla de sugerencias por una sola
  // que falló).
  private static final RemediationTipsDto FALLBACK_TIPS =
      new RemediationTipsDto(
          "",
          List.of(
              "No se pudo generar una sugerencia automática en este momento -- intentá de nuevo"
                  + " más tarde."));

  /**
   * Pide una guía práctica (resumen + pasos concretos) para cerrar una brecha puntual. On-demand,
   * un control a la vez -- nunca se llama para toda una lista de sugerencias junta, sería lento y
   * cargaría a Ollama con decenas de generaciones que el usuario ni llegó a pedir.
   */
  public RemediationTipsDto suggestRemediation(
      String controlCode,
      String description,
      String functionName,
      String categoryName,
      String subcategoryName,
      int currentLevel,
      int targetLevel) {
    try {
      RemediationTipsResponse response =
          restClient
              .post()
              .uri("/api/v1/rag/remediation-tips")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new RemediationTipsRequest(
                      controlCode,
                      description,
                      functionName,
                      categoryName,
                      subcategoryName,
                      currentLevel,
                      targetLevel))
              .retrieve()
              .body(RemediationTipsResponse.class);
      if (response == null) {
        return FALLBACK_TIPS;
      }
      return new RemediationTipsDto(
          response.summary(), response.tips() != null ? response.tips() : List.of());
    } catch (Exception e) {
      log.warn(
          "No se pudo generar una sugerencia de remediación para {}: {}",
          controlCode,
          e.getMessage());
      return FALLBACK_TIPS;
    }
  }

  /** Borra los fragmentos indexados de una evidencia (best-effort, p.ej. al eliminarla). */
  public void deleteEvidence(UUID organizationId, UUID evidenceId) {
    try {
      restClient
          .delete()
          .uri("/api/v1/evidence/{organizationId}/{evidenceId}", organizationId, evidenceId)
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn(
          "No se pudo borrar la indexación de la evidencia {}: {}", evidenceId, e.getMessage());
    }
  }

  private EvidenceCitationDto toDto(Citation c) {
    UUID evidenceId;
    try {
      evidenceId = c.evidenceId() != null ? UUID.fromString(c.evidenceId()) : null;
    } catch (IllegalArgumentException e) {
      evidenceId = null;
    }
    return new EvidenceCitationDto(evidenceId, c.fileName(), c.location(), c.snippet(), c.score());
  }

  // El JSON de estos records tiene que matchear los BaseModel de Pydantic en backend-ai
  // (app/api/v1/evidence.py), que usan snake_case -- de ahí los @JsonProperty explícitos.
  private record IngestRequest(
      @JsonProperty("organization_id") String organizationId,
      @JsonProperty("evaluation_id") String evaluationId,
      @JsonProperty("evidence_id") String evidenceId,
      @JsonProperty("control_id") String controlId,
      @JsonProperty("file_name") String fileName,
      @JsonProperty("file_url") String fileUrl) {}

  private record IngestResponse(boolean indexed, int chunks) {}

  private record CitationsRequest(
      @JsonProperty("organization_id") String organizationId,
      @JsonProperty("evaluation_id") String evaluationId,
      @JsonProperty("control_id") String controlId,
      String query,
      @JsonProperty("top_k") Integer topK) {}

  private record CitationsResponse(List<Citation> citations) {}

  // Igual que arriba: JSON en snake_case para matchear el BaseModel de Pydantic
  // (app/api/v1/rag.py RemediationTipsRequest/Response).
  private record RemediationTipsRequest(
      @JsonProperty("control_code") String controlCode,
      @JsonProperty("control_description") String controlDescription,
      @JsonProperty("function_name") String functionName,
      @JsonProperty("category_name") String categoryName,
      @JsonProperty("subcategory_name") String subcategoryName,
      @JsonProperty("current_level") int currentLevel,
      @JsonProperty("target_level") int targetLevel) {}

  private record RemediationTipsResponse(String summary, List<String> tips) {}

  private record Citation(
      @JsonProperty("evidence_id") String evidenceId,
      @JsonProperty("file_name") String fileName,
      String location,
      String snippet,
      double score) {}
}
