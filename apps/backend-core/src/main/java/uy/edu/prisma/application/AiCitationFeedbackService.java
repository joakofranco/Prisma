package uy.edu.prisma.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AiCitationFeedback;
import uy.edu.prisma.domain.entity.AiCitationFeedback.Decision;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiCitationFeedbackRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackItemDto;

/**
 * PRI-359: registra si el auditor aceptó o rechazó cada cita propuesta por la IA. Se guarda el
 * control del catálogo y el fragmento, no la evaluación, la organización ni el usuario (dataset
 * para medir la búsqueda y para fine-tuning). La evaluación viaja en el pedido solo para comprobar
 * el acceso y el estado: no se persiste.
 */
@Service
@Transactional
public class AiCitationFeedbackService {

  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final AiCitationFeedbackRepository feedbackRepo;
  private final CurrentUserService currentUser;

  public AiCitationFeedbackService(
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      AiCitationFeedbackRepository feedbackRepo,
      CurrentUserService currentUser) {
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.feedbackRepo = feedbackRepo;
    this.currentUser = currentUser;
  }

  public void record(CitationFeedbackBatchDto batch) {
    Evaluation eval =
        evaluationRepo
            .findById(batch.evaluationId())
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", batch.evaluationId()));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new ConflictException(
          "Solo se pueden evaluar citas mientras la evaluación está en auditoría (estado actual: "
              + eval.getStatus()
              + ")");
    }
    java.util.Map<UUID, CatalogControl> controls =
        controlRepo
            .findAllById(
                batch.items().stream().map(CitationFeedbackItemDto::controlId).distinct().toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(CatalogControl::getId, c -> c));
    List<AiCitationFeedback> rows = new java.util.ArrayList<>();
    for (CitationFeedbackItemDto item : batch.items()) {
      CatalogControl control = controls.get(item.controlId());
      if (control == null) {
        throw new ResourceNotFoundException("Control", item.controlId());
      }
      rows.add(
          AiCitationFeedback.builder()
              .control(control)
              .fileName(item.fileName())
              .location(item.location())
              .snippet(item.snippet())
              .score(item.score())
              .decision(Decision.valueOf(item.decision()))
              .build());
    }
    feedbackRepo.saveAll(rows);
  }
}
