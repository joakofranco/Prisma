package uy.edu.prisma.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Evidence.IndexStatus;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;

/**
 * Indexación de evidencia con estado y reintentos (C7). Antes era «best-effort y silenciosa»: la
 * subida esperaba a backend-ai y, si fallaba, quedaba {@code aiIndexed=false} sin aviso. Ahora cada
 * evidencia indexable es un trabajo {@code EVIDENCE_INDEX} de la misma cola que las guías y su
 * estado es visible: {@code QUEUED} → {@code INDEXING} → {@code INDEXED} | {@code ERROR}
 * (reintentable por el usuario), o {@code NOT_INDEXABLE} (ZIP, imágenes, en cuarentena, infectada).
 *
 * <p>Es un trabajo del sistema (sin usuario solicitante): no aparece en «Tareas de IA». Los
 * reintentos automáticos los hace la cola (espera creciente); si se agotan, la evidencia queda en
 * {@code ERROR} con el motivo.
 */
@Service
public class EvidenceIndexService {

  private static final Logger log = LoggerFactory.getLogger(EvidenceIndexService.class);

  /** Entre el interactivo (10) y el lote (0): indexar no debe esperar detrás de un lote. */
  static final int PRIORITY = 5;

  static final String INDEX_FAILED = "INDEX_FAILED";

  private static final Set<AiJob.Status> ACTIVE = Set.of(Status.QUEUED, Status.RUNNING);

  private final EvidenceRepository evidenceRepo;
  private final AiJobRepository jobRepo;
  private final AiJobProperties props;
  private final EvidenceCitationStore citationStore;
  private final EvidenceService evidenceService;
  private final EvidenceClassificationService classificationService;

  public EvidenceIndexService(
      EvidenceRepository evidenceRepo,
      AiJobRepository jobRepo,
      AiJobProperties props,
      EvidenceCitationStore citationStore,
      @Lazy EvidenceService evidenceService,
      EvidenceClassificationService classificationService) {
    this.evidenceRepo = evidenceRepo;
    this.jobRepo = jobRepo;
    this.props = props;
    this.citationStore = citationStore;
    this.evidenceService = evidenceService;
    this.classificationService = classificationService;
  }

  /**
   * Lo que el trabajador necesita para llamar a backend-ai (calculado dentro de una transacción).
   */
  public record IndexInput(
      UUID organizationId,
      UUID evaluationId,
      UUID evidenceId,
      UUID controlId,
      String fileName,
      String fileUrl) {}

  /**
   * Deja la evidencia en cola y crea el trabajo (si no hay uno activo). Una evidencia que no se
   * puede indexar pasa a {@code NOT_INDEXABLE} con el motivo.
   */
  @Transactional
  public void enqueue(Evidence evidence) {
    if (evidence.getScanStatus() == Evidence.ScanStatus.QUARANTINED) {
      markNotIndexable(evidence, "En cuarentena: se indexa cuando el análisis la libere.");
      return;
    }
    if (evidence.getScanStatus() == Evidence.ScanStatus.INFECTED) {
      markNotIndexable(evidence, "Rechazada por contenido malicioso.");
      return;
    }
    if (!EvidenceFileValidator.isIndexable(evidence.getFileType())) {
      markNotIndexable(evidence, null);
      return;
    }
    evidence.setIndexStatus(IndexStatus.QUEUED);
    evidence.setIndexError(null);
    evidence.setAiIndexed(false);
    String dedupKey = dedupKey(evidence.getId());
    if (jobRepo.findFirstByDedupKeyAndStatusIn(dedupKey, ACTIVE).isPresent()) {
      return; // ya hay un trabajo activo para esta evidencia
    }
    jobRepo.save(
        AiJob.builder()
            .type(AiJob.Type.EVIDENCE_INDEX)
            .organizationId(evidence.getEvaluation().getOrganization().getId())
            .priority(PRIORITY)
            .params(Map.of("evidenceId", evidence.getId().toString()))
            .dedupKey(dedupKey)
            .maxAttempts(props.maxAttempts())
            .build());
  }

  private static void markNotIndexable(Evidence evidence, String reason) {
    evidence.setIndexStatus(IndexStatus.NOT_INDEXABLE);
    evidence.setIndexError(reason);
    evidence.setAiIndexed(false);
  }

  /** El trabajador empieza: pasa a {@code INDEXING}. {@code null} si la evidencia ya no existe. */
  @Transactional
  public IndexInput prepare(UUID evidenceId) {
    Evidence evidence = evidenceRepo.findById(evidenceId).orElse(null);
    if (evidence == null) {
      return null;
    }
    evidence.setIndexStatus(IndexStatus.INDEXING);
    String url;
    try {
      url = evidenceService.internalFileUrl(evidence);
    } catch (Exception e) {
      log.warn("No se pudo firmar la URL de la evidencia {}: {}", evidenceId, e.getMessage());
      url = null;
    }
    return new IndexInput(
        evidence.getEvaluation().getOrganization().getId(),
        evidence.getEvaluation().getId(),
        evidence.getId(),
        evidence.getControl() != null ? evidence.getControl().getId() : null,
        evidence.getFileName(),
        url);
  }

  @Transactional
  public void markIndexed(UUID evidenceId) {
    evidenceRepo
        .findById(evidenceId)
        .ifPresent(
            e -> {
              e.setIndexStatus(IndexStatus.INDEXED);
              e.setAiIndexed(true);
              e.setIndexError(null);
              e.setIndexedAt(OffsetDateTime.now());
              // La evidencia del control cambió: las citas guardadas ya no reflejan todo (C2).
              if (e.getControl() != null) {
                citationStore.invalidateControl(e.getEvaluation().getId(), e.getControl().getId());
              } else {
                // Evidencia global ya indexada (C1 / D7): se sugieren controles en segundo plano.
                classificationService.enqueue(e);
              }
            });
  }

  /** Se acabaron los intentos: queda en {@code ERROR} con el motivo, para reintentar a mano. */
  @Transactional
  public void markError(UUID evidenceId, String reason) {
    evidenceRepo
        .findById(evidenceId)
        .ifPresent(
            e -> {
              e.setIndexStatus(IndexStatus.ERROR);
              e.setAiIndexed(false);
              e.setIndexError(reason);
            });
  }

  /** Falló pero la cola va a reintentar: vuelve a {@code QUEUED}. */
  @Transactional
  public void markQueued(UUID evidenceId) {
    evidenceRepo
        .findById(evidenceId)
        .ifPresent(
            e -> {
              e.setIndexStatus(IndexStatus.QUEUED);
              e.setAiIndexed(false);
            });
  }

  static String dedupKey(UUID evidenceId) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(("EVIDENCE_INDEX|" + evidenceId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
