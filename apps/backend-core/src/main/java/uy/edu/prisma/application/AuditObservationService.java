package uy.edu.prisma.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.AuditObservation.ObservationStatus;
import uy.edu.prisma.domain.entity.AuditObservation.ObservationType;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogSubcategoryRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.web.dto.Dto.AuditObservationDto;
import uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto;
import uy.edu.prisma.web.dto.Dto.UpdateAuditObservationDto;

@Service
@Transactional
public class AuditObservationService {

  private final AuditObservationRepository repo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final CatalogSubcategoryRepository subcategoryRepo;
  private final EvidenceRepository evidenceRepo;
  private final CurrentUserService currentUser;

  // Estados en los que la organización puede leer las observaciones del auditor: cuando la
  // evaluación vuelve (RETURNED) y después. Las observaciones son la forma en que el auditor le
  // comunica por qué la devuelve; mientras audita (IN_AUDIT) son su borrador de trabajo (PRI-354).
  // RF-AUD-08 (PRI-298): también en IN_PROGRESS y READY_FOR_AUDIT, que es cuando la organización
  // corrige tras «Reanudar» y la reenvía. Es seguro porque las observaciones solo se crean con la
  // evaluación IN_AUDIT: en esos estados solo existen si una auditoría anterior la devolvió (en el
  // primer ciclo no hay ninguna).
  private static final Set<Evaluation.Status> VISIBLE_TO_ORGANIZATION =
      Set.of(
          Evaluation.Status.RETURNED,
          Evaluation.Status.IN_PROGRESS,
          Evaluation.Status.READY_FOR_AUDIT,
          Evaluation.Status.APPROVED,
          Evaluation.Status.ARCHIVED);

  public AuditObservationService(
      AuditObservationRepository repo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      CatalogSubcategoryRepository subcategoryRepo,
      EvidenceRepository evidenceRepo,
      CurrentUserService currentUser) {
    this.repo = repo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.subcategoryRepo = subcategoryRepo;
    this.evidenceRepo = evidenceRepo;
    this.currentUser = currentUser;
  }

  @Transactional(readOnly = true)
  public List<AuditObservationDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    // El administrador y el auditor las ven siempre. La organización (responsable o evaluador
    // interno), solo desde que la evaluación vuelve; antes recibe una lista vacía y no un error,
    // así la pantalla no muestra un fallo y no se revela si hay observaciones en borrador.
    boolean always = currentUser.isPrismaAdmin() || currentUser.isAuditor();
    if (!always && !VISIBLE_TO_ORGANIZATION.contains(eval.getStatus())) {
      return List.of();
    }
    return repo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId).stream()
        .map(this::toDto)
        .toList();
  }

  /**
   * Escribir (crear o editar) observaciones solo se puede mientras la evaluación está en auditoría,
   * igual que el dictamen por control: después de devolverla o aprobarla quedan como registro.
   */
  private void assertInAudit(Evaluation eval, String action) {
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new ConflictException(
          "Solo se pueden "
              + action
              + " mientras la evaluación está en auditoría (estado actual: "
              + eval.getStatus()
              + ")");
    }
  }

  public AuditObservationDto create(CreateAuditObservationDto dto) {
    Evaluation eval =
        evaluationRepo
            .findById(dto.evaluationId())
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", dto.evaluationId()));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    assertInAudit(eval, "registrar observaciones");
    AuditObservation obs =
        AuditObservation.builder()
            .evaluation(eval)
            .control(resolveControl(dto.controlId()))
            .subcategory(resolveSubcategory(dto.subcategoryId()))
            .evidence(resolveEvidence(dto.evidenceId(), eval))
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
    assertInAudit(obs.getEvaluation(), "modificar observaciones");
    // Antes una descripción vacía se ignoraba en silencio y la observación quedaba como estaba.
    if (dto.description() != null && dto.description().isBlank()) {
      throw new InvalidRequestException("La descripción de la observación no puede estar vacía");
    }
    if (dto.controlId() != null) {
      obs.setControl(resolveControl(dto.controlId()));
    }
    if (dto.subcategoryId() != null) {
      obs.setSubcategory(resolveSubcategory(dto.subcategoryId()));
    }
    if (dto.evidenceId() != null) {
      obs.setEvidence(resolveEvidence(dto.evidenceId(), obs.getEvaluation()));
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

  // dto.subcategoryId() es opcional, igual que controlId: una observación puede ser general, estar
  // atada a un control puntual, a una subcategoría, o a ambos a la vez (RF-AUD-05).
  private CatalogSubcategory resolveSubcategory(UUID subcategoryId) {
    if (subcategoryId == null) {
      return null;
    }
    return subcategoryRepo
        .findById(subcategoryId)
        .orElseThrow(() -> new ResourceNotFoundException("Subcategoría", subcategoryId));
  }

  // La evidencia citada es opcional, pero si viene tiene que ser de la MISMA evaluación: si no, una
  // observación podría apuntar al documento de otra organización (RF-EVI-15, aislamiento).
  private Evidence resolveEvidence(UUID evidenceId, Evaluation eval) {
    if (evidenceId == null) {
      return null;
    }
    Evidence evidence =
        evidenceRepo
            .findById(evidenceId)
            .orElseThrow(() -> new ResourceNotFoundException("Evidencia", evidenceId));
    if (!evidence.getEvaluation().getId().equals(eval.getId())) {
      throw new InvalidRequestException("La evidencia citada no pertenece a esta evaluación");
    }
    return evidence;
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
        o.getSubcategory() != null ? o.getSubcategory().getId() : null,
        o.getType().name(),
        o.getDescription(),
        o.getStatus().name(),
        author != null ? author.getId() : null,
        o.getCreatedAt(),
        author != null ? (author.getFirstName() + " " + author.getLastName()).trim() : null,
        o.getControl() != null ? o.getControl().getCode() : null,
        o.getSubcategory() != null ? o.getSubcategory().getCode() : null,
        o.getEvidence() != null ? o.getEvidence().getId() : null,
        o.getEvidence() != null ? o.getEvidence().getFileName() : null);
  }
}
