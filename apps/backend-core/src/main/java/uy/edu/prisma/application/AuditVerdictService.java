package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.AuditControlVerdict.Verdict;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.ControlVerdictDto;

/**
 * Dictamen del auditor por control (RF-AUD-06): Validado, No validado o Requiere más evidencia.
 * Convive con las observaciones de auditoría (AuditObservationService), que no se tocan: el
 * dictamen es el resultado de la revisión del control; las observaciones, el registro de hallazgos.
 */
@Service
@Transactional
public class AuditVerdictService {

  private final AuditControlVerdictRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final CurrentUserService currentUser;
  private final AuditLogService auditLog;

  public AuditVerdictService(
      AuditControlVerdictRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      CurrentUserService currentUser,
      AuditLogService auditLog) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
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
    if (eval.getCommunityProfile() != null
        && eval.getCommunityProfile().getControls().stream()
            .noneMatch(c -> c.getId().equals(control.getId()))) {
      throw new InvalidRequestException(
          "El control "
              + control.getCode()
              + " no pertenece al perfil comunitario de esta evaluación");
    }

    AuditControlVerdict entity =
        repo.findByEvaluationIdAndControlId(evaluationId, controlId)
            .orElseGet(AuditControlVerdict::new);
    entity.setEvaluation(eval);
    entity.setControl(control);
    entity.setVerdict(verdict);
    entity.setSetBy(currentUser.currentUser().orElse(null));
    entity.setUpdatedAt(OffsetDateTime.now());
    ControlVerdictDto result = toDto(repo.save(entity));
    auditLog.record(
        "SET_CONTROL_VERDICT",
        "evaluation:" + evaluationId,
        "{\"controlId\":\"" + controlId + "\",\"verdict\":\"" + verdict + "\"}");
    return result;
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
        v.getUpdatedAt());
  }
}
