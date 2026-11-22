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
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.exception.TooManyRequestsException;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.AiJobAcceptedDto;
import uy.edu.prisma.web.dto.Dto.AiJobDto;
import uy.edu.prisma.web.dto.Dto.BatchItemDto;
import uy.edu.prisma.web.dto.Dto.GeneratedGuideDto;
import uy.edu.prisma.web.dto.Dto.GuideBatchAcceptedDto;
import uy.edu.prisma.web.dto.Dto.GuideBatchRequestDto;
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

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AiJobService.class);

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
    return accepted(createGuideJob(dto, AiJob.PRIORITY_INTERACTIVE, null, true));
  }

  /**
   * Lote de guías (F3): un trabajo {@code GUIDE} por control, con prioridad de lote (un pedido
   * interactivo pasa antes) y un {@code batchId} común para ver el avance y cancelar lo que siga en
   * cola. Un control que ya tiene un trabajo activo reutiliza ese trabajo (no se duplica). Topes:
   * {@code maxBatchSize} controles por lote y {@code maxPendingBatchPerOrg} trabajos de lote
   * activos por organización (aparte del tope de los pedidos sueltos).
   */
  public GuideBatchAcceptedDto enqueueGuideBatch(GuideBatchRequestDto dto) {
    List<GuideJobRequestDto> items = dto.items();
    if (items.size() > props.maxBatchSize()) {
      throw new InvalidRequestException(
          "Un lote admite hasta %d controles".formatted(props.maxBatchSize()));
    }
    UUID organizationId = resolveOrganization(items.get(0).evaluationId());
    UUID userId = currentUser.currentUserId();
    long pending =
        organizationId != null
            ? repo.countByOrganizationIdAndBatchIdIsNotNullAndStatusIn(organizationId, ACTIVE)
            : repo.countByOrganizationIdIsNullAndRequestedByAndBatchIdIsNotNullAndStatusIn(
                userId, ACTIVE);
    if (pending + items.size() > props.maxPendingBatchPerOrg()) {
      throw new TooManyRequestsException(
          "Hay demasiadas guías de lote en proceso (%d). Esperá a que terminen o cancelá el lote."
              .formatted(pending));
    }
    UUID batchId = UUID.randomUUID();
    List<BatchItemDto> jobs = new java.util.ArrayList<>();
    for (GuideJobRequestDto item : items) {
      AiJob job = createGuideJob(item, AiJob.PRIORITY_BATCH, batchId, false);
      jobs.add(new BatchItemDto(item.controlId(), job.getId(), job.getStatus().name()));
    }
    return new GuideBatchAcceptedDto(batchId, jobs);
  }

  /** Estado de varios trabajos de una vez (avance de un lote); los que ya no existen se omiten. */
  @Transactional
  public List<AiJobDto> getMany(List<UUID> ids) {
    List<AiJobDto> result = new java.util.ArrayList<>();
    for (UUID id : ids.stream().distinct().limit(100).toList()) {
      try {
        result.add(get(id));
      } catch (ResourceNotFoundException gone) {
        // venció o se borró: no rompe el seguimiento del resto
        log.debug("Trabajo de IA {} ya no existe: se omite del seguimiento", id);
      }
    }
    return result;
  }

  /** Cancela de un lote lo que sigue en cola; lo que ya corre termina. Devuelve cuántos. */
  @Transactional
  public int cancelBatch(UUID batchId) {
    List<AiJob> jobs = repo.findByBatchId(batchId);
    if (jobs.isEmpty()) {
      throw new ResourceNotFoundException("Lote de IA", batchId);
    }
    assertAccess(jobs.get(0));
    return repo.cancelQueuedOfBatch(batchId, OffsetDateTime.now());
  }

  /**
   * Crea (o reutiliza, si ya hay uno activo para lo mismo) el trabajo de una guía. Sin
   * {@code @Transactional} a propósito: la restricción única de trabajos activos puede saltar si
   * dos pedidos iguales llegan a la vez, y esa excepción dentro de una transacción dejaría el
   * pedido sin poder devolver el trabajo que ganó la carrera.
   */
  private AiJob createGuideJob(
      GuideJobRequestDto dto, int priority, UUID batchId, boolean enforceSingleLimit) {
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
      return existing;
    }
    if (enforceSingleLimit) {
      assertBelowPendingLimit(organizationId, userId);
    }

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
      return repo.saveAndFlush(
          AiJob.builder()
              .type(Type.GUIDE)
              .organizationId(organizationId)
              .requestedBy(userId)
              .priority(priority)
              .batchId(batchId)
              .params(params)
              .dedupKey(dedupKey)
              .maxAttempts(props.maxAttempts())
              .build());
    } catch (DataIntegrityViolationException race) {
      AiJob winner = findActive(dedupKey);
      if (winner != null) {
        return winner;
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
            ? repo.countByOrganizationIdAndTypeAndBatchIdIsNullAndStatusIn(
                organizationId, Type.GUIDE, ACTIVE)
            : repo.countByOrganizationIdIsNullAndRequestedByAndTypeAndBatchIdIsNullAndStatusIn(
                userId, Type.GUIDE, ACTIVE);
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
        guide,
        controlIdOf(job),
        job.getBatchId());
  }

  private static UUID controlIdOf(AiJob job) {
    Object raw = job.getParams() != null ? job.getParams().get("controlId") : null;
    try {
      return raw != null ? UUID.fromString(raw.toString()) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
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
