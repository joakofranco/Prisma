package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AiIndexState;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.EvidenceCitationResult.Status;
import uy.edu.prisma.domain.repository.AiIndexStateRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceCitationResultRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;

/**
 * Reindexación al cambiar el modelo de embedding de backend-ai.
 *
 * <p>backend-ai guarda cada colección con el modelo en el nombre ({@code evidence-<org>__bge-m3}),
 * así que al cambiar de modelo la colección nueva arranca vacía. Acá se detecta el cambio
 * comparando el modelo configurado en backend-ai con el último con que se indexó ({@code
 * ai_index_state}) y, si difieren:
 *
 * <ol>
 *   <li>se descartan los resultados de citas guardados (sus puntajes ya no valen): los no decididos
 *       se borran y los decididos se marcan viejos (se rehacen conservando la decisión);
 *   <li>se encola un trabajo {@code EVIDENCE_INDEX} por cada evidencia (la misma cola, prioridad y
 *       estados «en cola / indexando» que la subida; se ve en pantalla como indexando);
 *   <li>se encola el precálculo de citas de las evaluaciones en auditoría (prioridad de lote: corre
 *       después de las indexaciones);
 *   <li>se guarda el modelo nuevo.
 * </ol>
 *
 * Todo en una transacción: si algo falla no queda a medias y el próximo chequeo lo reintenta. Si se
 * vuelve al modelo anterior el cambio también se detecta y se reindexa de nuevo (las colecciones
 * del modelo anterior siguen en backend-ai, pero pueden estar desactualizadas).
 */
@Service
public class EmbeddingReindexService {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexService.class);

  private final AiEvidenceClient aiClient;
  private final AiIndexStateRepository stateRepo;
  private final EvidenceRepository evidenceRepo;
  private final EvaluationRepository evaluationRepo;
  private final EvidenceCitationResultRepository citationRepo;
  private final EvidenceIndexService indexService;
  private final CitationPrecomputeService precomputeService;

  public EmbeddingReindexService(
      AiEvidenceClient aiClient,
      AiIndexStateRepository stateRepo,
      EvidenceRepository evidenceRepo,
      EvaluationRepository evaluationRepo,
      EvidenceCitationResultRepository citationRepo,
      EvidenceIndexService indexService,
      CitationPrecomputeService precomputeService) {
    this.aiClient = aiClient;
    this.stateRepo = stateRepo;
    this.evidenceRepo = evidenceRepo;
    this.evaluationRepo = evaluationRepo;
    this.citationRepo = citationRepo;
    this.indexService = indexService;
    this.precomputeService = precomputeService;
  }

  /** Resultado de un chequeo. */
  public record Result(boolean changed, String model, int evidences) {
    static Result unchanged(String model) {
      return new Result(false, model, 0);
    }
  }

  /**
   * Compara el modelo de backend-ai con el indexado y reindexa si cambió. Si backend-ai no responde
   * no hace nada (se reintenta en el próximo chequeo).
   */
  @Transactional
  public Optional<Result> check() {
    Optional<String> current = aiClient.embeddingModel().map(EmbeddingReindexService::normalize);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    String model = current.get();
    Optional<AiIndexState> state = stateRepo.findById(AiIndexState.EMBEDDING_MODEL);
    if (state.isPresent() && state.get().getValue().equals(model)) {
      return Optional.of(Result.unchanged(model));
    }
    String previous = state.map(AiIndexState::getValue).orElse("(sin registro)");
    int evidences = reindexAll();
    AiIndexState saved =
        state.orElseGet(() -> AiIndexState.builder().key(AiIndexState.EMBEDDING_MODEL).build());
    saved.setValue(model);
    saved.setUpdatedAt(OffsetDateTime.now());
    stateRepo.save(saved);
    log.info(
        "Modelo de embedding {} -> {}: {} evidencias en cola para reindexar",
        previous,
        model,
        evidences);
    return Optional.of(new Result(true, model, evidences));
  }

  private int reindexAll() {
    // Los puntajes de un modelo no valen para otro: nada de lo guardado se muestra como vigente.
    int dropped = citationRepo.deleteAllByStatus(Status.PENDING);
    int stale = citationRepo.markAllDecidedStale(Status.PENDING);
    log.info("Citas guardadas: {} descartadas y {} marcadas para rehacer", dropped, stale);

    List<Evidence> evidences = evidenceRepo.findAll();
    for (Evidence evidence : evidences) {
      indexService.enqueue(evidence);
    }
    // Las evaluaciones que ya están en auditoría no pasan por la transición que encola el
    // precálculo: se lo encola acá (prioridad de lote, o sea después de las indexaciones).
    for (Evaluation evaluation : evaluationRepo.findAllByStatus(Evaluation.Status.IN_AUDIT)) {
      precomputeService.enqueue(evaluation.getId(), evaluation.getOrganization().getId());
    }
    return evidences.size();
  }

  /** {@code bge-m3} y {@code bge-m3:latest} son el mismo modelo. */
  static String normalize(String model) {
    String name = model.strip().toLowerCase(Locale.ROOT);
    return name.endsWith(":latest") ? name.substring(0, name.length() - ":latest".length()) : name;
  }
}
