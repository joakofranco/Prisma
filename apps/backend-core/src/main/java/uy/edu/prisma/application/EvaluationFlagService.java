package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationControlFlag;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationControlFlagRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.ControlFlagDto;

/**
 * Marca «para revisar» por control (PRI-352). Se guarda en el momento (no con «Guardar progreso») y
 * la ven todos los que acceden a la evaluación: evaluadores, responsable y auditor. La puede poner
 * y quitar cualquiera de los roles que trabajan la evaluación, no solo quien la puso.
 */
@Service
@Transactional
public class EvaluationFlagService {

  private final EvaluationControlFlagRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final CurrentUserService currentUser;

  public EvaluationFlagService(
      EvaluationControlFlagRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      CurrentUserService currentUser) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.currentUser = currentUser;
  }

  @Transactional(readOnly = true)
  public List<ControlFlagDto> list(UUID evaluationId) {
    Evaluation eval = findAccessibleEvaluation(evaluationId);
    return repo.findByEvaluationId(eval.getId()).stream().map(this::toDto).toList();
  }

  /** Marca o desmarca el control; idempotente (marcar dos veces no duplica ni falla). */
  public void setFlagged(UUID evaluationId, UUID controlId, boolean flagged) {
    Evaluation eval = findAccessibleEvaluation(evaluationId);
    // Se puede marcar mientras se responde (estados editables) y durante la auditoría, que es
    // cuando el auditor revisa los controles. Cerrada la evaluación, queda como registro.
    if (!eval.isSelfAssessmentEditable() && eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new InvalidRequestException(
          "No se pueden marcar controles: la evaluación está en estado "
              + eval.getStatus()
              + ", que ya no admite edición");
    }
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

    var existing = repo.findByEvaluationIdAndControlId(evaluationId, controlId);
    if (!flagged) {
      existing.ifPresent(repo::delete);
      return;
    }
    if (existing.isPresent()) {
      return;
    }
    repo.save(
        EvaluationControlFlag.builder()
            .evaluation(eval)
            .control(control)
            .flaggedBy(currentUser.currentUser().orElse(null))
            .flaggedAt(OffsetDateTime.now())
            .build());
  }

  private Evaluation findAccessibleEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return eval;
  }

  private ControlFlagDto toDto(EvaluationControlFlag f) {
    User by = f.getFlaggedBy();
    return new ControlFlagDto(
        f.getControl().getId(),
        by != null ? by.getId() : null,
        by != null ? (by.getFirstName() + " " + by.getLastName()).trim() : null,
        f.getFlaggedAt());
  }
}
