package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.AiUnavailableException;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CitationPrecomputeAndLifecycleTest {

  @Mock private EvaluationRepository evaluationRepo;
  @Mock private EvidenceRepository evidenceRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvidenceCitationStore store;
  @Mock private AiEvidenceClient aiClient;
  @Mock private AiJobRepository jobRepo;

  private CitationPrecomputeService service;
  private Evaluation eval;
  private final UUID orgId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new CitationPrecomputeService(
            evaluationRepo,
            evidenceRepo,
            controlRepo,
            store,
            aiClient,
            jobRepo,
            new AiJobProperties());
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .organization(Organization.builder().id(orgId).build())
            .status(Evaluation.Status.IN_AUDIT)
            .build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
  }

  private CatalogControl control(UUID id, String code) {
    CatalogControl c =
        CatalogControl.builder().id(id).code(code).description("desc " + code).build();
    when(controlRepo.findById(id)).thenReturn(Optional.of(c));
    return c;
  }

  // ------------------------------------------------------------------ encolar

  @Test
  void enqueueCreatesABatchSystemJobForTheEvaluation() {
    service.enqueue(eval.getId(), orgId);

    ArgumentCaptor<AiJob> job = ArgumentCaptor.forClass(AiJob.class);
    verify(jobRepo).save(job.capture());
    assertEquals(AiJob.Type.CITATIONS_PRECOMPUTE, job.getValue().getType());
    assertEquals(AiJob.PRIORITY_BATCH, job.getValue().getPriority());
    assertEquals(orgId, job.getValue().getOrganizationId());
    assertNull(job.getValue().getRequestedBy());
    assertEquals(eval.getId().toString(), job.getValue().getParams().get("evaluationId"));
  }

  @Test
  void enqueueTwiceDoesNotDuplicateTheJob() {
    when(jobRepo.findFirstByDedupKeyAndStatusIn(anyString(), any()))
        .thenReturn(Optional.of(AiJob.builder().build()));

    service.enqueue(eval.getId(), orgId);

    verify(jobRepo, never()).save(any());
  }

  // ------------------------------------------------------------------ ejecutar

  @Test
  void itSearchesAndSavesOnlyTheControlsWithIndexedEvidenceThatHaveNoCitationsYet() {
    UUID c1 = UUID.randomUUID();
    UUID c2 = UUID.randomUUID();
    CatalogControl control1 = control(c1, "PR.AA-01");
    control(c2, "PR.AA-02");
    when(evidenceRepo.findIndexedControlIds(eval.getId())).thenReturn(List.of(c1, c2));
    when(store.controlsWithResults(eval.getId(), List.of(c1, c2))).thenReturn(List.of(c2));
    List<EvidenceCitationDto> fresh =
        List.of(new EvidenceCitationDto(UUID.randomUUID(), "a.pdf", "p1", "t", 0.8));
    when(aiClient.citations(eq(orgId), eq(eval.getId()), eq(c1), eq("PR.AA-01: desc PR.AA-01")))
        .thenReturn(fresh);

    int processed = service.run(eval.getId());

    assertEquals(1, processed);
    verify(store).merge(eval, control1, fresh);
    verify(aiClient, never()).citations(any(), any(), eq(c2), any());
  }

  @Test
  void aControlWhoseEvidenceChangedIsComputedAgain() {
    UUID c1 = UUID.randomUUID();
    control(c1, "PR.AA-01");
    when(evidenceRepo.findIndexedControlIds(eval.getId())).thenReturn(List.of(c1));
    when(store.controlsWithResults(any(), any())).thenReturn(List.of(c1));
    when(store.isStale(eval.getId(), c1)).thenReturn(true);
    when(aiClient.citations(any(), any(), any(), any())).thenReturn(List.of());

    assertEquals(1, service.run(eval.getId()));
  }

  @Test
  void withoutIndexedEvidenceThereIsNothingToDo() {
    when(evidenceRepo.findIndexedControlIds(eval.getId())).thenReturn(List.of());

    assertEquals(0, service.run(eval.getId()));
    verifyNoInteractions(aiClient);
  }

  @Test
  void ifTheEvaluationLeftTheAuditTheJobIsDiscarded() {
    UUID c1 = UUID.randomUUID();
    control(c1, "PR.AA-01");
    when(evidenceRepo.findIndexedControlIds(eval.getId())).thenReturn(List.of(c1));
    when(store.controlsWithResults(any(), any())).thenReturn(List.of());
    eval.setStatus(Evaluation.Status.RETURNED);

    assertEquals(0, service.run(eval.getId()));
    verify(aiClient, never()).citations(any(), any(), any(), any());
    verify(store, never()).merge(any(), any(), any());
  }

  @Test
  void ifTheAssistantIsDownTheErrorReachesTheQueueSoItRetries() {
    UUID c1 = UUID.randomUUID();
    control(c1, "PR.AA-01");
    when(evidenceRepo.findIndexedControlIds(eval.getId())).thenReturn(List.of(c1));
    when(store.controlsWithResults(any(), any())).thenReturn(List.of());
    when(aiClient.citations(any(), any(), any(), any()))
        .thenThrow(new AiUnavailableException("caída", null));

    assertThrows(AiUnavailableException.class, () -> service.run(eval.getId()));
  }

  // ------------------------------------------------------------ ciclo de vida

  private CitationLifecycleListener listener() {
    return new CitationLifecycleListener(store, service);
  }

  @Test
  void enteringTheAuditQueuesThePrecompute() {
    listener()
        .onStatusChanged(
            new EvaluationStatusChangedEvent(
                eval.getId(),
                orgId,
                Evaluation.Status.READY_FOR_AUDIT,
                Evaluation.Status.IN_AUDIT));

    verify(jobRepo).save(any(AiJob.class));
    verify(store, never()).invalidateEvaluation(any());
  }

  @Test
  void leavingTheAuditDropsTheSavedCitations() {
    listener()
        .onStatusChanged(
            new EvaluationStatusChangedEvent(
                eval.getId(), orgId, Evaluation.Status.IN_AUDIT, Evaluation.Status.RETURNED));

    verify(store).invalidateEvaluation(eval.getId());
    verify(jobRepo, never()).save(any());
  }

  @Test
  void otherTransitionsDoNothing() {
    listener()
        .onStatusChanged(
            new EvaluationStatusChangedEvent(
                eval.getId(), orgId, Evaluation.Status.DRAFT, Evaluation.Status.IN_PROGRESS));

    verifyNoInteractions(store);
    verify(jobRepo, never()).save(any());
  }

  @Test
  void aFailureHereNeverBreaksTheStatusChange() {
    when(store.invalidateEvaluation(any())).thenThrow(new IllegalStateException("base caída"));

    assertDoesNotThrow(
        () ->
            listener()
                .onStatusChanged(
                    new EvaluationStatusChangedEvent(
                        eval.getId(),
                        orgId,
                        Evaluation.Status.IN_AUDIT,
                        Evaluation.Status.APPROVED)));
  }

  @Test
  void theDedupKeyIsStablePerEvaluation() {
    UUID id = UUID.randomUUID();
    assertEquals(CitationPrecomputeService.dedupKey(id), CitationPrecomputeService.dedupKey(id));
    assertEquals(Set.of(64), Set.of(CitationPrecomputeService.dedupKey(id).length()));
  }
}
