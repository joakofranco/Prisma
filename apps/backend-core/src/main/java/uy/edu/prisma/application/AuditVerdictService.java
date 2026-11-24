package uy.edu.prisma.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.AuditControlVerdict.Verdict;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.exception.VerdictOverrideRequiredException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.ControlVerdictDto;
import uy.edu.prisma.web.dto.Dto.VerdictChangeDto;

/**
 * Dictamen del auditor por control (RF-AUD-06): Validado, No validado o Requiere más evidencia.
 * Convive con las observaciones de auditoría (AuditObservationService), que no se tocan: el
 * dictamen es el resultado de la revisión del control; las observaciones, el registro de hallazgos.
 */
@Service
@Transactional
public class AuditVerdictService {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AuditControlVerdictRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final CurrentUserService currentUser;
  private final AuditLogService auditLog;
  private final EvidenceCitationStore citationStore;

  public AuditVerdictService(
      AuditControlVerdictRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      CurrentUserService currentUser,
      AuditLogService auditLog,
      EvidenceCitationStore citationStore) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
    this.citationStore = citationStore;
  }

  @Transactional(readOnly = true)
  public List<ControlVerdictDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval = findAccessibleEvaluation(evaluationId);
    return repo.findByEvaluationId(eval.getId()).stream().map(this::toDto).toList();
  }

  /** Registra o cambia el dictamen del control; solo mientras la evaluación está en auditoría. */
  public ControlVerdictDto set(UUID evaluationId, UUID controlId, String verdictName) {
    Evaluation eval = findAccessibleEvaluation(evaluationId);
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new InvalidRequestException(
          "Solo se puede dictaminar mientras la evaluación está en auditoría (estado actual: "
              + eval.getStatus()
              + ")");
    }
    Verdict verdict = parseVerdict(verdictName);
    CatalogControl control =
        controlRepo
            .findById(controlId)
            .orElseThrow(() -> new ResourceNotFoundException("Control", controlId));
    assertControlInProfile(eval, control);

    AuditControlVerdict entity =
        repo.findByEvaluationIdAndControlId(evaluationId, controlId)
            .orElseGet(AuditControlVerdict::new);
    entity.setEvaluation(eval);
    entity.setControl(control);
    entity.setVerdict(verdict);
    // Lo fija el auditor a mano: pisa cualquier origen anterior (también uno que vino de una cita).
    entity.setSource(AuditControlVerdict.Source.MANUAL);
    entity.setSourceRef(null);
    entity.setSetBy(currentUser.currentUser().orElse(null));
    entity.setUpdatedAt(OffsetDateTime.now());
    ControlVerdictDto result = toDto(repo.save(entity));
    auditLog.record(
        "SET_CONTROL_VERDICT",
        "evaluation:" + evaluationId,
        "{\"controlId\":\""
            + controlId
            + "\",\"verdict\":\""
            + verdict
            + "\",\"source\":\"MANUAL\"}");
    return result;
  }

  /**
   * Aceptar una cita pasa el control a «Validado» (C4a, versión reducida; D1, D5, D10). Se llama
   * desde la misma transacción que registra la decisión sobre la cita. Reglas:
   *
   * <ul>
   *   <li>Sin dictamen: pasa a {@code VALIDATED} con origen {@code CITATION} y la cita de respaldo.
   *   <li>Ya {@code VALIDATED}: sin cambios (la cita queda como respaldo adicional).
   *   <li>{@code NOT_VALIDATED} / {@code NEEDS_MORE_EVIDENCE} puesto a mano: hace falta la
   *       confirmación del auditor ({@code confirmOverride}); si no, {@link
   *       VerdictOverrideRequiredException} (HTTP 409).
   * </ul>
   *
   * Rechazar una cita no pasa por acá: nunca cambia el dictamen. La evaluación tiene que estar en
   * auditoría (lo verifica quien llama) y el control en el perfil comunitario.
   */
  public VerdictChangeDto applyCitation(
      Evaluation eval,
      CatalogControl control,
      Map<String, Object> citation,
      boolean confirmOverride) {
    assertControlInProfile(eval, control);
    AuditControlVerdict entity =
        repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()).orElse(null);
    if (entity != null && entity.getVerdict() == Verdict.VALIDATED) {
      return new VerdictChangeDto(
          control.getId(), Verdict.VALIDATED.name(), entity.getSource().name(), false);
    }
    if (entity != null && !confirmOverride) {
      throw new VerdictOverrideRequiredException(
          control.getId(), control.getCode(), entity.getVerdict().name());
    }
    // Antes de pisarlo: el registro de auditoría guarda qué dictamen había.
    String previousVerdict = entity != null ? entity.getVerdict().name() : null;
    AuditControlVerdict target = entity != null ? entity : new AuditControlVerdict();
    target.setEvaluation(eval);
    target.setControl(control);
    target.setVerdict(Verdict.VALIDATED);
    target.setSource(AuditControlVerdict.Source.CITATION);
    target.setSourceRef(citation);
    target.setSetBy(currentUser.currentUser().orElse(null));
    target.setUpdatedAt(OffsetDateTime.now());
    repo.save(target);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("controlId", control.getId().toString());
    payload.put("verdict", Verdict.VALIDATED.name());
    payload.put("source", "CITATION");
    payload.put("citation", citation);
    if (previousVerdict != null) {
      payload.put("previousVerdict", previousVerdict);
    }
    auditLog.record("SET_CONTROL_VERDICT", "evaluation:" + eval.getId(), toJson(payload));
    return new VerdictChangeDto(
        control.getId(),
        Verdict.VALIDATED.name(),
        AuditControlVerdict.Source.CITATION.name(),
        true);
  }

  /**
   * Se pasó de aceptada a rechazada una cita (C4a / D10). Si el dictamen vino de una cita ({@code
   * source = CITATION}) y ya no queda ninguna cita aceptada del control, vuelve a «Sin dictamen»;
   * si quedan otras aceptadas el dictamen se mantiene (y, si el respaldo registrado era la cita
   * rechazada, pasa a otra); si el auditor lo fijó o confirmó a mano, no se toca. Devuelve el
   * cambio ({@code verdict = null} = revertido) o {@code null} si no hubo ninguno.
   */
  public VerdictChangeDto onCitationRevoked(
      Evaluation eval, CatalogControl control, UUID revokedCitationId) {
    AuditControlVerdict entity =
        repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()).orElse(null);
    if (entity == null || entity.getSource() != AuditControlVerdict.Source.CITATION) {
      return null;
    }
    List<EvidenceCitationResult> remaining = citationStore.accepted(eval.getId(), control.getId());
    if (!remaining.isEmpty()) {
      Object backing =
          entity.getSourceRef() != null ? entity.getSourceRef().get("citationId") : null;
      if (revokedCitationId != null && revokedCitationId.toString().equals(backing)) {
        entity.setSourceRef(refOf(remaining.get(0)));
        repo.save(entity);
      }
      return null;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("controlId", control.getId().toString());
    payload.put("verdict", null);
    payload.put("previousVerdict", entity.getVerdict().name());
    payload.put("source", "CITATION");
    payload.put("reverted", true);
    payload.put("reason", "CITATION_REJECTED");
    payload.put("citation", entity.getSourceRef());
    repo.delete(entity);
    auditLog.record("SET_CONTROL_VERDICT", "evaluation:" + eval.getId(), toJson(payload));
    return new VerdictChangeDto(control.getId(), null, null, true);
  }

  /**
   * «Deshacer»: quita un dictamen que vino de aceptar una cita y deja esas citas otra vez sin
   * decidir. Un dictamen fijado a mano no se deshace por acá.
   */
  public void undoCitationVerdict(UUID evaluationId, UUID controlId) {
    Evaluation eval = findAccessibleEvaluation(evaluationId);
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new InvalidRequestException(
          "Solo se puede deshacer un dictamen mientras la evaluación está en auditoría (estado"
              + " actual: "
              + eval.getStatus()
              + ")");
    }
    AuditControlVerdict entity =
        repo.findByEvaluationIdAndControlId(evaluationId, controlId)
            .orElseThrow(() -> new ResourceNotFoundException("Dictamen del control", controlId));
    if (entity.getSource() != AuditControlVerdict.Source.CITATION) {
      throw new ConflictException(
          "Solo se puede deshacer un dictamen que salió de aceptar una cita: este lo fijó el"
              + " auditor a mano");
    }
    int reset = citationStore.resetAccepted(evaluationId, controlId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("controlId", controlId.toString());
    payload.put("verdict", null);
    payload.put("previousVerdict", entity.getVerdict().name());
    payload.put("source", "CITATION");
    payload.put("reverted", true);
    payload.put("reason", "UNDO");
    payload.put("citationsReset", reset);
    repo.delete(entity);
    auditLog.record("SET_CONTROL_VERDICT", "evaluation:" + evaluationId, toJson(payload));
  }

  private static Map<String, Object> refOf(EvidenceCitationResult r) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("citationId", r.getId().toString());
    ref.put("fragmentId", r.getFragmentId());
    ref.put("evidenceId", r.getEvidenceId() != null ? r.getEvidenceId().toString() : null);
    ref.put("fileName", r.getFileName());
    ref.put("location", r.getLocation());
    ref.put("score", r.getScore());
    String snippet = r.getSnippet();
    ref.put("snippet", snippet.length() > 300 ? snippet.substring(0, 300) + "…" : snippet);
    return ref;
  }

  private static String toJson(Map<String, Object> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("No se pudo serializar el registro de auditoría", e);
    }
  }

  private static void assertControlInProfile(Evaluation eval, CatalogControl control) {
    if (eval.getCommunityProfile() != null
        && eval.getCommunityProfile().getControls().stream()
            .noneMatch(c -> c.getId().equals(control.getId()))) {
      throw new InvalidRequestException(
          "El control "
              + control.getCode()
              + " no pertenece al perfil comunitario de esta evaluación");
    }
  }

  private Evaluation findAccessibleEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return eval;
  }

  private Verdict parseVerdict(String name) {
    if (name == null || name.isBlank()) {
      throw new InvalidRequestException("El dictamen es obligatorio");
    }
    try {
      return Verdict.valueOf(name);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Dictamen inválido: " + name);
    }
  }

  private ControlVerdictDto toDto(AuditControlVerdict v) {
    User author = v.getSetBy();
    return new ControlVerdictDto(
        v.getControl().getId(),
        v.getVerdict().name(),
        author != null ? author.getId() : null,
        author != null ? (author.getFirstName() + " " + author.getLastName()).trim() : null,
        v.getUpdatedAt(),
        v.getSource().name(),
        v.getSourceRef());
  }
}
