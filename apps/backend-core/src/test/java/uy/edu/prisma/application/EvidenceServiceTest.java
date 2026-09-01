package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import uy.edu.prisma.config.MinioConfig.MinioProperties;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.DownloadUrlDto;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceServiceTest {

  @Mock private EvidenceRepository evidenceRepo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private MinioClient minioClient;
  @Mock private MinioClient minioPublicClient;
  @Mock private CurrentUserService currentUser;
  @Mock private AiEvidenceClient aiClient;

  private final MinioProperties properties =
      new MinioProperties(
          "http://minio:9000", "http://localhost:9001", "ak", "sk", "prisma-evidences");

  private EvidenceService service;
  private Evaluation eval;
  private Evidence evidence;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    service =
        new EvidenceService(
            evidenceRepo,
            evaluationRepo,
            controlRepo,
            minioClient,
            minioPublicClient,
            properties,
            currentUser,
            aiClient);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .build();
    evidence =
        Evidence.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .fileName("doc.pdf")
            .fileSize(1024L)
            .fileType("application/pdf")
            .storageKey("evidence/" + UUID.randomUUID())
            .description("d")
            .uploadedAt(OffsetDateTime.now())
            .build();
  }

  /** Simula lo que hace Hibernate en un persist() real: asigna el id si aún es null. */
  private static Evidence simulatePersist(InvocationOnMock inv) {
    Evidence e = inv.getArgument(0);
    if (e.getId() == null) {
      e.setId(UUID.randomUUID());
    }
    return e;
  }

  @Test
  void listByEvaluationReturnsDtosWithUrls() throws Exception {
    String url = "http://localhost:9000/prisma-evidences/evidence/x?X-Amz-Signature=abc";
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()))
        .thenReturn(List.of(evidence));
    // El link que ve el frontend se firma con el cliente PÚBLICO (ver comentario en
    // EvidenceService.publicPresignedUrl), no con el interno.
    when(minioPublicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn(url);

    List<EvidenceDto> result = service.listByEvaluation(eval.getId());

    assertEquals(1, result.size());
    EvidenceDto dto = result.get(0);
    assertEquals(evidence.getId(), dto.id());
    assertEquals("doc.pdf", dto.fileName());
    assertEquals("d", dto.description());
    assertEquals(url, dto.url());
  }

  @Test
  void listReturnsNullUrlWhenPresignFails() throws Exception {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()))
        .thenReturn(List.of(evidence));
    when(minioPublicClient.getPresignedObjectUrl(any()))
        .thenThrow(new RuntimeException("minio down"));

    List<EvidenceDto> result = service.listByEvaluation(eval.getId());

    assertNull(result.get(0).url());
  }

  @Test
  void uploadPersistsEvidenceAndFile() throws Exception {
    UUID evalId = eval.getId();
    MockMultipartFile file =
        new MockMultipartFile("file", "reporte.pdf", "application/pdf", new byte[] {1, 2, 3});
    when(evaluationRepo.findById(evalId)).thenReturn(Optional.of(eval));
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    UploadEvidenceResponseDto result = service.upload(evalId, null, "respaldo", file);

    assertNotNull(result.id());
    assertEquals("reporte.pdf", result.fileName());
    assertEquals(3L, result.fileSize());
    verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    // Un solo save(): el flag aiIndexed se fija sobre la misma instancia administrada que
    // devuelve este save(), sin volver a llamar a save() (ver comentario en EvidenceService).
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertTrue(captor.getValue().getStorageKey().startsWith("evidence/"));
  }

  @Test
  void uploadCreatesBucketWhenMissing() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    verify(minioClient, times(1)).makeBucket(any());
    verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
  }

  @Test
  void uploadResolvesAssociatedControl() throws Exception {
    UUID controlId = UUID.randomUUID();
    CatalogControl control = CatalogControl.builder().id(controlId).code("PR.AA-01").build();
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.of(control));
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), controlId, null, file);

    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo, atLeastOnce()).save(captor.capture());
    assertEquals(controlId, captor.getAllValues().get(0).getControl().getId());
  }

  @Test
  void uploadThrowsWhenControlMissing() {
    UUID controlId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> service.upload(eval.getId(), controlId, null, file));
  }

  @Test
  void uploadSucceedsEvenWhenAiIndexingFails() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://minio/signed");
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(false);

    UploadEvidenceResponseDto result = service.upload(eval.getId(), null, null, file);

    assertNotNull(result.id());
    verify(aiClient, times(1)).ingest(any(), any(), any(), isNull(), any(), any());
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertFalse(captor.getValue().isAiIndexed());
  }

  @Test
  void uploadRejectsEmptyFile() {
    MockMultipartFile file = new MockMultipartFile("file", "v.x", null, new byte[0]);

    assertThrows(
        IllegalArgumentException.class, () -> service.upload(eval.getId(), null, null, file));
  }

  @Test
  void uploadRejectsWhenEvaluationIsUnderAudit() throws Exception {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.upload(eval.getId(), null, null, file));
    verify(minioClient, never()).putObject(any());
    verify(evidenceRepo, never()).save(any());
  }

  @Test
  void uploadThrowsWhenEvaluationMissing() {
    UUID id = UUID.randomUUID();
    when(evaluationRepo.findById(id)).thenReturn(Optional.empty());
    MockMultipartFile file = new MockMultipartFile("file", "v.x", null, new byte[] {1});

    assertThrows(ResourceNotFoundException.class, () -> service.upload(id, null, null, file));
  }

  @Test
  void reindexUpdatesAiIndexedFlag() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://minio/signed");
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    EvidenceDto dto = service.reindex(evidence.getId());

    assertTrue(dto.aiIndexed());
    verify(aiClient, times(1)).ingest(any(), any(), any(), any(), any(), any());
  }

  @Test
  void reindexRejectsWhenEvaluationIsUnderAudit() throws Exception {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.reindex(evidence.getId()));
    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
  }

  @Test
  void getCitationsDelegatesToAiClientWithControlQuery() {
    UUID controlId = UUID.randomUUID();
    CatalogControl control =
        CatalogControl.builder().id(controlId).code("PR.AA-01").description("Control X").build();
    List<EvidenceCitationDto> expected =
        List.of(new EvidenceCitationDto(UUID.randomUUID(), "a.pdf", "Página 2", "texto", 0.9));
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.of(control));
    when(aiClient.citations(
            eq(eval.getOrganization().getId()), eq(eval.getId()), eq(controlId), any()))
        .thenReturn(expected);

    List<EvidenceCitationDto> result = service.getCitations(eval.getId(), controlId);

    assertEquals(expected, result);
  }

  @Test
  void getCitationsThrowsWhenControlMissing() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> service.getCitations(eval.getId(), controlId));
  }

  @Test
  void deleteRemovesObjectAndRecord() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    service.delete(evidence.getId());

    verify(minioClient, times(1)).removeObject(any(RemoveObjectArgs.class));
    verify(aiClient, times(1)).deleteEvidence(eval.getOrganization().getId(), evidence.getId());
    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteRejectsWhenEvaluationIsUnderAudit() throws Exception {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.delete(evidence.getId()));
    verify(evidenceRepo, never()).delete(any());
    verify(minioClient, never()).removeObject(any());
  }

  @Test
  void deleteIgnoresStorageErrors() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    doThrow(new RuntimeException("minio")).when(minioClient).removeObject(any());

    service.delete(evidence.getId());

    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evidenceRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
  }

  @Test
  void generateDownloadUrlReturnsUrl() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(minioPublicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://signed/url");

    DownloadUrlDto dto = service.generateDownloadUrl(evidence.getId());

    assertEquals("http://signed/url", dto.url());
  }

  @Test
  void uploadIndexesInAiUsingTheInternalEndpointNotThePublicOne() throws Exception {
    // La URL que backend-ai usa para bajar el archivo es server-to-server (dentro de la red de
    // contenedores): tiene que salir del cliente INTERNO, nunca del público (que apunta a un host
    // como localhost:9001 que solo tiene sentido desde el navegador del usuario).
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://minio:9000/internal-signed");
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);

    service.upload(eval.getId(), null, null, file);

    verify(aiClient, times(1))
        .ingest(any(), any(), any(), isNull(), any(), eq("http://minio:9000/internal-signed"));
    verify(minioPublicClient, never()).getPresignedObjectUrl(any());
  }

  @Test
  void generateDownloadUrlThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evidenceRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.generateDownloadUrl(id));
  }
}
