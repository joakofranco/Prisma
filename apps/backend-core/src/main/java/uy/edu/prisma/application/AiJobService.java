package uy.edu.prisma.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;
import uy.edu.prisma.domain.entity.AiJob.Type;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.exception.TooManyRequestsException;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.AiJobAcceptedDto;
import uy.edu.prisma.web.dto.Dto.AiJobDto;
import uy.edu.prisma.web.dto.Dto.GeneratedGuideDto;
import uy.edu.prisma.web.dto.Dto.GuideJobRequestDto;

/**
 * Lado API de la cola de IA: encolar, consultar, listar y cancelar. Quien ejecuta los trabajos es
 * {@link AiJobWorker}.
 *
 * <p>Cada consulta reverifica el acceso a la organización del trabajo (no alcanza con conocer el
 * id): el rol lo exige el controlador y la organización se valida acá.
 */
@Service
public class AiJobService {

  private static final Set<Status> ACTIVE = Set.of(Status.QUEUED, Status.RUNNING);
  private static final int LIST_LIMIT = 50;

  private final AiJobRepository repo;
  private final AiGeneratedGuideRepository guideRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationRepository evaluationRepo;
  private final CurrentUserService currentUser;
  private final AiJobProperties props;

  public AiJobService(
      AiJobRepository repo,
      AiGeneratedGuideRepository guideRepo,
      CatalogControlRepository controlRepo,
      EvaluationRepository evaluationRepo,
      CurrentUserService currentUser,
      AiJobProperties props) {
    this.repo = repo;
    this.guideRepo = guideRepo;
    this.controlRepo = controlRepo;
    this.evaluationRepo = evaluationRepo;
    this.currentUser = currentUser;
    this.props = props;
  }

  /**
   * Encola la generación de una guía. Responde enseguida (no espera al LLM). Idempotente: si ya hay
   * un trabajo activo para lo mismo (misma organización, control y niveles), devuelve ese.
   *
   * <p>Sin {@code @Transactional} a propósito: la restricción única de trabajos activos puede
   * saltar si dos pedidos iguales llegan a la vez, y esa excepción dentro de una transacción
   * dejaría el pedido sin poder devolver el trabajo que ganó la carrera.
   */
  public AiJobAcceptedDto enqueueGuide(GuideJobRequestDto dto) {
    CatalogControl control =
        controlRepo
            .findById(dto.controlId())
            .orElseThrow(() -> new ResourceNotFoundException("Control", dto.controlId()));
    UUID organizationId = resolveOrganization(dto.evaluationId());
    UUID userId = currentUser.currentUserId();

    String dedupKey =
        dedupKey(
            Type.GUIDE,
            organizationId,
            userId,
            dto.controlId(),
            dto.currentLevel(),
            dto.targetLevel());
    AiJob existing = findActive(dedupKey);
    if (existing != null) {
      return accepted(existing);
    }
    assertBelowPendingLimit(organizationId, userId);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("controlId", control.getId().toString());
    params.put("controlCode", control.getCode());
    params.put("description", control.getDescription());
    params.put("functionName", dto.functionName());
    params.put("categoryName", dto.categoryName());
    params.put("subcategoryName", dto.subcategoryName());
    params.put("currentLevel", dto.currentLevel());
    params.put("targetLevel", dto.targetLevel());
    try {
      AiJob saved =
          repo.saveAndFlush(
              AiJob.builder()
                  .type(Type.GUIDE)
                  .organizationId(organizationId)
                  .requestedBy(userId)
                  .priority(AiJob.PRIORITY_INTERACTIVE)
                  .params(params)
                  .dedupKey(dedupKey)
                  .maxAttempts(props.maxAttempts())
                  .build());
      return accepted(saved);
    } catch (DataIntegrityViolationException race) {
      AiJob winner = findActive(dedupKey);
      if (winner != null) {
        return accepted(winner);
      }
      throw race;
    }
  }

  @Transactional
  public AiJobDto get(UUID id) {
    AiJob job = load(id);
    assertAccess(job);
    GeneratedGuideDto guide = null;
    if (job.getStatus() == Status.SUCCEEDED && job.getResultRef() != null) {
      AiGeneratedGuide g = guideRepo.findById(job.getResultRef()).orElse(null);
      if (g != null) {
        markShown(g);
        guide = toDto(g);
      }
    }
    return toDto(job, guide);
  }

