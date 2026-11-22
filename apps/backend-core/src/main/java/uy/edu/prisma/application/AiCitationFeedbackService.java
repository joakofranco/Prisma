package uy.edu.prisma.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AiCitationFeedback;
import uy.edu.prisma.domain.entity.AiCitationFeedback.Decision;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiCitationFeedbackRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackItemDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackResultDto;
import uy.edu.prisma.web.dto.Dto.VerdictChangeDto;

/**
 * PRI-359: registra si el auditor aceptó o rechazó cada cita propuesta por la IA. Se guarda el
 * control del catálogo y el fragmento, no la evaluación, la organización ni el usuario (dataset
 * para medir la búsqueda y para fine-tuning). La evaluación viaja en el pedido solo para comprobar
 * el acceso y el estado: no se persiste.
 *
 * <p>C4a (versión reducida): <b>aceptar una cita significa que el fragmento es relevante Y valida
 * el control</b> (D5), así que en la MISMA transacción el control pasa a «Validado» con origen
 * {@code CITATION} (ver {@link AuditVerdictService#applyCitation}). Rechazar una cita, una o todas,
 * no cambia el dictamen (D1): que un fragmento no sirva no prueba incumplimiento.
 */
@Service
@Transactional
public class AiCitationFeedbackService {

  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final AiCitationFeedbackRepository feedbackRepo;
  private final CurrentUserService currentUser;
  private final AuditVerdictService verdictService;
  private final EvidenceCitationStore citationStore;

  public AiCitationFeedbackService(
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      AiCitationFeedbackRepository feedbackRepo,
      CurrentUserService currentUser,
      AuditVerdictService verdictService,
      EvidenceCitationStore citationStore) {
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.feedbackRepo = feedbackRepo;
    this.currentUser = currentUser;
    this.verdictService = verdictService;
    this.citationStore = citationStore;
  }

  public CitationFeedbackResultDto record(CitationFeedbackBatchDto batch) {
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
    Map<UUID, CatalogControl> controls =
        controlRepo
            .findAllById(
                batch.items().stream().map(CitationFeedbackItemDto::controlId).distinct().toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(CatalogControl::getId, c -> c));
    List<AiCitationFeedback> rows = new java.util.ArrayList<>();
    Map<UUID, VerdictChangeDto> verdicts = new LinkedHashMap<>();
    boolean confirm = Boolean.TRUE.equals(batch.confirmVerdictOverride());
    for (CitationFeedbackItemDto item : batch.items()) {
      CatalogControl control = controls.get(item.controlId());
      if (control == null) {
        throw new ResourceNotFoundException("Control", item.controlId());
      }
      Decision decision = Decision.valueOf(item.decision());
      // C2: la decisión queda guardada en la cita (estado, quién y cuándo), en la misma operación.
      EvidenceCitationStore.Decided decided = null;
      if (item.citationId() != null) {
        decided =
            citationStore.decide(
                item.citationId(),
                eval.getId(),
                control.getId(),
                decision == Decision.ACCEPTED
                    ? EvidenceCitationResult.Status.ACCEPTED
                    : EvidenceCitationResult.Status.REJECTED,
                currentUser.currentUser().orElse(null));
      }
      // Aceptar = relevante y valida el control. Un control con varias citas aceptadas se dictamina
      // una sola vez (la primera cita queda como respaldo).
      if (decision == Decision.ACCEPTED && !verdicts.containsKey(control.getId())) {
        verdicts.put(
            control.getId(),
            verdictService.applyCitation(eval, control, citationRef(item), confirm));
      }
      // D10: pasar de aceptada a rechazada la única cita de respaldo revierte un dictamen que
      // vino de la cita (uno fijado a mano se mantiene).
      if (decision == Decision.REJECTED
          && decided != null
          && decided.previous() == EvidenceCitationResult.Status.ACCEPTED) {
        VerdictChangeDto reverted =
            verdictService.onCitationRevoked(eval, control, item.citationId());
        if (reverted != null) {
          verdicts.put(control.getId(), reverted);
        }
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
    return new CitationFeedbackResultDto(List.copyOf(verdicts.values()));
  }

  /**
   * La cita que queda como respaldo del dictamen (sin el fragmento completo, que puede ser largo).
   */
  private static Map<String, Object> citationRef(CitationFeedbackItemDto item) {
    Map<String, Object> ref = new LinkedHashMap<>();
    // citationId y fragmentId (id estable de backend-ai) identifican el respaldo aunque se vuelva a
    // buscar: permiten saber si el dictamen sigue respaldado cuando se rechaza una cita.
    ref.put("citationId", item.citationId() != null ? item.citationId().toString() : null);
    ref.put("evidenceId", item.evidenceId() != null ? item.evidenceId().toString() : null);
    ref.put("fileName", item.fileName());
    ref.put("location", item.location());
    ref.put("score", item.score());
    String snippet = item.snippet();
    ref.put("snippet", snippet.length() > 300 ? snippet.substring(0, 300) + "…" : snippet);
    return ref;
  }
}
