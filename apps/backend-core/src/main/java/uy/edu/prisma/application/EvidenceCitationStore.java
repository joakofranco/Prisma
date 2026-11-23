package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.entity.EvidenceCitationResult.Status;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.repository.EvidenceCitationResultRepository;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

/**
 * Citas de la IA guardadas por evaluación y control (C2). Reglas:
 *
 * <ul>
 *   <li>Lo guardado se devuelve primero; solo se vuelve a buscar si no hay nada, si el auditor pide
 *       «Actualizar» o si la evidencia del control cambió ({@code stale}).
 *   <li>Al volver a buscar se <b>conservan las decisiones</b> de los fragmentos que siguen
 *       apareciendo (se identifican por el id estable que devuelve backend-ai); los nuevos entran
 *       como {@code PENDING} y los que ya no aparecen se descartan.
 *   <li>Cambiar la evidencia de un control descarta las citas sin decidir y marca viejas las
 *       decididas; salir de auditoría descarta todas (el dictamen ya fijado NO se toca: queda con
 *       su cita de respaldo en {@code source_ref}).
 * </ul>
 */
@Service
@Transactional
public class EvidenceCitationStore {

  private final EvidenceCitationResultRepository repo;
  private final uy.edu.prisma.domain.repository.EvidenceRepository evidenceRepo;

  public EvidenceCitationStore(
      EvidenceCitationResultRepository repo,
      uy.edu.prisma.domain.repository.EvidenceRepository evidenceRepo) {
    this.repo = repo;
    this.evidenceRepo = evidenceRepo;
  }

  // Modo investigación (C8). Por setter y opcional: el servicio funciona igual sin trazas.
  private AiTraceService traceService;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setTraceService(AiTraceService traceService) {
    this.traceService = traceService;
  }

  @Transactional(readOnly = true)
  public List<EvidenceCitationResult> saved(UUID evaluationId, UUID controlId) {
    return repo.findByEvaluationIdAndControlIdOrderByPositionAsc(evaluationId, controlId);
  }

  @Transactional(readOnly = true)
  public List<EvidenceCitationResult> savedOfEvaluation(UUID evaluationId) {
    return repo.findByEvaluationIdOrderByControlIdAscPositionAsc(evaluationId);
  }

  @Transactional(readOnly = true)
  public List<UUID> controlsWithResults(UUID evaluationId, java.util.Collection<UUID> controlIds) {
    return controlIds.isEmpty()
        ? List.of()
        : repo.findControlsWithResults(evaluationId, controlIds);
  }

  @Transactional(readOnly = true)
  public boolean isStale(UUID evaluationId, UUID controlId) {
    return repo.existsByEvaluationIdAndControlIdAndStaleTrue(evaluationId, controlId);
  }

