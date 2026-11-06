package uy.edu.prisma.application;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import uy.edu.prisma.config.ObjectStorageConfig.StorageProperties;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@Service
@Transactional
public class EvidenceService {

  private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
  private static final Duration PRESIGNED_EXPIRY = Duration.ofSeconds(900);

  private final EvidenceRepository evidenceRepo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final S3Client s3Client;
  private final S3Presigner internalPresigner;
  private final StorageProperties storageProperties;
  private final CurrentUserService currentUser;
  private final AiEvidenceClient aiClient;
  private final EvidenceFileValidator fileValidator;

  public EvidenceService(
      EvidenceRepository evidenceRepo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      S3Client s3Client,
      @Qualifier("internalPresigner") S3Presigner internalPresigner,
      StorageProperties storageProperties,
      CurrentUserService currentUser,
      AiEvidenceClient aiClient,
      EvidenceFileValidator fileValidator) {
    this.evidenceRepo = evidenceRepo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.s3Client = s3Client;
    this.internalPresigner = internalPresigner;
    this.storageProperties = storageProperties;
    this.currentUser = currentUser;
    this.aiClient = aiClient;
    this.fileValidator = fileValidator;
  }

  @Transactional(readOnly = true)
  public List<EvidenceDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    // El listado no entrega enlaces de descarga: el archivo solo sale por download(), que valida
    // el JWT, la organización y el rol en cada descarga (PRI-286).
    return evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(evaluationId).stream()
        .map(this::toDto)
        .toList();
  }

  /** Extensiones de archivo que acepta la carga de evidencias (RF-EVI-02), sin punto. */
  public List<String> allowedExtensions() {
    return fileValidator.allowedExtensions();
  }

  /** Tamaño máximo de una evidencia, en bytes (RF-EVI-04). */
  public long maxSizeBytes() {
    return fileValidator.maxSizeBytes();
  }

  /** Roles que pueden descargar evidencias: PRISMA_ADMIN, ORG_RESPONSIBLE y AUDITOR (PRI-286). */
  private boolean canDownload() {
    return currentUser.hasRole(UserRole.PRISMA_ADMIN)
        || currentUser.hasRole(UserRole.ORG_RESPONSIBLE)
        || currentUser.hasRole(UserRole.AUDITOR);
  }

  public UploadEvidenceResponseDto upload(
      UUID evaluationId, UUID controlId, String description, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("El archivo es obligatorio");
    }
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    assertSelfAssessmentEditable(eval, "subir evidencia");
    CatalogControl control =
        controlId != null
            ? controlRepo
                .findById(controlId)
                .orElseThrow(() -> new ResourceNotFoundException("Control", controlId))
            : null;
    // RF-EVI-04: el tamaño se mira primero, antes de leer el contenido (un ZIP grande tarda en
    // recorrerse) y antes de tocar el almacenamiento.
    fileValidator.assertWithinSizeLimit(file);
    // RF-EVI-03: antes de tocar el almacenamiento, se valida que la firma binaria real coincida
    // con la extensión declarada; se persiste el tipo detectado, no el Content-Type del cliente.
    String detectedType = fileValidator.validate(file);

    // Solo para la storage key en el bucket -- no se asigna como @Id de la entidad: Evidence usa
    // @GeneratedValue(strategy = GenerationType.UUID), y si se le asigna manualmente un id antes
    // de la primera persistencia, Spring Data JPA decide (via isNew()) llamar a
    // entityManager.merge() en vez de persist() para el primer save(), lo cual revienta con
    // StaleObjectStateException porque Hibernate trata la fila (inexistente) como "detached".
    String storageKey = "evidence/" + UUID.randomUUID();
    ensureBucket();
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw new RuntimeException("No se pudo leer el archivo de evidencia", e);
    }
    // RF-EVI-05: hash SHA-256 del contenido real recibido, no de metadata declarada por el
    // cliente, para poder detectar alteraciones posteriores del archivo en el almacenamiento.
    String fileHash = sha256Hex(content);
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(storageProperties.bucket())
              .key(storageKey)
              .contentType(detectedType)
              .build(),
          RequestBody.fromBytes(content));
    } catch (Exception e) {
      throw new RuntimeException("No se pudo guardar el archivo de evidencia", e);
    }

    Evidence evidence =
        Evidence.builder()
            .evaluation(eval)
            .control(control)
            .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "evidence")
            .fileSize(file.getSize())
            .fileType(detectedType)
            .storageKey(storageKey)
            .fileHash(fileHash)
            .uploadedBy(currentUser.currentUser().orElse(null))
            .description(description)
            .build();
    Evidence saved = evidenceRepo.save(evidence);

    // Best-effort: la evidencia ya quedó guardada aunque esto falle o backend-ai esté caído.
    // No se llama a evidenceRepo.save(saved) de nuevo: `saved` ya es la instancia administrada
    // por el EntityManager de esta transacción (Evidence no tiene @Version), así que un segundo
    // merge() sobre el mismo id revienta con StaleObjectStateException. El dirty-checking de JPA
    // ya persiste este cambio al hacer commit.
    // ZIP e imágenes no se indexan (backend-ai no los lee): quedan con aiIndexed=false a propósito.
    saved.setAiIndexed(EvidenceFileValidator.isIndexable(detectedType) && indexInAi(saved));

    return new UploadEvidenceResponseDto(saved.getId(), saved.getFileName(), saved.getFileSize());
  }

  /** Reintenta la indexación en backend-ai de una evidencia ya subida (idempotente). */
  public EvidenceDto reindex(UUID id) {
    Evidence evidence =
        evidenceRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evidencia", id));
    currentUser.assertOrganizationAccess(evidence.getEvaluation().getOrganization().getId());
    assertSelfAssessmentEditable(evidence.getEvaluation(), "reindexar evidencia");
    // `evidence` ya está administrada (viene de findById en esta misma transacción): alcanza con
    // mutarla, sin volver a llamar a save() -- ver comentario en upload().
    // Un formato que backend-ai no indexa (ZIP, imágenes) no tiene nada que reintentar.
    if (!EvidenceFileValidator.isIndexable(evidence.getFileType())) {
      return toDto(evidence);
    }
    evidence.setAiIndexed(indexInAi(evidence));
    return toDto(evidence);
  }

  // Igual que EvaluationService.saveResponse: una vez enviada a auditoría (READY_FOR_AUDIT en
  // adelante), la organización no puede seguir tocando la evidencia -- ni subir, ni borrar, ni
  // reindexar -- mientras un auditor la está revisando (o después de que ya se aprobó/archivó).
  // Antes ninguna de las tres operaciones chequeaba esto.
  private void assertSelfAssessmentEditable(Evaluation eval, String action) {
    if (!eval.isSelfAssessmentEditable()) {
      throw new InvalidRequestException(
          "No se puede "
              + action
              + ": la evaluación está en estado "
              + eval.getStatus()
              + ", que ya no admite edición");
    }
  }

  private boolean indexInAi(Evidence evidence) {
    // A diferencia de la URL que va al frontend, esta la consume backend-ai del lado del
    // servidor (dentro de la misma red de contenedores) -- tiene que ser el endpoint INTERNO
    // (presigner interno), no el público: "localhost:9001" no significa nada dentro del contenedor
    // de
    // backend-ai.
    String fileUrl;
    try {
      fileUrl = presignedUrlOrThrow(evidence);
    } catch (Exception e) {
      return false;
    }
    UUID organizationId = evidence.getEvaluation().getOrganization().getId();
    UUID controlId = evidence.getControl() != null ? evidence.getControl().getId() : null;
    return aiClient.ingest(
        organizationId,
        evidence.getEvaluation().getId(),
        evidence.getId(),
        controlId,
        evidence.getFileName(),
        fileUrl);
  }

  /**
   * Citas de evidencia relevantes para un control, dentro de una evaluación. Pura recuperación (sin
   * veredicto): el auditor decide qué hacer con cada fragmento devuelto.
   */
  @Transactional(readOnly = true)
  public List<EvidenceCitationDto> getCitations(UUID evaluationId, UUID controlId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    CatalogControl control =
        controlRepo
            .findById(controlId)
            .orElseThrow(() -> new ResourceNotFoundException("Control", controlId));
    String query = control.getCode() + ": " + control.getDescription();
    return aiClient.citations(eval.getOrganization().getId(), evaluationId, controlId, query);
  }

  public void delete(UUID id) {
    Evidence evidence =
        evidenceRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evidencia", id));
    currentUser.assertOrganizationAccess(evidence.getEvaluation().getOrganization().getId());
    assertCanDelete(evidence);
    assertSelfAssessmentEditable(evidence.getEvaluation(), "eliminar evidencia");
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder()
              .bucket(storageProperties.bucket())
              .key(evidence.getStorageKey())
              .build());
    } catch (Exception e) {
      // best-effort: si ya no existe en el bucket, igual se elimina el registro; se deja constancia
      // en el log para poder detectar si el almacenamiento empieza a fallar de verdad.
      log.warn(
          "No se pudo borrar el objeto {} del almacenamiento al eliminar la evidencia {}",
          evidence.getStorageKey(),
          id,
          e);
    }
    aiClient.deleteEvidence(evidence.getEvaluation().getOrganization().getId(), evidence.getId());
    evidenceRepo.delete(evidence);
  }

  /**
   * Archivo de una evidencia listo para streaming. El backend lo sirve en cada descarga, en lugar
   * de entregar un enlace pre-firmado de RustFS: así no existe un enlace que se pueda compartir y
   * cada descarga exige JWT, acceso a la organización y rol (PRI-286). El llamador debe cerrar
   * {@code content}.
   */
  public record EvidenceFile(String fileName, String contentType, long size, InputStream content) {}

  @Transactional(readOnly = true)
  public EvidenceFile download(UUID id) {
    Evidence evidence =
        evidenceRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evidencia", id));
    currentUser.assertOrganizationAccess(evidence.getEvaluation().getOrganization().getId());
    // Defensa en profundidad: el controlador ya exige el rol con @PreAuthorize.
    if (!canDownload()) {
      throw new AccessDeniedException("Su rol no puede descargar evidencias");
    }
    try {
      ResponseInputStream<GetObjectResponse> object =
          s3Client.getObject(
              GetObjectRequest.builder()
                  .bucket(storageProperties.bucket())
                  .key(evidence.getStorageKey())
                  .build());
      return new EvidenceFile(
          evidence.getFileName(),
          evidence.getFileType() != null ? evidence.getFileType() : "application/octet-stream",
          object.response().contentLength() != null
              ? object.response().contentLength()
              : evidence.getFileSize(),
          object);
    } catch (Exception e) {
      throw new RuntimeException("No se pudo leer el archivo de evidencia", e);
    }
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 es parte de todo JDK conforme (JLS/JCA); esto nunca debería ocurrir en runtime.
      throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
    }
  }

  // RF-EVI-08: solo puede borrar una evidencia quien la subió o el responsable de la
  // organización (PRISMA_ADMIN y el evaluador general quedan afuera de esta restricción porque ya
  // tienen acceso irrestricto a todas las organizaciones vía hasAllOrganizationsAccess -- ver
  // CurrentUserService). assertOrganizationAccess ya corrió antes y descarta cualquier otra
  // organización.
  private void assertCanDelete(Evidence evidence) {
    if (currentUser.hasAllOrganizationsAccess()) {
      return;
    }
    boolean isResponsible = currentUser.hasRole(UserRole.ORG_RESPONSIBLE);
    UUID currentUserId = currentUser.currentUserId();
    boolean isUploader =
        currentUserId != null
            && evidence.getUploadedBy() != null
            && currentUserId.equals(evidence.getUploadedBy().getId());
    if (!isResponsible && !isUploader) {
      throw new AccessDeniedException(
          "Solo quien subió la evidencia o el responsable de la organización puede eliminarla");
    }
  }

  private void ensureBucket() {
    try {
      try {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(storageProperties.bucket()).build());
      } catch (NoSuchBucketException notFound) {
        s3Client.createBucket(
            CreateBucketRequest.builder().bucket(storageProperties.bucket()).build());
      }
    } catch (Exception e) {
      throw new RuntimeException("No se pudo verificar/crear el bucket de almacenamiento", e);
    }
  }

  // Usada por indexInAi: URL interna, para que backend-ai (server-side) baje el archivo.
  private String presignedUrlOrThrow(Evidence e) throws Exception {
    return presign(internalPresigner, e);
  }

  private String presign(S3Presigner presigner, Evidence e) {
    return presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_EXPIRY)
                .getObjectRequest(
                    GetObjectRequest.builder()
                        .bucket(storageProperties.bucket())
                        .key(e.getStorageKey())
                        .build())
                .build())
        .url()
        .toString();
  }

  private EvidenceDto toDto(Evidence e) {
    return new EvidenceDto(
        e.getId(),
        e.getEvaluation().getId(),
        e.getControl() != null ? e.getControl().getId() : null,
        e.getFileName(),
        e.getFileSize(),
        e.getFileType(),
        e.getFileHash(),
        e.getDescription(),
        e.getUploadedBy() != null ? e.getUploadedBy().getId() : null,
        e.getUploadedAt(),
        e.isAiIndexed());
  }
}
