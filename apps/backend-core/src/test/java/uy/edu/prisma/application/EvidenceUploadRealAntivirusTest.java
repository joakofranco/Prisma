package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import uy.edu.prisma.config.ClamAvConfig.ClamAvProperties;
import uy.edu.prisma.config.ObjectStorageConfig.StorageProperties;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.MalwareDetectedException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.ClamAvClient;
import uy.edu.prisma.web.dto.Dto.UploadEvidenceResponseDto;

@EnabledIfEnvironmentVariable(named = "CLAMAV_HOST", matches = ".+")
class EvidenceUploadRealAntivirusTest {

  private static final String EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  private final EvidenceRepository evidenceRepo = mock(EvidenceRepository.class);
  private final EvaluationRepository evaluationRepo = mock(EvaluationRepository.class);
  private final S3Client s3Client = mock(S3Client.class);
  private final AuditLogService auditLog = mock(AuditLogService.class);
  private final CurrentUserService currentUser = mock(CurrentUserService.class);
  private Evaluation eval;

  private EvidenceService serviceWith(ClamAvClient clamAv) {
    return new EvidenceService(
        evidenceRepo,
        evaluationRepo,
        mock(CatalogControlRepository.class),
        s3Client,
        mock(S3Presigner.class),
        new StorageProperties(
            "http://rustfs:9000", "http://localhost:9001", "ak", "sk", "prisma-evidences"),
        currentUser,
        mock(AiEvidenceClient.class),
        EvidenceFileValidatorTest.validatorFor(EvidenceFileValidatorTest.DEFAULT_FORMATS),
        clamAv,
        auditLog);
  }

  private ClamAvClient realClamAv() {
    return new ClamAvClient(
        new ClamAvProperties(
            System.getenv("CLAMAV_HOST"),
            Integer.parseInt(System.getenv().getOrDefault("CLAMAV_PORT", "3310")),
            Duration.ofMinutes(2)));
  }

  private ClamAvClient deadClamAv() {
    return new ClamAvClient(
        new ClamAvProperties("clamav-que-no-existe", 3310, Duration.ofSeconds(2)));
  }

  @BeforeEach
  void setUp() {
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .catalogVersion("5.0")
            .status(Evaluation.Status.IN_PROGRESS)
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(null);
    when(evidenceRepo.save(any(Evidence.class)))
        .thenAnswer(
            inv -> {
              Evidence e = inv.getArgument(0);
              e.setId(UUID.randomUUID());
              return e;
            });
  }

  private MockMultipartFile txt(String name, String content) {
    return new MockMultipartFile(
        "file", name, "text/plain", content.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void aFileWithEicarIsRejectedAndNothingIsStored() {
    EvidenceService service = serviceWith(realClamAv());

    MalwareDetectedException ex =
        assertThrows(
            MalwareDetectedException.class,
            () -> service.upload(eval.getId(), null, null, txt("eicar.txt", EICAR)));

    assertTrue(ex.getSignature().toUpperCase().contains("EICAR"), ex.getSignature());
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(evidenceRepo, never()).save(any());
    verify(auditLog).recordIndependently(eq("EVIDENCE_SCAN_INFECTED"), any(), contains("Eicar"));
  }

  @Test
  void aCleanFileIsStoredAndTheScanIsRecorded() {
    EvidenceService service = serviceWith(realClamAv());

    UploadEvidenceResponseDto result =
        service.upload(eval.getId(), null, null, txt("politica.txt", "Política de seguridad"));

    assertNotNull(result.id());
    verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(auditLog)
        .recordIndependently(eq("EVIDENCE_SCAN_CLEAN"), any(), contains("politica.txt"));
  }

  private Evidence uploadWithDeadAntivirus(String name, String content) {
    EvidenceService service = serviceWith(deadClamAv());

    UploadEvidenceResponseDto result = service.upload(eval.getId(), null, null, txt(name, content));

    assertEquals("QUARANTINED", result.scanStatus());
    ArgumentCaptor<Evidence> saved = ArgumentCaptor.forClass(Evidence.class);
    verify(evidenceRepo).save(saved.capture());
    Evidence evidence = saved.getValue();
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            inv ->
                new ResponseInputStream<>(
                    GetObjectResponse.builder().build(),
                    AbortableInputStream.create(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)))));
    return evidence;
  }

  @Test
  void withoutAntivirusTheFileIsQuarantinedAndReleasedOnceItIsBack() {
    Evidence evidence = uploadWithDeadAntivirus("politica.txt", "hola");
    verify(auditLog).recordIndependently(eq("EVIDENCE_QUARANTINED"), any(), any());
    EvidenceService stillDown = serviceWith(deadClamAv());

    assertEquals(ClamAvClient.Status.ERROR, stillDown.rescanQuarantined(evidence.getId()));
    assertEquals(Evidence.ScanStatus.QUARANTINED, evidence.getScanStatus());

    EvidenceService back = serviceWith(realClamAv());
    assertEquals(ClamAvClient.Status.CLEAN, back.rescanQuarantined(evidence.getId()));
    assertEquals(Evidence.ScanStatus.CLEAN, evidence.getScanStatus());
    verify(auditLog)
        .recordSystemEvent(eq("EVIDENCE_QUARANTINE_RELEASED"), any(), any(), any(), any());
  }

  @Test
  void aQuarantinedEicarIsRejectedAndDeletedOnceTheAntivirusIsBack() {
    Evidence evidence = uploadWithDeadAntivirus("eicar.txt", EICAR);
    EvidenceService back = serviceWith(realClamAv());

    assertEquals(ClamAvClient.Status.INFECTED, back.rescanQuarantined(evidence.getId()));

    assertEquals(Evidence.ScanStatus.INFECTED, evidence.getScanStatus());
    verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    verify(auditLog)
        .recordSystemEvent(
            eq("EVIDENCE_QUARANTINE_INFECTED"), any(), contains("Eicar"), any(), any());
  }
}
