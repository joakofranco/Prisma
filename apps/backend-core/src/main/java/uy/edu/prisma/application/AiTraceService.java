package uy.edu.prisma.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AiTrace;
import uy.edu.prisma.domain.entity.AiTrace.Kind;
import uy.edu.prisma.domain.entity.AiTraceLink;
import uy.edu.prisma.domain.entity.AiTraceLink.ResourceType;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiTraceLinkRepository;
import uy.edu.prisma.domain.repository.AiTraceRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ControlSuggestion;
import uy.edu.prisma.web.dto.Dto.AiTraceDto;

/**
 * Trazas del modo investigación (C8). Todo lo que escribe este servicio depende de que la
 * organización tenga el modo encendido ({@link #isEnabled}): con el modo apagado NO se registra
 * nada (D2). La traza no lleva organización ni usuario; {@code ai_trace_link} dice a qué recurso
 * corresponde. En tareas con evidencia solo se guardan ids y puntajes (D15).
 *
 * <p>Registrar una traza es best-effort: si falla no puede tumbar la función de la que sale.
 */
@Service
public class AiTraceService {

  private static final Logger log = LoggerFactory.getLogger(AiTraceService.class);

  private final AiTraceRepository traceRepo;
  private final AiTraceLinkRepository linkRepo;
  private final OrganizationRepository orgRepo;
  private final AiEvidenceClient aiClient;
  private final CurrentUserService currentUser;
  private final ObjectMapper mapper;

  public AiTraceService(
      AiTraceRepository traceRepo,
      AiTraceLinkRepository linkRepo,
      OrganizationRepository orgRepo,
      AiEvidenceClient aiClient,
      CurrentUserService currentUser,
      ObjectMapper mapper) {
    this.traceRepo = traceRepo;
    this.linkRepo = linkRepo;
    this.orgRepo = orgRepo;
    this.aiClient = aiClient;
    this.currentUser = currentUser;
    this.mapper = mapper;
  }

  /** ¿La organización tiene el modo investigación encendido? Sin organización: nunca. */
  @Transactional(readOnly = true)
  public boolean isEnabled(UUID organizationId) {
    return organizationId != null
        && orgRepo.findById(organizationId).map(o -> o.isResearchMode()).orElse(false);
  }

  /** Semilla aleatoria por pedido (se envía al modelo y se guarda en la traza). */
  public long newSeed() {
    return ThreadLocalRandom.current().nextLong(0, Integer.MAX_VALUE);
  }

  /** Guarda la traza de una guía (viene armada por backend-ai) y devuelve su id. */
  @Transactional
  public UUID recordGuide(Map<String, Object> trace, Long seed) {
    if (trace == null || trace.isEmpty()) {
      return null;
    }
    AiTrace saved =
        traceRepo.save(
            AiTrace.builder()
                .kind(Kind.GUIDE)
                .seed(seed != null ? seed : asLong(trace.get("seed")))
                .config(asMap(trace.get("config")))
                .prompt(asString(trace.get("prompt")))
                .response(asString(trace.get("response")))
                .thinking(asString(trace.get("thinking")))
                .retrieval(asList(trace.get("retrieval")))
                .metrics(asMap(trace.get("metrics")))
                .build());
    return saved.getId();
  }

  /**
   * Traza de una búsqueda de citas: solo ids y puntajes de los fragmentos (D15), enlazada a cada
   * cita guardada. No lanza.
   */
  @Transactional
  public void recordCitations(UUID organizationId, List<EvidenceCitationResult> results) {
    if (!isEnabled(organizationId) || results == null || results.isEmpty()) {
      return;
    }
    try {
      List<Map<String, Object>> retrieval = new ArrayList<>();
      for (EvidenceCitationResult r : results) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("fragmentId", r.getFragmentId());
        item.put("evidenceId", r.getEvidenceId());
        item.put("score", r.getScore());
        item.put("unassigned", r.isUnassigned());
        retrieval.add(item);
      }
      AiTrace trace =
          traceRepo.save(
              AiTrace.builder()
                  .kind(Kind.CITATIONS)
                  .config(aiClient.traceConfig())
                  .retrieval(retrieval)
                  .build());
      for (EvidenceCitationResult r : results) {
        link(trace.getId(), ResourceType.CITATION, r.getId(), organizationId);
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo guardar la traza de las citas: {}", e.getMessage());
    }
  }

