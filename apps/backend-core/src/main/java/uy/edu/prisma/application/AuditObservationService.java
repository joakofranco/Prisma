package uy.edu.prisma.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.AuditObservation.ObservationStatus;
import uy.edu.prisma.domain.entity.AuditObservation.ObservationType;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.AuditObservationDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;
import uy.edu.prisma.web.dto.Dto.UpdateAuditObservationDto;

@Service
@Transactional
public class AuditObservationService {

  private final AuditObservationRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final CurrentUserService currentUser;

  public AuditObservationService(
      AuditObservationRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      CurrentUserService currentUser) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.currentUser = currentUser;
  }

  @Transactional(readOnly = true)
  public List<AuditObservationDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return repo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId).stream()
        .map(this::toDto)
        .toList();
  }

  public AuditObservationDto create(CreateAuditObservationDto dto) {
    Evaluation eval =
        evaluationRepo
            .findById(dto.evaluationId())
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", dto.evaluationId()));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    AuditObservation obs =
        AuditObservation.builder()
            .evaluation(eval)
            .control(resolveControl(dto.controlId()))
            .type(parseType(dto.type()))
            .description(dto.description())
            .status(
                dto.status() != null && !dto.status().isBlank()
                    ? parseStatus(dto.status())
                    : ObservationStatus.OPEN)
            .createdBy(currentUser.currentUser().orElse(null))
            .build();
    return toDto(repo.save(obs));
  }

  public AuditObservationDto update(UUID id, UpdateAuditObservationDto dto) {
    AuditObservation obs =
        repo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Observación de auditoría", id));
    currentUser.assertOrganizationAccess(obs.getEvaluation().getOrganization().getId());
    if (dto.controlId() != null) {
      obs.setControl(resolveControl(dto.controlId()));
    }
    if (dto.type() != null && !dto.type().isBlank()) {
      obs.setType(parseType(dto.type()));
    }
    if (dto.description() != null && !dto.description().isBlank()) {
      obs.setDescription(dto.description());
    }
    if (dto.status() != null && !dto.status().isBlank()) {
      obs.setStatus(parseStatus(dto.status()));
    }
    return toDto(repo.save(obs));
  }

  // dto.controlId() es opcional (una observación puede ser general, no atada a un control
  // puntual) -- antes se ignoraba SIEMPRE, incluso cuando venía cargado, así que toda observación
  // quedaba sin control asociado pese a que el DTO y el frontend sí lo mandan.
  private CatalogControl resolveControl(UUID controlId) {
    if (controlId == null) {
      return null;
    }
    return controlRepo
        .findById(controlId)
        .orElseThrow(() -> new ResourceNotFoundException("Control", controlId));
  }

  private ObservationType parseType(String type) {
    if (type == null || type.isBlank()) {
      return ObservationType.OBSERVATION;
    }
    try {
      return ObservationType.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Tipo de observación inválido: " + type);
    }
  }

  private ObservationStatus parseStatus(String status) {
    try {
      return ObservationStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException("Estado de observación inválido: " + status);
    }
  }

  private AuditObservationDto toDto(AuditObservation o) {
    User author = o.getCreatedBy();
    return new AuditObservationDto(
        o.getId(),
        o.getEvaluation().getId(),
        o.getControl() != null ? o.getControl().getId() : null,
        o.getType().name(),
        o.getDescription(),
        o.getStatus().name(),
        author != null ? author.getId() : null,
        o.getCreatedAt(),
        author != null ? (author.getFirstName() + " " + author.getLastName()).trim() : null);
  }
}