  /**
   * Reemplaza las citas guardadas del control por el resultado de una búsqueda nueva, conservando
   * la decisión de los fragmentos que siguen apareciendo. Devuelve la lista final, en orden.
   */
  public List<EvidenceCitationResult> merge(
      Evaluation eval, CatalogControl control, List<EvidenceCitationDto> fresh) {
    List<EvidenceCitationResult> existing =
        repo.findByEvaluationIdAndControlIdOrderByPositionAsc(eval.getId(), control.getId());
    Map<String, EvidenceCitationResult> byFragment = new HashMap<>();
    for (EvidenceCitationResult r : existing) {
      if (r.getFragmentId() != null) {
        byFragment.put(r.getFragmentId(), r);
      }
    }
    List<EvidenceCitationResult> result = new ArrayList<>();
    List<EvidenceCitationResult> kept = new ArrayList<>();
    OffsetDateTime now = OffsetDateTime.now();
    int position = 0;
    // Una evidencia borrada entre la búsqueda y el guardado no puede quedar referenciada (FK).
    java.util.Set<UUID> evidenceIds =
        fresh.stream()
            .map(EvidenceCitationDto::evidenceId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    java.util.Set<UUID> alive =
        evidenceRepo.findAllById(evidenceIds).stream()
            .map(uy.edu.prisma.domain.entity.Evidence::getId)
            .collect(java.util.stream.Collectors.toSet());
    for (EvidenceCitationDto f : fresh) {
      if (f.evidenceId() != null && !alive.contains(f.evidenceId())) {
        continue;
      }
      EvidenceCitationResult current =
          f.fragmentId() != null ? byFragment.remove(f.fragmentId()) : null;
      if (current == null) {
        current =
            EvidenceCitationResult.builder()
                .evaluation(eval)
                .control(control)
                .fragmentId(f.fragmentId())
                .status(Status.PENDING)
                .build();
      } else {
        kept.add(current);
      }
      current.setEvidenceId(f.evidenceId());
      current.setFileName(f.fileName());
      current.setLocation(f.location());
      current.setSnippet(f.snippet());
      current.setScore(f.score());
      current.setUnassigned(f.unassigned());
      current.setPosition(position++);
      current.setStale(false);
      current.setUpdatedAt(now);
      result.add(current);
    }
    // Lo que ya no aparece se descarta; se borra ANTES de insertar para no chocar con el índice
    // único del id de fragmento.
    List<EvidenceCitationResult> gone = existing.stream().filter(r -> !kept.contains(r)).toList();
    repo.deleteAll(gone);
    repo.flush();
    List<EvidenceCitationResult> saved = repo.saveAll(result);
    // Modo investigación (C8): solo ids y puntajes (D15); apagado no guarda nada (D2).
    if (traceService != null) {
      traceService.recordCitations(eval.getOrganization().getId(), saved);
    }
    return saved;
  }

  /** La evidencia del control cambió: ver la descripción de la clase. */
  public void invalidateControl(UUID evaluationId, UUID controlId) {
    repo.deleteByControlAndStatus(evaluationId, controlId, Status.PENDING);
    repo.markDecidedStale(evaluationId, controlId, Status.PENDING);
  }

  /** La evaluación salió de auditoría: se descartan todas las citas guardadas. */
  public int invalidateEvaluation(UUID evaluationId) {
    return repo.deleteByEvaluation(evaluationId);
  }

  /** Resultado de decidir una cita: la cita ya actualizada y el estado que tenía antes. */
  public record Decided(EvidenceCitationResult result, Status previous) {}

  public Decided decide(
      UUID citationId, UUID evaluationId, UUID controlId, Status status, User by) {
    EvidenceCitationResult r =
        repo.findById(citationId)
            .orElseThrow(
                () ->
                    new uy.edu.prisma.domain.exception.ResourceNotFoundException(
                        "Cita", citationId));
    if (!r.getEvaluation().getId().equals(evaluationId)
        || !r.getControl().getId().equals(controlId)) {
      throw new uy.edu.prisma.domain.exception.InvalidRequestException(
          "La cita no corresponde a esta evaluación y control");
    }
    Status previous = r.getStatus();
    r.setStatus(status);
    r.setDecidedBy(by);
    r.setDecidedAt(OffsetDateTime.now());
    r.setUpdatedAt(OffsetDateTime.now());
    repo.save(r);
    return new Decided(r, previous);
  }

  @Transactional(readOnly = true)
  public List<EvidenceCitationResult> accepted(UUID evaluationId, UUID controlId) {
    return repo.findByEvaluationIdAndControlIdAndStatus(evaluationId, controlId, Status.ACCEPTED);
  }

  /** «Deshacer» un dictamen por cita: las citas que lo respaldaban vuelven a quedar sin decidir. */
  public int resetAccepted(UUID evaluationId, UUID controlId) {
    List<EvidenceCitationResult> accepted =
        repo.findByEvaluationIdAndControlIdAndStatus(evaluationId, controlId, Status.ACCEPTED);
    OffsetDateTime now = OffsetDateTime.now();
    for (EvidenceCitationResult r : accepted) {
      r.setStatus(Status.PENDING);
      r.setDecidedBy(null);
      r.setDecidedAt(null);
      r.setUpdatedAt(now);
    }
    repo.saveAll(accepted);
    return accepted.size();
  }

  public static EvidenceCitationDto toDto(EvidenceCitationResult r) {
    return new EvidenceCitationDto(
        r.getEvidenceId(),
        r.getFileName(),
        r.getLocation(),
        r.getSnippet(),
        r.getScore() != null ? r.getScore() : 0.0,
        r.getFragmentId(),
        r.getId(),
        r.getStatus().name(),
        r.getPosition(),
        r.getControl().getId(),
        r.isStale(),
        r.isUnassigned());
  }
}
