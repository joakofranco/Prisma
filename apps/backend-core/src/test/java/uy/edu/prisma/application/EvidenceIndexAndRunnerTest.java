package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.Evidence.IndexStatus;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.AiUnavailableException;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;

/** Indexación con estado (C7) y el despacho del trabajador para los trabajos del sistema. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceIndexAndRunnerTest {

  @Mock private EvidenceRepository evidenceRepo;
  @Mock private AiJobRepository jobRepo;
  @Mock private EvidenceCitationStore citationStore;
  @Mock private EvidenceService evidenceService;
  @Mock private AiEvidenceClient aiClient;
  @Mock private AiJobQueue queue;
  @Mock private CitationPrecomputeService precompute;
  @Mock private EvidenceClassificationService classification;

  private EvidenceIndexService index;
  private AiJobRunner runner;
  private Evidence evidence;
  private Evaluation eval;
  private final UUID orgId = UUID.randomUUID();
  private final UUID controlId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    index =
        new EvidenceIndexService(
            evidenceRepo,
            jobRepo,
            new AiJobProperties(),
            citationStore,
            evidenceService,
            classification);
    runner = new AiJobRunner(aiClient, queue, jobRepo, index, precompute, classification);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .organization(Organization.builder().id(orgId).build())
            .status(Evaluation.Status.IN_AUDIT)
            .build();
    evidence =
        Evidence.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(CatalogControl.builder().id(controlId).build())
            .fileName("politica.pdf")
            .fileType("application/pdf")
            .scanStatus(Evidence.ScanStatus.CLEAN)
            .build();
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(evidenceService.internalFileUrl(any())).thenReturn("http://rustfs:9000/signed");
  }

  // ------------------------------------------------------------------- enqueue

  @Test
  void anIndexableCleanEvidenceIsQueuedAndGetsAJob() {
    index.enqueue(evidence);

    assertEquals(IndexStatus.QUEUED, evidence.getIndexStatus());
    assertFalse(evidence.isAiIndexed());
    ArgumentCaptor<AiJob> job = ArgumentCaptor.forClass(AiJob.class);
    verify(jobRepo).save(job.capture());
    assertEquals(AiJob.Type.EVIDENCE_INDEX, job.getValue().getType());
    assertEquals(orgId, job.getValue().getOrganizationId());
    assertNull(job.getValue().getRequestedBy(), "trabajo del sistema: no aparece en Tareas de IA");
    assertEquals(EvidenceIndexService.PRIORITY, job.getValue().getPriority());
    assertEquals(evidence.getId().toString(), job.getValue().getParams().get("evidenceId"));
  }

  @Test
  void queuingTwiceDoesNotDuplicateTheJob() {
    when(jobRepo.findFirstByDedupKeyAndStatusIn(anyString(), any()))
        .thenReturn(Optional.of(AiJob.builder().id(UUID.randomUUID()).build()));

    index.enqueue(evidence);

    verify(jobRepo, never()).save(any());
    assertEquals(IndexStatus.QUEUED, evidence.getIndexStatus());
  }

  @Test
  void aZipOrAnImageIsNotIndexable() {
    evidence.setFileType("application/zip");

    index.enqueue(evidence);

    assertEquals(IndexStatus.NOT_INDEXABLE, evidence.getIndexStatus());
    assertNull(evidence.getIndexError());
    verify(jobRepo, never()).save(any());
  }

  @Test
  void aQuarantinedEvidenceWaitsForTheScanAndSaysSo() {
    evidence.setScanStatus(Evidence.ScanStatus.QUARANTINED);

    index.enqueue(evidence);

    assertEquals(IndexStatus.NOT_INDEXABLE, evidence.getIndexStatus());
    assertTrue(evidence.getIndexError().contains("cuarentena"));
    verify(jobRepo, never()).save(any());
  }

  @Test
  void anInfectedEvidenceIsNeverIndexed() {
    evidence.setScanStatus(Evidence.ScanStatus.INFECTED);

    index.enqueue(evidence);

    assertEquals(IndexStatus.NOT_INDEXABLE, evidence.getIndexStatus());
    verify(jobRepo, never()).save(any());
  }

  @Test
  void retryingAnErroredEvidenceQueuesItAgainAndClearsTheError() {
    evidence.setIndexStatus(IndexStatus.ERROR);
    evidence.setIndexError("falló");

    index.enqueue(evidence);

    assertEquals(IndexStatus.QUEUED, evidence.getIndexStatus());
    assertNull(evidence.getIndexError());
    verify(jobRepo).save(any(AiJob.class));
  }

  // ------------------------------------------------------------------ estados

  @Test
  void prepareMovesToIndexingAndBuildsTheInput() {
    var input = index.prepare(evidence.getId());

    assertEquals(IndexStatus.INDEXING, evidence.getIndexStatus());
    assertEquals(orgId, input.organizationId());
    assertEquals(eval.getId(), input.evaluationId());
    assertEquals(controlId, input.controlId());
    assertEquals("politica.pdf", input.fileName());
    assertEquals("http://rustfs:9000/signed", input.fileUrl());
  }

  @Test
  void prepareOfAnEvidenceThatNoLongerExistsReturnsNull() {
    assertNull(index.prepare(UUID.randomUUID()));
  }

  @Test
  void markIndexedSetsTheFlagsAndInvalidatesTheCitationsOfTheControl() {
    index.markIndexed(evidence.getId());

    assertEquals(IndexStatus.INDEXED, evidence.getIndexStatus());
    assertTrue(evidence.isAiIndexed());
    assertNotNull(evidence.getIndexedAt());
    assertNull(evidence.getIndexError());
    verify(citationStore).invalidateControl(eval.getId(), controlId);
  }

  @Test
  void markIndexedOfAGlobalEvidenceDoesNotTouchCitations() {
    evidence.setControl(null);

    index.markIndexed(evidence.getId());

    verifyNoInteractions(citationStore);
  }

  @Test
  void anIndexedGlobalEvidenceQueuesItsClassification() {
    evidence.setControl(null);

    index.markIndexed(evidence.getId());

    verify(classification).enqueue(evidence);
    verifyNoInteractions(citationStore);
  }

  @Test
  void anIndexedEvidenceWithAControlIsNotClassified() {
    index.markIndexed(evidence.getId());

    verifyNoInteractions(classification);
  }

  @Test
  void theRunnerDispatchesTheClassificationJob() {
    UUID evidenceId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.EVIDENCE_CLASSIFY)
            .params(Map.of("evidenceId", evidenceId.toString()))
            .build();

    runner.run(job);

    verify(classification).run(evidenceId);
    verify(queue).completeWithoutResult(eq(job.getId()), anyLong());
  }

  @Test
  void ifTheAssistantIsDownTheClassificationRetries() {
    UUID evidenceId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.EVIDENCE_CLASSIFY)
            .params(Map.of("evidenceId", evidenceId.toString()))
            .build();
    when(classification.run(evidenceId)).thenThrow(new AiUnavailableException("caída", null));

    runner.run(job);

    verify(queue).fail(eq(job.getId()), eq("AI_UNAVAILABLE"), anyString(), eq(true), anyLong());
  }

  @Test
  void anUnexpectedClassificationErrorIsNotRetried() {
    UUID evidenceId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.EVIDENCE_CLASSIFY)
            .params(Map.of("evidenceId", evidenceId.toString()))
            .build();
    when(classification.run(evidenceId)).thenThrow(new IllegalStateException("boom"));

    runner.run(job);

    verify(queue).fail(eq(job.getId()), eq("INTERNAL_ERROR"), anyString(), eq(false), anyLong());
  }

  @Test
  void markErrorKeepsTheReason() {
    index.markError(evidence.getId(), "motivo");

    assertEquals(IndexStatus.ERROR, evidence.getIndexStatus());
    assertEquals("motivo", evidence.getIndexError());
    assertFalse(evidence.isAiIndexed());
  }

  // ------------------------------------------------------------------- runner

  private AiJob indexJob() {
    return AiJob.builder()
        .id(UUID.randomUUID())
        .type(AiJob.Type.EVIDENCE_INDEX)
        .params(Map.of("evidenceId", evidence.getId().toString()))
        .build();
  }

  @Test
  void aSuccessfulIndexingMarksTheEvidenceIndexedAndCompletesTheJob() {
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(true);
    AiJob job = indexJob();

    runner.run(job);

    verify(aiClient)
        .ingest(
            eq(orgId),
            eq(eval.getId()),
            eq(evidence.getId()),
            eq(controlId),
            eq("politica.pdf"),
            eq("http://rustfs:9000/signed"));
    assertEquals(IndexStatus.INDEXED, evidence.getIndexStatus());
    verify(queue).completeWithoutResult(eq(job.getId()), anyLong());
    verify(queue, never()).fail(any(), any(), any(), anyBoolean(), anyLong());
  }

  @Test
  void aFailureWithAttemptsLeftGoesBackToQueued() {
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(false);
    AiJob job = indexJob();
    when(jobRepo.findById(job.getId()))
        .thenReturn(Optional.of(AiJob.builder().status(AiJob.Status.QUEUED).build()));

    runner.run(job);

    verify(queue)
        .fail(
            eq(job.getId()),
            eq(EvidenceIndexService.INDEX_FAILED),
            anyString(),
            eq(true),
            anyLong());
    assertEquals(IndexStatus.QUEUED, evidence.getIndexStatus());
    assertNull(evidence.getIndexError());
  }

  @Test
  void whenTheAttemptsRunOutTheEvidenceEndsInErrorWithAClearMessage() {
    when(aiClient.ingest(any(), any(), any(), any(), any(), any())).thenReturn(false);
    AiJob job = indexJob();
    when(jobRepo.findById(job.getId()))
        .thenReturn(Optional.of(AiJob.builder().status(AiJob.Status.FAILED).build()));

    runner.run(job);

    assertEquals(IndexStatus.ERROR, evidence.getIndexStatus());
    assertTrue(evidence.getIndexError().contains("No se pudo indexar"));
    assertFalse(evidence.isAiIndexed());
  }

  @Test
  void anUnexpectedExceptionIsTreatedAsAFailedAttempt() {
    when(aiClient.ingest(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("boom"));
    AiJob job = indexJob();
    when(jobRepo.findById(job.getId()))
        .thenReturn(Optional.of(AiJob.builder().status(AiJob.Status.FAILED).build()));

    assertDoesNotThrow(() -> runner.run(job));

    assertEquals(IndexStatus.ERROR, evidence.getIndexStatus());
  }

  @Test
  void ifTheUrlCannotBeSignedTheIndexingFails() {
    when(evidenceService.internalFileUrl(any())).thenThrow(new IllegalStateException("sin firma"));
    AiJob job = indexJob();
    when(jobRepo.findById(job.getId()))
        .thenReturn(Optional.of(AiJob.builder().status(AiJob.Status.FAILED).build()));

    runner.run(job);

    verify(aiClient, never()).ingest(any(), any(), any(), any(), any(), any());
    assertEquals(IndexStatus.ERROR, evidence.getIndexStatus());
  }

  @Test
  void aJobForADeletedEvidenceCompletesWithoutDoingAnything() {
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.EVIDENCE_INDEX)
            .params(Map.of("evidenceId", UUID.randomUUID().toString()))
            .build();

    runner.run(job);

    verify(queue).completeWithoutResult(eq(job.getId()), anyLong());
    verifyNoInteractions(aiClient);
  }

  @Test
  void theRunnerDispatchesThePrecomputeJob() {
    UUID evaluationId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.CITATIONS_PRECOMPUTE)
            .params(Map.of("evaluationId", evaluationId.toString()))
            .build();

    runner.run(job);

    verify(precompute).run(evaluationId);
    verify(queue).completeWithoutResult(eq(job.getId()), anyLong());
  }

  @Test
  void ifTheAssistantIsDownThePrecomputeFailsAsRetryable() {
    UUID evaluationId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.CITATIONS_PRECOMPUTE)
            .params(Map.of("evaluationId", evaluationId.toString()))
            .build();
    when(precompute.run(evaluationId)).thenThrow(new AiUnavailableException("caída", null));

    runner.run(job);

    verify(queue).fail(eq(job.getId()), eq("AI_UNAVAILABLE"), anyString(), eq(true), anyLong());
  }

  @Test
  void anUnexpectedPrecomputeErrorIsNotRetried() {
    UUID evaluationId = UUID.randomUUID();
    AiJob job =
        AiJob.builder()
            .id(UUID.randomUUID())
            .type(AiJob.Type.CITATIONS_PRECOMPUTE)
            .params(Map.of("evaluationId", evaluationId.toString()))
            .build();
    when(precompute.run(evaluationId)).thenThrow(new IllegalStateException("boom"));

    runner.run(job);

    verify(queue).fail(eq(job.getId()), eq("INTERNAL_ERROR"), anyString(), eq(false), anyLong());
  }

  @Test
  void theDedupKeyIsStablePerEvidence() {
    UUID id = UUID.randomUUID();
    assertEquals(EvidenceIndexService.dedupKey(id), EvidenceIndexService.dedupKey(id));
    assertNotEquals(
        EvidenceIndexService.dedupKey(id), EvidenceIndexService.dedupKey(UUID.randomUUID()));
    assertEquals(64, EvidenceIndexService.dedupKey(id).length());
    assertEquals(List.of(), List.of());
  }
}
