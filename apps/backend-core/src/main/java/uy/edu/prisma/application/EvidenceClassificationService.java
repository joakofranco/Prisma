package uy.edu.prisma.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiEvidenceClassificationFeedback;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.EvidenceControlSuggestion;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiEvidenceClassificationFeedbackRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceControlSuggestionRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ClassifyCandidate;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ControlSuggestion;
import uy.edu.prisma.web.dto.Dto.EvidenceSuggestionDto;

/**
 * Preclasificación de evidencia global con IA (C1 / D7). Una evidencia subida SIN control se indexa
 * con {@code control_id = ""}; cuando termina, un trabajo {@code EVIDENCE_CLASSIFY} busca entre los
 * controles de la evaluación (acotados a su perfil comunitario, si lo tiene) los 3 más probables y
 * los guarda como <b>sugerencias</b>. Reglas:
 *
 * <ul>
 *   <li>Es solo una sugerencia: <b>aceptarla no asigna la evidencia a ese control</b> (una política
 *       típica respalda varios). Los fragmentos de la evidencia global ya entran en las citas de
 *       cualquier control; aceptar hace que el control aparezca en la lista de la auditoría.
 *   <li>Cada decisión deja una fila anónima y mínima (control, puntaje, posición, decisión): sin
 *       organización, evaluación, usuario, archivo ni texto (D15).
 *   <li>Las sugerencias decididas se conservan si se vuelve a clasificar; las pendientes se
 *       rehacen.
 * </ul>
 */
@Service
public class EvidenceClassificationService {

  private static final Logger log = LoggerFactory.getLogger(EvidenceClassificationService.class);
  private static final Set<AiJob.Status> ACTIVE = Set.of(Status.QUEUED, Status.RUNNING);

  /** Cuántos controles se sugieren por evidencia (C1: «los 1–3 más probables»). */
  static final int TOP_N = 3;

  private final EvidenceRepository evidenceRepo;
  private final EvidenceControlSuggestionRepository suggestionRepo;
  private final AiEvidenceClassificationFeedbackRepository feedbackRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationRepository evaluationRepo;
  private final AiEvidenceClient aiClient;
  private final AiJobRepository jobRepo;
  private final AiJobProperties props;
  private final CurrentUserService currentUser;
  // prepare() y save() se llaman desde run() (misma clase): no pasan por el proxy de
  // @Transactional,
  // así que abren su transacción a mano.
  private final org.springframework.transaction.support.TransactionTemplate tx;

  public EvidenceClassificationService(
      EvidenceRepository evidenceRepo,
      EvidenceControlSuggestionRepository suggestionRepo,
      AiEvidenceClassificationFeedbackRepository feedbackRepo,
      CatalogControlRepository controlRepo,
      EvaluationRepository evaluationRepo,
      AiEvidenceClient aiClient,
      AiJobRepository jobRepo,
      AiJobProperties props,
      CurrentUserService currentUser,
      org.springframework.transaction.PlatformTransactionManager txManager) {
    this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    this.evidenceRepo = evidenceRepo;
    this.suggestionRepo = suggestionRepo;
    this.feedbackRepo = feedbackRepo;
    this.controlRepo = controlRepo;
    this.evaluationRepo = evaluationRepo;
    this.aiClient = aiClient;
    this.jobRepo = jobRepo;
    this.props = props;
    this.currentUser = currentUser;
  }

  /** Encola la preclasificación de una evidencia global ya indexada (lote, trabajo del sistema). */
  @Transactional
  public void enqueue(Evidence evidence) {
    if (evidence.getControl() != null) {
      return; // la evidencia ya tiene control: no hay nada que sugerir
    }
    String dedupKey = dedupKey(evidence.getId());
    if (jobRepo.findFirstByDedupKeyAndStatusIn(dedupKey, ACTIVE).isPresent()) {
      return;
    }
    jobRepo.save(
        AiJob.builder()
            .type(AiJob.Type.EVIDENCE_CLASSIFY)
            .organizationId(evidence.getEvaluation().getOrganization().getId())
            .priority(AiJob.PRIORITY_BATCH)
            .params(Map.of("evidenceId", evidence.getId().toString()))
            .dedupKey(dedupKey)
            .maxAttempts(props.maxAttempts())
            .build());
  }

  /** Lo que se manda a backend-ai (calculado en una transacción corta). */
  public record ClassifyInput(
      UUID organizationId,
      UUID evaluationId,
      UUID evidenceId,
      List<ClassifyCandidate> candidates) {}

  ClassifyInput prepare(UUID evidenceId) {
    return tx.execute(status -> doPrepare(evidenceId));
  }

