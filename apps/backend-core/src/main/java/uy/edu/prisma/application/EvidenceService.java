package uy.edu.prisma.application;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.config.MinioConfig.MinioProperties;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.DownloadUrlDto;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@Service
@Transactional
public class EvidenceService {

  private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
  private static final int PRESIGNED_EXPIRY_SECONDS = 900;

  private final EvidenceRepository evidenceRepo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogControlRepository controlRepo;
  private final MinioClient minioClient;
  private final MinioClient minioPublicClient;
  private final MinioProperties minioProperties;
  private final CurrentUserService currentUser;
  private final AiEvidenceClient aiClient;

  public EvidenceService(
      EvidenceRepository evidenceRepo,
      EvaluationRepository evaluationRepo,
      CatalogControlRepository controlRepo,
      MinioClient minioClient,
      @Qualifier("minioPublicClient") MinioClient minioPublicClient,
      MinioProperties minioProperties,
      CurrentUserService currentUser,
      AiEvidenceClient aiClient) {
    this.evidenceRepo = evidenceRepo;
    this.evaluationRepo = evaluationRepo;
    this.controlRepo = controlRepo;
    this.minioClient = minioClient;
    this.minioPublicClient = minioPublicClient;
    this.minioProperties = minioProperties;
    this.currentUser = currentUser;
    this.aiClient = aiClient;
  }

  @Transactional(readOnly = true)
  public List<EvidenceDto> listByEvaluation(UUID evaluationId) {
    Evaluation eval =
        evaluationRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(evaluationId).stream()
        .map(e -> toDto(e, publicPresignedUrl(e)))
        .toList();
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

    // Solo para la storage key en MinIO -- no se asigna como @Id de la entidad: Evidence usa
    // @GeneratedValue(strategy = GenerationType.UUID), y si se le asigna manualmente un id antes
    // de la primera persistencia, Spring Data JPA decide (via isNew()) llamar a
    // entityManager.merge() en vez de persist() para el primer save(), lo cual revienta con
    // StaleObjectStateException porque Hibernate trata la fila (inexistente) como "detached".
    String storageKey = "evidence/" + UUID.randomUUID();
    ensureBucket();
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(minioProperties.bucket()).object(storageKey).stream(
                  file.getInputStream(), file.getSize(), -1)
              .contentType(
                  file.getContentType() != null
                      ? file.getContentType()
                      : "application/octet-stream")
              .build());
    } catch (Exception e) {
      throw new RuntimeException("No se pudo guardar el archivo de evidencia", e);
    }

    Evidence evidence =
        Evidence.builder()
            .evaluation(eval)
            .control(control)
            .fileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "evidence")
            .fileSize(file.getSize())
            .fileType(file.getContentType())
            .storageKey(storageKey)
            .uploadedBy(currentUser.currentUser().orElse(null))
            .description(description)
            .build();
    Evidence saved = evidenceRepo.save(evidence);

    // Best-effort: la evidencia ya quedó guardada aunque esto falle o backend-ai esté caído.
    // No se llama a evidenceRepo.save(saved) de nuevo: `saved` ya es la instancia administrada
    // por el EntityManager de esta transacción (Evidence no tiene @Version), así que un segundo
    // merge() sobre el mismo id revienta con StaleObjectStateException. El dirty-checking de JPA
    // ya persiste este cambio al hacer commit.
    saved.setAiIndexed(indexInAi(saved));

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
    evidence.setAiIndexed(indexInAi(evidence));
    return toDto(evidence, publicPresignedUrl(evidence));
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
    // (minioClient), no el público: "localhost:9001" no significa nada dentro del contenedor de
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
    assertSelfAssessmentEditable(evidence.getEvaluation(), "eliminar evidencia");
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(minioProperties.bucket())
              .object(evidence.getStorageKey())
              .build());
    } catch (Exception e) {
      // best-effort: si ya no existe en MinIO, igual se elimina el registro; se deja constancia
      // en el log para poder detectar si MinIO empieza a fallar de verdad.
      log.warn(
          "No se pudo borrar el objeto {} de MinIO al eliminar la evidencia {}",
          evidence.getStorageKey(),
          id,
          e);
    }
    aiClient.deleteEvidence(evidence.getEvaluation().getOrganization().getId(), evidence.getId());
    evidenceRepo.delete(evidence);
  }

  @Transactional(readOnly = true)
  public DownloadUrlDto generateDownloadUrl(UUID id) {
    Evidence evidence =
        evidenceRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evidencia", id));
    currentUser.assertOrganizationAccess(evidence.getEvaluation().getOrganization().getId());
    try {
      String url =
          minioPublicClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.GET)
                  .bucket(minioProperties.bucket())
                  .object(evidence.getStorageKey())
                  .expiry(PRESIGNED_EXPIRY_SECONDS)
                  .build());
      return new DownloadUrlDto(url);
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar la URL de descarga", e);
    }
  }

  private void ensureBucket() {
    try {
      boolean exists =
          minioClient.bucketExists(
              BucketExistsArgs.builder().bucket(minioProperties.bucket()).build());
      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.bucket()).build());
      }
    } catch (Exception e) {
      throw new RuntimeException("No se pudo verificar/crear el bucket de almacenamiento", e);
    }
  }

  // Usada por indexInAi: URL interna, para que backend-ai (server-side) baje el archivo.
  private String presignedUrlOrThrow(Evidence e) throws Exception {
    return minioClient.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
            .method(Method.GET)
            .bucket(minioProperties.bucket())
            .object(e.getStorageKey())
            .expiry(PRESIGNED_EXPIRY_SECONDS)
            .build());
  }

  // Usada en los DTOs que devuelve la API: URL pública, para que el navegador del usuario pueda
  // bajar el archivo directamente. Antes se reusaba presignedUrlOrThrow (endpoint interno) acá
  // también, y el link de "Descargar" del frontend quedaba con un host que ningún navegador podía
  // resolver (ver MinioConfig).
  private String publicPresignedUrl(Evidence e) {
    try {
      return minioPublicClient.getPresignedObjectUrl(
          GetPresignedObjectUrlArgs.builder()
              .method(Method.GET)
              .bucket(minioProperties.bucket())
              .object(e.getStorageKey())
              .expiry(PRESIGNED_EXPIRY_SECONDS)
              .build());
    } catch (Exception ex) {
      log.warn("No se pudo firmar la URL pública de descarga para la evidencia {}", e.getId(), ex);
      return null;
    }
  }

  private EvidenceDto toDto(Evidence e, String url) {
    return new EvidenceDto(
        e.getId(),
        e.getEvaluation().getId(),
        e.getControl() != null ? e.getControl().getId() : null,
        e.getFileName(),
        e.getFileSize(),
        e.getFileType(),
        e.getDescription(),
        e.getUploadedBy() != null ? e.getUploadedBy().getId() : null,
        e.getUploadedAt(),
        url,
        e.isAiIndexed());
  }
}
