package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
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
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiEvidenceClassificationFeedback;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.EvidenceControlSuggestion;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiEvidenceClassificationFeedbackRepository;
import uy.edu.prisma.domain.repository.AiJobRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceControlSuggestionRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ClassifyCandidate;
import uy.edu.prisma.infrastructure.AiEvidenceClient.ControlSuggestion;
import uy.edu.prisma.web.dto.Dto.EvidenceSuggestionDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceClassificationServiceTest {

  @Mock private EvidenceRepository evidenceRepo;
  @Mock private EvidenceControlSuggestionRepository suggestionRepo;
  @Mock private AiEvidenceClassificationFeedbackRepository feedbackRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private AiEvidenceClient aiClient;
  @Mock private AiJobRepository jobRepo;
  @Mock private CurrentUserService currentUser;
  @Mock private org.springframework.transaction.PlatformTransactionManager txManager;

  private EvidenceClassificationService service;
  private Evaluation eval;
  private Evidence evidence;
  private CatalogControl c1;
  private CatalogControl c2;
  private CatalogControl c3;
  private final UUID orgId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new EvidenceClassificationService(
            evidenceRepo,
            suggestionRepo,
            feedbackRepo,
            controlRepo,
            evaluationRepo,
            aiClient,
            jobRepo,
            new AiJobProperties(),
            currentUser,
            txManager);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .organization(Organization.builder().id(orgId).build())
            .catalogVersion("5.0")
            .status(Evaluation.Status.IN_AUDIT)
            .build();
    evidence =
        Evidence.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .fileName("politica.pdf")
            .indexStatus(Evidence.IndexStatus.INDEXED)
            .build();
    c1 = CatalogControl.builder().id(UUID.randomUUID()).code("CA.1-1").description("uno").build();
    c2 = CatalogControl.builder().id(UUID.randomUUID()).code("CA.1-2").description("dos").build();
    c3 = CatalogControl.builder().id(UUID.randomUUID()).code("CA.1-3").description("tres").build();
    when(evidenceRepo.findById(evidence.getId())).thenReturn(Optional.of(evidence));
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1, c2, c3));
    for (CatalogControl c : List.of(c1, c2, c3)) {
      when(controlRepo.findById(c.getId())).thenReturn(Optional.of(c));
    }
  }

  // ------------------------------------------------------------------ encolar

  @Test
  void aGlobalEvidenceGetsABatchSystemJob() {
    service.enqueue(evidence);

    ArgumentCaptor<AiJob> job = ArgumentCaptor.forClass(AiJob.class);
    verify(jobRepo).save(job.capture());
    assertEquals(AiJob.Type.EVIDENCE_CLASSIFY, job.getValue().getType());
    assertEquals(AiJob.PRIORITY_BATCH, job.getValue().getPriority());
    assertNull(job.getValue().getRequestedBy());
    assertEquals(orgId, job.getValue().getOrganizationId());
  }

  @Test
  void anEvidenceThatHasAControlIsNeverClassified() {
    evidence.setControl(c1);

    service.enqueue(evidence);

    verify(jobRepo, never()).save(any());
  }

  @Test
  void queuingTwiceDoesNotDuplicateTheJob() {
    when(jobRepo.findFirstByDedupKeyAndStatusIn(anyString(), any()))
        .thenReturn(Optional.of(AiJob.builder().build()));

    service.enqueue(evidence);

    verify(jobRepo, never()).save(any());
  }

  // ------------------------------------------------------------------ ejecutar

  @Test
  void itSendsTheControlsOfTheEvaluationAsCandidatesAndSavesTheTopSuggestions() {
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ControlSuggestion(c2.getId(), 0.81), new ControlSuggestion(c1.getId(), 0.55)));
    when(suggestionRepo.findByEvidenceId(evidence.getId())).thenReturn(List.of());

    int saved = service.run(evidence.getId());

    assertEquals(2, saved);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ClassifyCandidate>> candidates = ArgumentCaptor.forClass(List.class);
    verify(aiClient)
        .classify(eq(orgId), eq(eval.getId()), eq(evidence.getId()), candidates.capture(), eq(3));
    assertEquals(3, candidates.getValue().size());
    assertEquals("CA.1-2: dos", candidates.getValue().get(1).query());
    ArgumentCaptor<EvidenceControlSuggestion> rows =
        ArgumentCaptor.forClass(EvidenceControlSuggestion.class);
    verify(suggestionRepo, times(2)).save(rows.capture());
    assertEquals(c2, rows.getAllValues().get(0).getControl());
    assertEquals(1, rows.getAllValues().get(0).getRank());
    assertEquals(0.81, rows.getAllValues().get(0).getScore());
    assertEquals(2, rows.getAllValues().get(1).getRank());
    assertEquals(EvidenceControlSuggestion.Status.PENDING, rows.getAllValues().get(0).getStatus());
  }

  @Test
  void theCandidatesAreLimitedToTheCommunityProfileOfTheEvaluation() {
    CommunityProfile profile =
        CommunityProfile.builder()
            .id(UUID.randomUUID())
            .controls(new HashSet<>(Set.of(c1, c3)))
            .build();
    eval.setCommunityProfile(profile);
    when(aiClient.classify(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

    service.run(evidence.getId());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ClassifyCandidate>> candidates = ArgumentCaptor.forClass(List.class);
    verify(aiClient).classify(any(), any(), any(), candidates.capture(), anyInt());
    assertEquals(
        Set.of(c1.getId(), c3.getId()),
        candidates.getValue().stream()
            .map(ClassifyCandidate::controlId)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @Test
  void decidedSuggestionsAreKeptAndNotSuggestedAgain() {
    EvidenceControlSuggestion decided =
        EvidenceControlSuggestion.builder()
            .control(c2)
            .status(EvidenceControlSuggestion.Status.REJECTED)
            .build();
    when(suggestionRepo.findByEvidenceId(evidence.getId())).thenReturn(List.of(decided));
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenReturn(
            List.of(
                new ControlSuggestion(c2.getId(), 0.9), new ControlSuggestion(c1.getId(), 0.5)));

    int saved = service.run(evidence.getId());

    assertEquals(1, saved);
    verify(suggestionRepo).deletePendingOfEvidence(evidence.getId());
    ArgumentCaptor<EvidenceControlSuggestion> row =
        ArgumentCaptor.forClass(EvidenceControlSuggestion.class);
    verify(suggestionRepo).save(row.capture());
    assertEquals(c1, row.getValue().getControl());
  }

  @Test
  void anEvidenceThatIsNotIndexedYetOrHasBeenDeletedIsSkipped() {
    evidence.setIndexStatus(Evidence.IndexStatus.QUEUED);
    assertEquals(0, service.run(evidence.getId()));
    assertEquals(0, service.run(UUID.randomUUID()));
    verifyNoInteractions(aiClient);
  }

  @Test
  void ifTheAssistantIsDownTheErrorReachesTheQueue() {
    when(aiClient.classify(any(), any(), any(), any(), anyInt()))
        .thenThrow(new uy.edu.prisma.domain.exception.AiUnavailableException("caída", null));

    assertThrows(
        uy.edu.prisma.domain.exception.AiUnavailableException.class,
        () -> service.run(evidence.getId()));
    verify(suggestionRepo, never()).save(any());
  }

  // ------------------------------------------------------------------ decidir

  private EvidenceControlSuggestion pending() {
    EvidenceControlSuggestion s =
        EvidenceControlSuggestion.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .evidence(evidence)
            .control(c2)
            .score(0.81)
            .rank(2)
            .build();
    when(suggestionRepo.findById(s.getId())).thenReturn(Optional.of(s));
    return s;
  }

  @Test
  void acceptingASuggestionRecordsItAndNeverAssignsTheEvidenceToTheControl() {
    EvidenceControlSuggestion s = pending();
    User auditor = User.builder().id(UUID.randomUUID()).build();
    when(currentUser.currentUser()).thenReturn(Optional.of(auditor));

    EvidenceSuggestionDto dto = service.decide(s.getId(), "ACCEPTED");

    assertEquals("ACCEPTED", dto.status());
    assertEquals(EvidenceControlSuggestion.Status.ACCEPTED, s.getStatus());
    assertEquals(auditor, s.getDecidedBy());
    assertNotNull(s.getDecidedAt());
    // D7: solo sugerencia, la evidencia sigue sin control.
    assertNull(evidence.getControl());
    verify(evidenceRepo, never()).save(any());
  }

  @Test
  void theFeedbackIsAnonymousAndMinimal() {
    EvidenceControlSuggestion s = pending();

    service.decide(s.getId(), "REJECTED");

    ArgumentCaptor<AiEvidenceClassificationFeedback> fb =
        ArgumentCaptor.forClass(AiEvidenceClassificationFeedback.class);
    verify(feedbackRepo).save(fb.capture());
    assertEquals(c2, fb.getValue().getControl());
    assertEquals(0.81, fb.getValue().getScore());
    assertEquals(2, fb.getValue().getRank());
    assertEquals(AiEvidenceClassificationFeedback.Decision.REJECTED, fb.getValue().getDecision());
    for (var field : AiEvidenceClassificationFeedback.class.getDeclaredFields()) {
      String name = field.getName().toLowerCase();
      assertFalse(
          name.contains("organization")
              || name.contains("evaluation")
              || name.contains("user")
              || name.contains("file")
              || name.contains("text")
              || name.contains("snippet"),
          name);
    }
  }

  @Test
  void suggestionsOnlyExistInAudit() {
    eval.setStatus(Evaluation.Status.RETURNED);
    EvidenceControlSuggestion s = pending();

    assertThrows(ConflictException.class, () -> service.list(eval.getId()));
    assertThrows(ConflictException.class, () -> service.decide(s.getId(), "ACCEPTED"));
    verify(feedbackRepo, never()).save(any());
  }

  @Test
  void theOrganizationIsVerifiedOnEveryCall() {
    EvidenceControlSuggestion s = pending();
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(orgId);

    assertThrows(AccessDeniedException.class, () -> service.list(eval.getId()));
    assertThrows(AccessDeniedException.class, () -> service.decide(s.getId(), "ACCEPTED"));
  }

  @Test
  void anUnknownSuggestionIsNotFound() {
    UUID id = UUID.randomUUID();
    when(suggestionRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.decide(id, "ACCEPTED"));
  }

  @Test
  void theListCarriesTheControlAndTheScore() {
    EvidenceControlSuggestion s = pending();
    when(suggestionRepo.findByEvaluationIdOrderByEvidenceIdAscRankAsc(eval.getId()))
        .thenReturn(List.of(s));

    List<EvidenceSuggestionDto> list = service.list(eval.getId());

    assertEquals(1, list.size());
    assertEquals("CA.1-2", list.get(0).controlCode());
    assertEquals("politica.pdf", list.get(0).fileName());
    assertEquals(0.81, list.get(0).score());
    assertEquals(2, list.get(0).rank());
  }

  @Test
  void withoutAThresholdTheSuggestionsAreMarkedWithoutCalibratedConfidence() {
    EvidenceControlSuggestion s = pending();
    when(suggestionRepo.findByEvaluationIdOrderByEvidenceIdAscRankAsc(eval.getId()))
        .thenReturn(List.of(s));

    // AI_CLASSIFY_MIN_SCORE sin definir (o vacío): el puntaje no tiene umbral calibrado (3.4).
    assertFalse(service.list(eval.getId()).get(0).calibrated());
    service.setClassifyMinScore("");
    assertFalse(service.list(eval.getId()).get(0).calibrated());
  }

  @Test
  void withAThresholdTheSuggestionsAreCalibrated() {
    EvidenceControlSuggestion s = pending();
    when(suggestionRepo.findByEvaluationIdOrderByEvidenceIdAscRankAsc(eval.getId()))
        .thenReturn(List.of(s));
    service.setClassifyMinScore("0.52");

    assertTrue(service.list(eval.getId()).get(0).calibrated());
  }
}
