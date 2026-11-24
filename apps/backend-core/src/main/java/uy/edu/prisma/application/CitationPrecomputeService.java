package uy.edu.prisma.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * {@code CITATIONS_PRECOMPUTE}: cuando la evaluación pasa a auditoría, busca y guarda las citas de
 * cada control que tiene evidencia indexada, para que el auditor las encuentre listas (C2). Corre
 * como trabajo de lote (prioridad 0) y del sistema. Es de mejor esfuerzo: un control sin evidencia
 * indexada todavía se resuelve en la primera consulta. Si la evaluación sale de auditoría mientras
 * corre, el trabajo se descarta (R7).
 */
@Service
public class CitationPrecomputeService {

  private static final Logger log = LoggerFactory.getLogger(CitationPrecomputeService.class);
  private static final Set<AiJob.Status> ACTIVE = Set.of(Status.QUEUED, Status.RUNNING);

  private final EvaluationRepository evaluationRepo;
  private final EvidenceRepository evidenceRepo;
  private final CatalogControlRepository controlRepo;
  private final EvidenceCitationStore store;
  private final AiEvidenceClient aiClient;
  private final AiJobRepository jobRepo;
  private final AiJobProperties props;

  public CitationPrecomputeService(
      EvaluationRepository evaluationRepo,
      EvidenceRepository evidenceRepo,
      CatalogControlRepository controlRepo,
      EvidenceCitationStore store,
      AiEvidenceClient aiClient,
      AiJobRepository jobRepo,
      AiJobProperties props) {
    this.evaluationRepo = evaluationRepo;
    this.evidenceRepo = evidenceRepo;
    this.controlRepo = controlRepo;
    this.store = store;
    this.aiClient = aiClient;
    this.jobRepo = jobRepo;
    this.props = props;
  }

  @Transactional
  public void enqueue(UUID evaluationId, UUID organizationId) {
    String dedupKey = dedupKey(evaluationId);
    if (jobRepo.findFirstByDedupKeyAndStatusIn(dedupKey, ACTIVE).isPresent()) {
      return;
    }
    jobRepo.save(
        AiJob.builder()
            .type(AiJob.Type.CITATIONS_PRECOMPUTE)
            .organizationId(organizationId)
            .priority(AiJob.PRIORITY_BATCH)
            .params(Map.of("evaluationId", evaluationId.toString()))
            .dedupKey(dedupKey)
            .maxAttempts(props.maxAttempts())
            .build());
  }

  /**
   * Busca y guarda las citas de los controles con evidencia indexada que todavía no las tienen.
   * Devuelve cuántos controles procesó. Lanza {@link
   * uy.edu.prisma.domain.exception.AiUnavailableException} si backend-ai no responde (la cola
   * reintenta).
   */
  public int run(UUID evaluationId) {
    int processed = 0;
    for (UUID controlId : controlsToCompute(evaluationId)) {
      Evaluation eval = evaluationRepo.findById(evaluationId).orElse(null);
      if (eval == null || eval.getStatus() != Evaluation.Status.IN_AUDIT) {
        log.info("Evaluación {} salió de auditoría: se descarta el precálculo", evaluationId);
        return processed;
      }
      CatalogControl control = controlRepo.findById(controlId).orElse(null);
      if (control == null) {
        continue;
      }
      List<EvidenceCitationDto> fresh =
          aiClient.citations(
              eval.getOrganization().getId(),
              evaluationId,
              controlId,
              control.getCode() + ": " + control.getDescription());
      store.merge(eval, control, fresh);
      processed++;
    }
    return processed;
  }

  @Transactional(readOnly = true)
  List<UUID> controlsToCompute(UUID evaluationId) {
    List<UUID> withEvidence = evidenceRepo.findIndexedControlIds(evaluationId);
    if (withEvidence.isEmpty()) {
      return List.of();
    }
    Set<UUID> alreadyHave = Set.copyOf(store.controlsWithResults(evaluationId, withEvidence));
    return withEvidence.stream()
        .filter(id -> !alreadyHave.contains(id) || store.isStale(evaluationId, id))
        .toList();
  }

  static String dedupKey(UUID evaluationId) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(("CITATIONS_PRECOMPUTE|" + evaluationId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
