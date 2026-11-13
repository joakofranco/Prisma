package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
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
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import uy.edu.prisma.config.ObjectStorageConfig.StorageProperties;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.MalwareDetectedException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.ClamAvClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;
import uy.edu.prisma.web.dto.Dto.EvidenceDto;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceServiceTest {

  @Mock private EvidenceRepository evidenceRepo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private S3Client s3Client;
  @Mock private S3Presigner internalPresigner;
  @Mock private CurrentUserService currentUser;
  @Mock private AiEvidenceClient aiClient;
  @Mock private ClamAvClient clamAv;
  @Mock private AuditLogService auditLog;

  private final StorageProperties properties =
      new StorageProperties(
          "http://rustfs:9000", "http://localhost:9001", "ak", "sk", "prisma-evidences");

  private EvidenceService service;
  private Evaluation eval;
  private Evidence evidence;

  @BeforeEach
  void setUp() {
    when(currentUser.isGlobalRole()).thenReturn(true);
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(true);
    // Admin-bypass por defecto para no tener que stubear la autorización fina de delete()
    // (ver assertCanDelete) en cada test que no la ejercita específicamente.
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.CLEAN, null));
    service =
        new EvidenceService(
            evidenceRepo,
            evaluationRepo,
            controlRepo,
            s3Client,
            internalPresigner,
            properties,
            currentUser,
            aiClient,
            EvidenceFileValidatorTest.validatorFor(EvidenceFileValidatorTest.DEFAULT_FORMATS),
            clamAv,
            auditLog);
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

  /** Respuesta de firma con la URL indicada (el presigner real firma offline, sin red). */
  private static PresignedGetObjectRequest signed(String url) throws Exception {
    PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
    when(presigned.url()).thenReturn(URI.create(url).toURL());
    return presigned;
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
  void listByEvaluationReturnsDtosWithoutDownloadLinks() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()))
        .thenReturn(List.of(evidence));

    List<EvidenceDto> result = service.listByEvaluation(eval.getId());

    assertEquals(1, result.size());
    EvidenceDto dto = result.get(0);
    assertEquals(evidence.getId(), dto.id());
    assertEquals("doc.pdf", dto.fileName());
    assertEquals("d", dto.description());
    // El listado nunca firma enlaces: el archivo solo sale por download().
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
    verify(internalPresigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void uploadPersistsEvidenceAndFile() throws Exception {
    UUID evalId = eval.getId();
    MockMultipartFile file =
        new MockMultipartFile("file", "reporte.pdf", "application/pdf", "%PDF-".getBytes());
    when(evaluationRepo.findById(evalId)).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    UploadEvidenceResponseDto result = service.upload(evalId, null, "respaldo", file);

    assertNotNull(result.id());
    assertEquals("reporte.pdf", result.fileName());
    assertEquals(5L, result.fileSize());
    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    // Un solo save(): el flag aiIndexed se fija sobre la misma instancia administrada que
    // devuelve este save(), sin volver a llamar a save() (ver comentario en EvidenceService).
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertTrue(captor.getValue().getStorageKey().startsWith("evidence/"));
  }

  @Test
  void uploadComputesSha256HashOfFileContent() throws Exception {
    // Bytes con magic bytes reales de PDF ("%PDF-1.4"): con RF-EVI-03 (validacion de magic bytes)
    // ya integrado, un contenido arbitrario como {1, 2, 3} queda rechazado antes de llegar a
    // calcular el hash. SHA-256 de "%PDF-1.4", calculado independientemente (RF-EVI-05).
    String expectedHash = "e16fa5d9b51928755db85b917f0297babaf22c7a47e97d9212adab56e61ba04e";
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "reporte.pdf",
            "application/pdf",
            "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertEquals(expectedHash, captor.getValue().getFileHash());
  }

  @Test
  void uploadWithoutControlLeavesEvidenceUnassociated() throws Exception {
    // Con magic bytes de PDF reales: la validacion de contenido (RF-EVI-03) rechaza otros bytes.
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "acta.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo).save(captor.capture());
    assertNull(captor.getValue().getControl());
    verify(controlRepo, never()).findById(any());
  }

  @Test
  void uploadCreatesBucketWhenMissing() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(NoSuchBucketException.builder().build());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    verify(s3Client, times(1)).createBucket(any(CreateBucketRequest.class));
    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void uploadResolvesAssociatedControl() throws Exception {
    UUID controlId = UUID.randomUUID();
    CatalogControl control = CatalogControl.builder().id(controlId).code("PR.AA-01").build();
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.of(control));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
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
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(internalPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenAnswer(inv -> signed("http://rustfs/signed"));
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
  void uploadRejectsFileWhoseMagicBytesDoNotMatchExtension() {
    // Ejecutable de Windows (MZ) renombrado a .pdf
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "malware.pdf", "application/pdf", new byte[] {0x4D, 0x5A, 0x00, 0x01});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.upload(eval.getId(), null, null, file));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(evidenceRepo, never()).save(any());
  }

  @Test
  void uploadRejectsDisallowedExtension() {
    MockMultipartFile file =
        new MockMultipartFile("file", "script.exe", null, new byte[] {0x4D, 0x5A});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.upload(eval.getId(), null, null, file));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void uploadStoresDetectedTypeInsteadOfDeclaredContentType() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "reporte.pdf", "text/html", "%PDF-1.7".getBytes());
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertEquals("application/pdf", captor.getValue().getFileType());
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
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
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
    when(internalPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenAnswer(inv -> signed("http://rustfs/signed"));
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    EvidenceDto dto = service.reindex(evidence.getId());

    assertTrue(dto.aiIndexed());
    verify(aiClient, times(1)).ingest(any(), any(), any(), any(), any(), any());
  }

  // ---- PRI-281: ZIP e imágenes se guardan pero no se mandan a backend-ai ----

  private static byte[] zipBytes() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("acta.pdf"));
      zip.write("contenido".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  @Test
  void uploadZipIsStoredButNotSentToAi() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "respaldo.zip", "application/zip", zipBytes());
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo).save(captor.capture());
    assertEquals("application/zip", captor.getValue().getFileType());
    assertFalse(captor.getValue().isAiIndexed());
  }

  @Test
  void uploadImageIsStoredWithItsRealTypeAndNotSentToAi() throws Exception {
    byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    MockMultipartFile file = new MockMultipartFile("file", "foto.png", "image/jpeg", png);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    service.upload(eval.getId(), null, null, file);

    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo).save(captor.capture());
    // Se guarda el tipo detectado por la firma, no el Content-Type que declaró el cliente.
    assertEquals("image/png", captor.getValue().getFileType());
  }

  @Test
  void uploadExecutableRenamedToPngNeverReachesStorage() {
    MockMultipartFile file =
        new MockMultipartFile("file", "virus.png", "image/png", new byte[] {0x4D, 0x5A, 0, 0});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.upload(eval.getId(), null, null, file));

    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(evidenceRepo, never()).save(any(Evidence.class));
  }

  @Test
  void reindexSkipsFormatsThatBackendAiDoesNotRead() {
    evidence.setFileType("application/zip");
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    EvidenceDto dto = service.reindex(evidence.getId());

    assertFalse(dto.aiIndexed());
    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
  }

  // ---- RNF-09: antivirus ----

  private static ClamAvClient.ScanResult scan(ClamAvClient.Status status, String signature) {
    return new ClamAvClient.ScanResult(
        status, signature, status == ClamAvClient.Status.ERROR ? "caído" : null);
  }

  private MockMultipartFile pdfFile() {
    return new MockMultipartFile(
        "file", "reporte.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
  }

  private void readyToUpload() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);
  }

  @Test
  void uploadScansTheFileAndRecordsACleanResult() throws Exception {
    readyToUpload();

    service.upload(eval.getId(), null, null, pdfFile());

    verify(clamAv, times(1)).scan(any());
    verify(auditLog)
        .recordIndependently(
            eq("EVIDENCE_SCAN_CLEAN"), eq("evaluation:" + eval.getId()), contains("reporte.pdf"));
    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void aninfectedFileIsRejectedRecordedAndNeverStored() throws Exception {
    readyToUpload();
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.INFECTED, "Win.Test.EICAR_HDB-1"));

    MalwareDetectedException ex =
        assertThrows(
            MalwareDetectedException.class,
            () -> service.upload(eval.getId(), null, null, pdfFile()));

    assertEquals("Win.Test.EICAR_HDB-1", ex.getSignature());
    verify(auditLog)
        .recordIndependently(eq("EVIDENCE_SCAN_INFECTED"), any(), contains("Win.Test.EICAR_HDB-1"));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(evidenceRepo, never()).save(any());
  }

  @Test
  void whenTheAntivirusFailsTheFileIsStoredInQuarantineAndRecorded() throws Exception {
    readyToUpload();
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.ERROR, null));

    UploadEvidenceResponseDto result = service.upload(eval.getId(), null, null, pdfFile());

    assertEquals("QUARANTINED", result.scanStatus());
    ArgumentCaptor<Evidence> saved = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo).save(saved.capture());
    assertEquals(Evidence.ScanStatus.QUARANTINED, saved.getValue().getScanStatus());
    assertFalse(saved.getValue().isAiIndexed());
    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
    verify(auditLog).recordIndependently(eq("EVIDENCE_QUARANTINED"), any(), contains("caído"));
  }

  @Test
  void aCleanUploadIsMarkedClean() throws Exception {
    readyToUpload();

    UploadEvidenceResponseDto result = service.upload(eval.getId(), null, null, pdfFile());

    assertEquals("CLEAN", result.scanStatus());
  }

  private Evidence storedEvidence(Evidence.ScanStatus status) {
    Evidence e =
        Evidence.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .fileName("reporte.pdf")
            .fileSize(8L)
            .fileType("application/pdf")
            .storageKey("evidence/" + UUID.randomUUID())
            .fileHash("abc")
            .scanStatus(status)
            .build();
    when(evidenceRepo.findById(e.getId())).thenReturn(Optional.of(e));
    return e;
  }

  @Test
  void aQuarantinedEvidenceCannotBeDownloaded() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(true);

    ConflictException ex = assertThrows(ConflictException.class, () -> service.download(e.getId()));

    assertTrue(ex.getMessage().contains("cuarentena"));
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
  }

  @Test
  void anInfectedEvidenceCannotBeDownloaded() {
    Evidence e = storedEvidence(Evidence.ScanStatus.INFECTED);
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(true);

    ConflictException ex = assertThrows(ConflictException.class, () -> service.download(e.getId()));

    assertTrue(ex.getMessage().contains("malicioso"));
  }

  @Test
  void aQuarantinedEvidenceCannotBeReindexed() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);

    assertThrows(ConflictException.class, () -> service.reindex(e.getId()));

    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
  }

  @Test
  void quarantinedIdsListsOnlyTheEvidenceWaitingForTheAntivirus() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);
    when(evidenceRepo.findByScanStatusOrderByUploadedAtAsc(Evidence.ScanStatus.QUARANTINED))
        .thenReturn(List.of(e));

    assertEquals(List.of(e.getId()), service.quarantinedIds());
  }

  @Test
  void rescanReleasesAQuarantinedEvidenceWhenItIsClean() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);
    stubObject("%PDF-1.4".getBytes(StandardCharsets.US_ASCII));
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.CLEAN, null));

    ClamAvClient.Status status = service.rescanQuarantined(e.getId());

    assertEquals(ClamAvClient.Status.CLEAN, status);
    assertEquals(Evidence.ScanStatus.CLEAN, e.getScanStatus());
    verify(auditLog)
        .recordSystemEvent(
            eq("EVIDENCE_QUARANTINE_RELEASED"),
            eq("evaluation:" + eval.getId()),
            contains("reporte.pdf"),
            any(),
            eq(eval.getOrganization().getId()));
  }

  @Test
  void rescanRejectsAnInfectedEvidenceAndDeletesTheStoredFile() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);
    stubObject("x".getBytes(StandardCharsets.US_ASCII));
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.INFECTED, "Eicar-Test-Signature"));

    ClamAvClient.Status status = service.rescanQuarantined(e.getId());

    assertEquals(ClamAvClient.Status.INFECTED, status);
    assertEquals(Evidence.ScanStatus.INFECTED, e.getScanStatus());
    assertFalse(e.isAiIndexed());
    ArgumentCaptor<DeleteObjectRequest> deleted =
        ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client).deleteObject(deleted.capture());
    assertEquals(e.getStorageKey(), deleted.getValue().key());
    verify(auditLog)
        .recordSystemEvent(
            eq("EVIDENCE_QUARANTINE_INFECTED"),
            any(),
            contains("Eicar-Test-Signature"),
            any(),
            any());
  }

  @Test
  void rescanLeavesTheEvidenceInQuarantineWhileTheAntivirusIsDown() {
    Evidence e = storedEvidence(Evidence.ScanStatus.QUARANTINED);
    stubObject("x".getBytes(StandardCharsets.US_ASCII));
    when(clamAv.scan(any())).thenReturn(scan(ClamAvClient.Status.ERROR, null));

    ClamAvClient.Status status = service.rescanQuarantined(e.getId());

    assertEquals(ClamAvClient.Status.ERROR, status);
    assertEquals(Evidence.ScanStatus.QUARANTINED, e.getScanStatus());
    verify(auditLog, never()).recordSystemEvent(any(), any(), any(), any(), any());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void rescanIgnoresEvidenceThatIsNotInQuarantine() {
    Evidence e = storedEvidence(Evidence.ScanStatus.CLEAN);

    assertNull(service.rescanQuarantined(e.getId()));
    assertNull(service.rescanQuarantined(UUID.randomUUID()));

    verify(clamAv, never()).scan(any());
  }

  @Test
  void auditPayloadEscapesTheFileName() throws Exception {
    readyToUpload();
    MockMultipartFile tricky =
        new MockMultipartFile(
            "file",
            "a\"b\\c.pdf",
            "application/pdf",
            "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));

    service.upload(eval.getId(), null, null, tricky);

    verify(auditLog)
        .recordIndependently(eq("EVIDENCE_SCAN_CLEAN"), any(), contains("a\\\"b\\\\c.pdf"));
  }

  // ---- PRI-283: tamaño máximo ----

  private EvidenceService serviceWithMaxSize(DataSize maxSize) {
    return new EvidenceService(
        evidenceRepo,
        evaluationRepo,
        controlRepo,
        s3Client,
        internalPresigner,
        properties,
        currentUser,
        aiClient,
        EvidenceFileValidatorTest.validatorWithMaxSize(maxSize),
        clamAv,
        auditLog);
  }

  @Test
  void uploadAtTheSizeLimitIsAccepted() throws Exception {
    byte[] pdf = new byte[16];
    System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, pdf, 0, 5);
    MockMultipartFile file = new MockMultipartFile("file", "justo.pdf", "application/pdf", pdf);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);

    serviceWithMaxSize(DataSize.ofBytes(16)).upload(eval.getId(), null, null, file);

    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void uploadOneByteOverTheLimitIsRejectedBeforeTouchingStorage() {
    byte[] pdf = new byte[17];
    System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, pdf, 0, 5);
    MockMultipartFile file = new MockMultipartFile("file", "grande.pdf", "application/pdf", pdf);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    PayloadTooLargeException error =
        assertThrows(
            PayloadTooLargeException.class,
            () -> serviceWithMaxSize(DataSize.ofBytes(16)).upload(eval.getId(), null, null, file));

    assertEquals("El archivo supera el tamaño máximo permitido de 16 bytes", error.getMessage());
    verify(s3Client, never()).headBucket(any(HeadBucketRequest.class));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(evidenceRepo, never()).save(any(Evidence.class));
  }

  @Test
  void theSizeLimitComesFromTheValidator() {
    assertEquals(50L * 1024 * 1024, service.maxSizeBytes());
    assertEquals(5L * 1024 * 1024, serviceWithMaxSize(DataSize.ofMegabytes(5)).maxSizeBytes());
  }

  @Test
  void allowedExtensionsComeFromTheValidator() {
    assertEquals(EvidenceFileValidatorTest.DEFAULT_FORMATS, service.allowedExtensions());
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
    eval.setStatus(Evaluation.Status.IN_AUDIT);
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
  void getCitationsOnlyWorksWhileTheEvaluationIsInAudit() {
    UUID controlId = UUID.randomUUID();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    for (Evaluation.Status status : Evaluation.Status.values()) {
      if (status == Evaluation.Status.IN_AUDIT) {
        continue;
      }
      eval.setStatus(status);
      var ex =
          assertThrows(
              uy.edu.prisma.domain.exception.ConflictException.class,
              () -> service.getCitations(eval.getId(), controlId),
              status.name());
      assertTrue(ex.getMessage().contains(status.name()), "el mensaje trae el estado actual");
    }
    verify(aiClient, never()).citations(any(), any(), any(), any());
  }

  @Test
  void getCitationsPropagatesAiUnavailableInsteadOfPretendingThereAreNoResults() {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    UUID controlId = UUID.randomUUID();
    CatalogControl control =
        CatalogControl.builder().id(controlId).code("PR.AA-01").description("Control X").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.of(control));
    when(aiClient.citations(any(), any(), any(), any()))
        .thenThrow(new uy.edu.prisma.domain.exception.AiUnavailableException("IA caída", null));

    assertThrows(
        uy.edu.prisma.domain.exception.AiUnavailableException.class,
        () -> service.getCitations(eval.getId(), controlId));
  }

  @Test
  void getCitationsThrowsWhenControlMissing() {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
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

    verify(s3Client, times(1)).deleteObject(any(DeleteObjectRequest.class));
    verify(aiClient, times(1)).deleteEvidence(eval.getOrganization().getId(), evidence.getId());
    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteAllowedForOrgResponsible() throws Exception {
    // Rol ORG_RESPONSIBLE de la organización, pero no es quien subió la evidencia (RF-EVI-08).
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(true);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    service.delete(evidence.getId());

    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteAllowedForUploader() throws Exception {
    // No es ORG_RESPONSIBLE ni tiene acceso global, pero subió la evidencia (RF-EVI-08).
    User uploader = User.builder().id(UUID.randomUUID()).build();
    evidence.setUploadedBy(uploader);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.currentUserId()).thenReturn(uploader.getId());
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    service.delete(evidence.getId());

    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteRejectedForUnrelatedUser() throws Exception {
    // Ni ORG_RESPONSIBLE, ni acceso global, ni quien subió la evidencia: debe rechazarse
    // (RF-EVI-08 -- solo el responsable de organización o el usuario creador pueden borrar).
    evidence.setUploadedBy(User.builder().id(UUID.randomUUID()).build());
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.currentUserId()).thenReturn(UUID.randomUUID());
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(AccessDeniedException.class, () -> service.delete(evidence.getId()));
    verify(evidenceRepo, never()).delete(any());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void deleteRejectsWhenEvaluationIsUnderAudit() throws Exception {
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.delete(evidence.getId()));
    verify(evidenceRepo, never()).delete(any());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void deleteIgnoresStorageErrors() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    doThrow(new RuntimeException("storage"))
        .when(s3Client)
        .deleteObject(any(DeleteObjectRequest.class));

    service.delete(evidence.getId());

    verify(evidenceRepo, times(1)).delete(evidence);
  }

  @Test
  void deleteThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evidenceRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.delete(id));
  }

  private void stubObject(byte[] bytes) {
    GetObjectResponse response =
        GetObjectResponse.builder().contentLength((long) bytes.length).build();
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenReturn(
            new ResponseInputStream<>(
                response, AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
  }

  @Test
  void downloadStreamsFileForAdmin() throws Exception {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    stubObject(new byte[] {1, 2, 3});

    EvidenceService.EvidenceFile file = service.download(evidence.getId());

    assertEquals("doc.pdf", file.fileName());
    assertEquals("application/pdf", file.contentType());
    assertEquals(3L, file.size());
    assertArrayEquals(new byte[] {1, 2, 3}, file.content().readAllBytes());
    ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObject(captor.capture());
    assertEquals(evidence.getStorageKey(), captor.getValue().key());
  }

  @Test
  void downloadAllowedForOrgResponsible() {
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(true);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    stubObject(new byte[] {1});

    assertEquals("doc.pdf", service.download(evidence.getId()).fileName());
  }

  @Test
  void downloadAllowedForAuditor() {
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.hasRole(UserRole.AUDITOR)).thenReturn(true);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    stubObject(new byte[] {1});

    assertEquals("doc.pdf", service.download(evidence.getId()).fileName());
  }

  @Test
  void downloadDeniedForOtherRolesWithoutTouchingStorage() {
    when(currentUser.hasRole(UserRole.PRISMA_ADMIN)).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.hasRole(UserRole.AUDITOR)).thenReturn(false);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () -> service.download(evidence.getId()));
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
  }

  @Test
  void downloadDeniedWhenOrganizationAccessFails() {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    doThrow(new RuntimeException("403")).when(currentUser).assertOrganizationAccess(any());

    assertThrows(RuntimeException.class, () -> service.download(evidence.getId()));
    verify(s3Client, never()).getObject(any(GetObjectRequest.class));
  }

  @Test
  void uploadIndexesInAiUsingTheInternalEndpointNotThePublicOne() throws Exception {
    // La URL que backend-ai usa para bajar el archivo es server-to-server (dentro de la red de
    // contenedores): tiene que salir del presigner INTERNO, nunca del público (que apunta a un host
    // como localhost:9001 que solo tiene sentido desde el navegador del usuario).
    MockMultipartFile file = new MockMultipartFile("file", "f.txt", "text/plain", new byte[] {9});
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(internalPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenAnswer(inv -> signed("http://rustfs:9000/internal-signed"));
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);

    service.upload(eval.getId(), null, null, file);

    verify(aiClient, times(1))
        .ingest(any(), any(), any(), isNull(), any(), eq("http://rustfs:9000/internal-signed"));
  }

  // ---- PRI-357 · RF-EVI-12: indexación automática al subir (CP219) ----

  private static byte[] docxBytes() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  /**
   * Sube un archivo con backend-ai disponible y comprueba que se indexó acotado a la organización y
   * la evaluación, con el control asociado, y que la evidencia quedó con aiIndexed = true.
   */
  private void assertUploadIsIndexed(String fileName, String contentType, byte[] content)
      throws Exception {
    UUID controlId = UUID.randomUUID();
    CatalogControl control = CatalogControl.builder().id(controlId).build();
    MockMultipartFile file = new MockMultipartFile("file", fileName, contentType, content);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(controlId)).thenReturn(Optional.of(control));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(internalPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenAnswer(inv -> signed("http://rustfs:9000/internal-signed"));
    when(evidenceRepo.save(any(Evidence.class))).thenAnswer(EvidenceServiceTest::simulatePersist);
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);

    UploadEvidenceResponseDto result = service.upload(eval.getId(), controlId, null, file);

    verify(aiClient, times(1))
        .ingest(
            eq(eval.getOrganization().getId()),
            eq(eval.getId()),
            eq(result.id()),
            eq(controlId),
            eq(fileName),
            eq("http://rustfs:9000/internal-signed"));
    ArgumentCaptor<Evidence> captor = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo, times(1)).save(captor.capture());
    assertTrue(captor.getValue().isAiIndexed(), fileName + " debería quedar indexada");
  }

  @Test
  void uploadedPdfIsIndexedForTheOrganizationAndEvaluation() throws Exception {
    assertUploadIsIndexed("politica.pdf", "application/pdf", "%PDF-1.4 contenido".getBytes());
  }

  @Test
  void uploadedDocxIsIndexedForTheOrganizationAndEvaluation() throws Exception {
    assertUploadIsIndexed(
        "politica.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        docxBytes());
  }

  @Test
  void uploadedTxtIsIndexedForTheOrganizationAndEvaluation() throws Exception {
    assertUploadIsIndexed(
        "politica.txt", "text/plain", "Política de accesos".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void uploadedMarkdownIsIndexedForTheOrganizationAndEvaluation() throws Exception {
    assertUploadIsIndexed(
        "politica.md", "text/markdown", "# Política".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void downloadThrowsWhenMissing() {
    UUID id = UUID.randomUUID();
    when(evidenceRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.download(id));
  }

  // ---- Evaluación cerrada: nadie puede borrar evidencia, ni siquiera con permiso de borrado ----

  /** Estados en que la organización ya no puede tocar la evidencia. */
  private static final List<Evaluation.Status> CLOSED_FOR_EVIDENCE =
      List.of(
          Evaluation.Status.READY_FOR_AUDIT,
          Evaluation.Status.IN_AUDIT,
          Evaluation.Status.APPROVED,
          Evaluation.Status.ARCHIVED);

  private void assertDeleteRejectedFor(Evaluation.Status status) {
    eval.setStatus(status);
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.delete(evidence.getId()),
        "estado " + status);
    verify(evidenceRepo, never()).delete(any());
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
  }

  @Test
  void anAdministratorCannotDeleteEvidenceOnceTheEvaluationIsClosed() {
    // Por defecto el usuario de la prueba es PRISMA_ADMIN con acceso a todas las organizaciones.
    for (Evaluation.Status status : CLOSED_FOR_EVIDENCE) {
      assertDeleteRejectedFor(status);
    }
  }

  @Test
  void theOrganizationResponsibleCannotDeleteEvidenceOnceTheEvaluationIsClosed() {
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(true);
    for (Evaluation.Status status : CLOSED_FOR_EVIDENCE) {
      assertDeleteRejectedFor(status);
    }
  }

  @Test
  void theUploaderCannotDeleteEvidenceOnceTheEvaluationIsClosed() {
    User uploader = User.builder().id(UUID.randomUUID()).build();
    evidence.setUploadedBy(uploader);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(false);
    when(currentUser.hasRole(UserRole.ORG_RESPONSIBLE)).thenReturn(false);
    when(currentUser.currentUserId()).thenReturn(uploader.getId());
    for (Evaluation.Status status : CLOSED_FOR_EVIDENCE) {
      assertDeleteRejectedFor(status);
    }
  }

  @Test
  void aClosedEvaluationAlsoRejectsUploadingAndReindexingEvidence() {
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    for (Evaluation.Status status :
        List.of(Evaluation.Status.APPROVED, Evaluation.Status.ARCHIVED)) {
      eval.setStatus(status);
      assertThrows(
          uy.edu.prisma.domain.exception.InvalidRequestException.class,
          () -> service.reindex(evidence.getId()),
          "reindexar en " + status);
    }
  }

  @Test
  void whileTheEvaluationIsBeingAnsweredDeletingStillWorks() throws Exception {
    for (Evaluation.Status status :
        List.of(
            Evaluation.Status.DRAFT, Evaluation.Status.IN_PROGRESS, Evaluation.Status.RETURNED)) {
      eval.setStatus(status);
      when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));

      service.delete(evidence.getId());
    }
    verify(evidenceRepo, times(3)).delete(evidence);
  }
}