  /** Trabajos de quien consulta, del más nuevo al más viejo (para «Tareas de IA»). */
  @Transactional(readOnly = true)
  public List<AiJobDto> listMine() {
    UUID userId = currentUser.currentUserId();
    if (userId == null) {
      return List.of();
    }
    return repo
        .findByRequestedByOrderByCreatedAtDesc(userId, PageRequest.of(0, LIST_LIMIT))
        .stream()
        .map(job -> toDto(job, null))
        .toList();
  }

  /** Cancela un trabajo que sigue en cola. Uno que ya corre no se puede cortar. */
  @Transactional
  public AiJobDto cancel(UUID id) {
    AiJob job = load(id);
    assertAccess(job);
    if (job.getStatus() == Status.CANCELLED) {
      return toDto(job, null);
    }
    if (job.getStatus() != Status.QUEUED) {
      throw new ConflictException(
          job.getStatus() == Status.RUNNING
              ? "El trabajo ya se está ejecutando y no se puede cancelar"
              : "El trabajo ya terminó");
    }
    // Condicional en la base: si otra réplica lo reclamó justo ahora, no lo pisa.
    if (repo.cancelIfQueued(id, OffsetDateTime.now()) == 0) {
      throw new ConflictException("El trabajo ya se está ejecutando y no se puede cancelar");
    }
    return toDto(load(id), null);
  }

  // ---------------------------------------------------------------- internos

  private UUID resolveOrganization(UUID evaluationId) {
    if (evaluationId != null) {
      UUID orgId =
          evaluationRepo
              .findOrganizationId(evaluationId)
              .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
      currentUser.assertOrganizationAccess(orgId);
      return orgId;
    }
    return currentUser.currentTenantId();
  }

  private AiJob findActive(String dedupKey) {
    return repo.findFirstByDedupKeyAndStatusIn(dedupKey, ACTIVE).orElse(null);
  }

  private void assertBelowPendingLimit(UUID organizationId, UUID userId) {
    long pending =
        organizationId != null
            ? repo.countByOrganizationIdAndStatusIn(organizationId, ACTIVE)
            : repo.countByOrganizationIdIsNullAndRequestedByAndStatusIn(userId, ACTIVE);
    if (pending >= props.maxPendingPerOrg()) {
      throw new TooManyRequestsException(
          "Hay demasiadas guías de IA en proceso (%d). Esperá a que terminen o cancelá alguna."
              .formatted(pending));
    }
  }

  private AiJob load(UUID id) {
    return repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Trabajo de IA", id));
  }

  private void assertAccess(AiJob job) {
    if (job.getOrganizationId() != null) {
      currentUser.assertOrganizationAccess(job.getOrganizationId());
      return;
    }
    if (!currentUser.isPrismaAdmin() && !job.getRequestedBy().equals(currentUser.currentUserId())) {
      currentUser.assertOrganizationAccess(null);
    }
  }

  /** La primera vez que quien la pidió la consulta, la guía pasa de GENERATED a SHOWN. */
  private void markShown(AiGeneratedGuide guide) {
    UUID me = currentUser.currentUserId();
    if (guide.getStatus() == AiGeneratedGuide.Status.GENERATED
        && me != null
        && me.equals(guide.getRequestedBy())) {
      guide.setStatus(AiGeneratedGuide.Status.SHOWN);
      guide.setShownAt(OffsetDateTime.now());
      guideRepo.save(guide);
    }
  }

  private static AiJobAcceptedDto accepted(AiJob job) {
    return new AiJobAcceptedDto(job.getId(), job.getStatus().name());
  }

  private AiJobDto toDto(AiJob job, GeneratedGuideDto guide) {
    Integer position = null;
    if (job.getStatus() == Status.QUEUED) {
      position = (int) repo.countAhead(job.getPriority(), job.getCreatedAt()) + 1;
    }
    return new AiJobDto(
        job.getId(),
        job.getType().name(),
        job.getStatus().name(),
        position,
        job.getAttempts(),
        job.getMaxAttempts(),
        job.getErrorCode(),
        job.getErrorMessage(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getFinishedAt(),
        guide);
  }

  private static GeneratedGuideDto toDto(AiGeneratedGuide g) {
    return new GeneratedGuideDto(
        g.getId(), g.getControl().getId(), g.getSummary(), List.copyOf(g.getTips()), g.getModel());
  }

  static String dedupKey(
      Type type,
      UUID organizationId,
      UUID userId,
      UUID controlId,
      int currentLevel,
      int targetLevel) {
    String scope = organizationId != null ? organizationId.toString() : "user:" + userId;
    String raw = type + "|" + scope + "|" + controlId + "|" + currentLevel + "|" + targetLevel;
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}