  private ClassifyInput doPrepare(UUID evidenceId) {
    Evidence evidence = evidenceRepo.findById(evidenceId).orElse(null);
    if (evidence == null
        || evidence.getControl() != null
        || evidence.getIndexStatus() != Evidence.IndexStatus.INDEXED) {
      return null;
    }
    Evaluation eval = evidence.getEvaluation();
    Set<UUID> profileControls =
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getControls().stream()
                .map(CatalogControl::getId)
                .collect(Collectors.toSet())
            : null;
    List<ClassifyCandidate> candidates =
        controlRepo.findByVersionOrdered(eval.getCatalogVersion()).stream()
            .filter(c -> profileControls == null || profileControls.contains(c.getId()))
            .map(c -> new ClassifyCandidate(c.getId(), c.getCode() + ": " + c.getDescription()))
            .toList();
    return new ClassifyInput(eval.getOrganization().getId(), eval.getId(), evidenceId, candidates);
  }

  /**
   * Corre la preclasificación de una evidencia y guarda las sugerencias. Devuelve cuántas guardó.
   * Lanza {@link uy.edu.prisma.domain.exception.AiUnavailableException} si backend-ai no responde
   * (la cola reintenta).
   */
  public int run(UUID evidenceId) {
    ClassifyInput input = prepare(evidenceId);
    if (input == null || input.candidates().isEmpty()) {
      return 0;
    }
    List<ControlSuggestion> suggestions =
        aiClient.classify(
            input.organizationId(),
            input.evaluationId(),
            input.evidenceId(),
            input.candidates(),
            TOP_N);
    Integer saved = tx.execute(status -> doSave(evidenceId, suggestions));
    return saved == null ? 0 : saved;
  }

  private int doSave(UUID evidenceId, List<ControlSuggestion> suggestions) {
    Evidence evidence = evidenceRepo.findById(evidenceId).orElse(null);
    if (evidence == null) {
      return 0;
    }
    suggestionRepo.deletePendingOfEvidence(evidenceId);
    suggestionRepo.flush();
    Set<UUID> decided =
        suggestionRepo.findByEvidenceId(evidenceId).stream()
            .map(s -> s.getControl().getId())
            .collect(Collectors.toSet());
    int saved = 0;
    int rank = 0;
    for (ControlSuggestion s : suggestions) {
      rank++;
      if (decided.contains(s.controlId())) {
        continue; // ya decidida: no se vuelve a sugerir
      }
      CatalogControl control = controlRepo.findById(s.controlId()).orElse(null);
      if (control == null) {
        continue;
      }
      suggestionRepo.save(
          EvidenceControlSuggestion.builder()
              .evaluation(evidence.getEvaluation())
              .evidence(evidence)
              .control(control)
              .score(s.score())
              .rank(rank)
              .build());
      saved++;
    }
    return saved;
  }

  // ------------------------------------------------------------------ consulta y decisión

  @Transactional(readOnly = true)
  public List<EvidenceSuggestionDto> list(UUID evaluationId) {
    assertAuditAccess(evaluationId);
    return suggestionRepo.findByEvaluationIdOrderByEvidenceIdAscRankAsc(evaluationId).stream()
        .map(EvidenceClassificationService::toDto)
        .toList();
  }

  /**
   * Acepta o rechaza una sugerencia. NO asigna la evidencia al control (D7); deja la decisión
   * guardada y una fila anónima de feedback.
   */
  @Transactional
  public EvidenceSuggestionDto decide(UUID suggestionId, String decision) {
    EvidenceControlSuggestion s =
        suggestionRepo
            .findById(suggestionId)
            .orElseThrow(() -> new ResourceNotFoundException("Sugerencia", suggestionId));
    assertAuditAccess(s.getEvaluation().getId());
    AiEvidenceClassificationFeedback.Decision d =
        AiEvidenceClassificationFeedback.Decision.valueOf(decision);
    s.setStatus(
        d == AiEvidenceClassificationFeedback.Decision.ACCEPTED
            ? EvidenceControlSuggestion.Status.ACCEPTED
            : EvidenceControlSuggestion.Status.REJECTED);
    s.setDecidedBy(currentUser.currentUser().orElse(null));
    s.setDecidedAt(OffsetDateTime.now());
    suggestionRepo.save(s);
    feedbackRepo.save(
        AiEvidenceClassificationFeedback.builder()
            .control(s.getControl())
            .score(s.getScore())
            .rank(s.getRank())
            .decision(d)
            .build());
    log.debug("Sugerencia {} decidida: {}", suggestionId, decision);
    return toDto(s);
  }

  private void assertAuditAccess(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new ConflictException(
          "Las sugerencias de control solo están disponibles con la evaluación en auditoría"
              + " (estado actual: "
              + eval.getStatus()
              + ")");
    }
  }

  private static EvidenceSuggestionDto toDto(EvidenceControlSuggestion s) {
    return new EvidenceSuggestionDto(
        s.getId(),
        s.getEvidence().getId(),
        s.getEvidence().getFileName(),
        s.getControl().getId(),
        s.getControl().getCode(),
        s.getControl().getDescription(),
        s.getScore(),
        s.getRank(),
        s.getStatus().name());
  }

  static String dedupKey(UUID evidenceId) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(("EVIDENCE_CLASSIFY|" + evidenceId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