  /** Traza de la preclasificación de una evidencia global: ids de control y puntajes. */
  @Transactional
  public void recordClassification(
      UUID organizationId, UUID evidenceId, List<ControlSuggestion> suggestions) {
    if (!isEnabled(organizationId) || suggestions == null) {
      return;
    }
    try {
      List<Map<String, Object>> retrieval = new ArrayList<>();
      for (ControlSuggestion s : suggestions) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("controlId", s.controlId());
        item.put("score", s.score());
        retrieval.add(item);
      }
      AiTrace trace =
          traceRepo.save(
              AiTrace.builder()
                  .kind(Kind.CLASSIFY)
                  .config(aiClient.traceConfig())
                  .retrieval(retrieval)
                  .build());
      link(trace.getId(), ResourceType.CLASSIFICATION, evidenceId, organizationId);
    } catch (RuntimeException e) {
      log.warn("No se pudo guardar la traza de la clasificación: {}", e.getMessage());
    }
  }

  /** Enlaza una traza con un recurso (idempotente). {@code traceId} nulo no hace nada. */
  @Transactional
  public void link(UUID traceId, ResourceType type, UUID resourceId, UUID organizationId) {
    if (traceId == null || resourceId == null) {
      return;
    }
    if (linkRepo.existsByTraceIdAndResourceTypeAndResourceId(traceId, type, resourceId)) {
      return;
    }
    linkRepo.save(
        AiTraceLink.builder()
            .traceId(traceId)
            .resourceType(type)
            .resourceId(resourceId)
            .organizationId(organizationId)
            .build());
  }

  /** Copia al recurso nuevo (p. ej. la observación) los enlaces de trazas del recurso origen. */
  @Transactional
  public void linkLike(
      ResourceType fromType, UUID fromId, ResourceType toType, UUID toId, UUID organizationId) {
    if (fromId == null || toId == null) {
      return;
    }
    for (AiTraceLink l :
        linkRepo.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(fromType, fromId)) {
      link(l.getTraceId(), toType, toId, organizationId);
    }
  }

  // ------------------------------------------------------------------ consulta

  /** Traza por id. Solo PRISMA_ADMIN (lo exige el controlador) y con acceso a su organización. */
  @Transactional(readOnly = true)
  public AiTraceDto get(UUID traceId) {
    AiTrace trace =
        traceRepo
            .findById(traceId)
            .orElseThrow(() -> new ResourceNotFoundException("Traza de IA", traceId));
    assertLinkAccess(linkRepo.findByTraceId(traceId));
    return toDto(trace);
  }

  /** La traza más reciente del recurso, o 404 si no hay (modo apagado cuando se generó). */
  @Transactional(readOnly = true)
  public AiTraceDto forResource(ResourceType type, UUID resourceId) {
    List<AiTraceLink> links =
        linkRepo.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(type, resourceId);
    if (links.isEmpty()) {
      throw new ResourceNotFoundException("Traza de IA", resourceId);
    }
    assertLinkAccess(links.subList(0, 1));
    UUID traceId = links.get(0).getTraceId();
    return toDto(
        traceRepo
            .findById(traceId)
            .orElseThrow(() -> new ResourceNotFoundException("Traza de IA", traceId)));
  }

  private void assertLinkAccess(List<AiTraceLink> links) {
    for (AiTraceLink l : links) {
      if (l.getOrganizationId() != null) {
        currentUser.assertOrganizationAccess(l.getOrganizationId());
        return;
      }
    }
  }

  // ------------------------------------------------------------------ exportación y retención

  /**
   * Exporta las trazas como JSONL (una por línea) desde {@code ai_trace} SOLAMENTE: no toca los
   * enlaces, así que el archivo no dice de qué organización ni usuario sale cada una. Devuelve
   * cuántas escribió.
   */
  @Transactional(readOnly = true)
  public int exportJsonl(Writer out) throws IOException {
    int n = 0;
    for (AiTrace t : traceRepo.findAllByOrderByCreatedAtAsc()) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("id", t.getId());
      line.put("kind", t.getKind().name());
      line.put("createdAt", t.getCreatedAt());
      line.put("seed", t.getSeed());
      line.put("config", t.getConfig());
      line.put("prompt", t.getPrompt());
      line.put("response", t.getResponse());
      line.put("thinking", t.getThinking());
      line.put("retrieval", t.getRetrieval());
      line.put("metrics", t.getMetrics());
      out.write(mapper.writeValueAsString(line));
      out.write('\n');
      n++;
    }
    out.flush();
    return n;
  }

  /** Borra las trazas con más de {@code days} días. {@code days <= 0} = no se borra nunca. */
  @Transactional
  public int purgeOlderThan(int days) {
    if (days <= 0) {
      return 0;
    }
    return traceRepo.deleteOlderThan(OffsetDateTime.now().minusDays(days));
  }

  // ------------------------------------------------------------------ helpers

  static AiTraceDto toDto(AiTrace t) {
    return new AiTraceDto(
        t.getId(),
        t.getKind().name(),
        t.getSeed(),
        t.getConfig(),
        t.getPrompt(),
        t.getResponse(),
        t.getThinking(),
        t.getRetrieval(),
        t.getMetrics(),
        t.getCreatedAt());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return o instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object o) {
    return o instanceof List<?> l
        ? new ArrayList<>((List<Map<String, Object>>) l)
        : new ArrayList<>();
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static Long asLong(Object o) {
    return o instanceof Number n ? n.longValue() : null;
  }
}
