package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.ControlVerdictDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditVerdictServiceTest {

  @Mock private AuditControlVerdictRepository repo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CurrentUserService currentUser;
  @Mock private AuditLogService auditLog;
  @Mock private EvidenceCitationStore citationStore;

  private AuditVerdictService service;
  private Evaluation eval;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service =
        new AuditVerdictService(
            repo, evaluationRepo, controlRepo, currentUser, auditLog, citationStore);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .status(Evaluation.Status.IN_AUDIT)
            .build();
    control = CatalogControl.builder().id(UUID.randomUUID()).code("C1").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.save(any(AuditControlVerdict.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void setCreatesVerdictForEachOfTheThreeValues() {
    for (String v : List.of("VALIDATED", "NOT_VALIDATED", "NEEDS_MORE_EVIDENCE")) {
      when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
          .thenReturn(Optional.empty());

      ControlVerdictDto dto = service.set(eval.getId(), control.getId(), v);

      assertEquals(v, dto.verdict());
      assertEquals(control.getId(), dto.controlId());
    }
    verify(repo, times(3)).save(any(AuditControlVerdict.class));
  }

  @Test
  void setUpdatesTheExistingVerdictInsteadOfCreatingAnother() {
    AuditControlVerdict existing =
        AuditControlVerdict.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(control)
            .verdict(AuditControlVerdict.Verdict.NEEDS_MORE_EVIDENCE)
            .build();
    when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.of(existing));

    ControlVerdictDto dto = service.set(eval.getId(), control.getId(), "VALIDATED");

    assertEquals("VALIDATED", dto.verdict());
    assertEquals(AuditControlVerdict.Verdict.VALIDATED, existing.getVerdict());
  }

  @Test
  void setRejectsInvalidOrBlankVerdict() {
    assertThrows(
        InvalidRequestException.class, () -> service.set(eval.getId(), control.getId(), "MAYBE"));
    assertThrows(
        InvalidRequestException.class, () -> service.set(eval.getId(), control.getId(), " "));
    verify(repo, never()).save(any());
  }

  @Test
  void setRejectedWhenEvaluationIsNotInAudit() {
    for (Evaluation.Status s :
        List.of(
            Evaluation.Status.DRAFT,
            Evaluation.Status.READY_FOR_AUDIT,
            Evaluation.Status.APPROVED)) {
      eval.setStatus(s);
      assertThrows(
          InvalidRequestException.class,
          () -> service.set(eval.getId(), control.getId(), "VALIDATED"));
    }
    verify(repo, never()).save(any());
  }

  @Test
  void setRejectedWhenControlOutsideCommunityProfile() {
    CatalogControl other = CatalogControl.builder().id(UUID.randomUUID()).code("OUT").build();
    eval.setCommunityProfile(
        CommunityProfile.builder().id(UUID.randomUUID()).controls(Set.of(other)).build());

    assertThrows(
        InvalidRequestException.class,
        () -> service.set(eval.getId(), control.getId(), "VALIDATED"));
    verify(repo, never()).save(any());
  }

  @Test
  void setThrowsWhenEvaluationOrControlMissing() {
    UUID missing = UUID.randomUUID();
    when(evaluationRepo.findById(missing)).thenReturn(Optional.empty());
    when(controlRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> service.set(missing, control.getId(), "VALIDATED"));
    assertThrows(
        ResourceNotFoundException.class, () -> service.set(eval.getId(), missing, "VALIDATED"));
  }

  @Test
  void setAndListRejectedWhenOrganizationAccessDenied() {
    doThrow(new RuntimeException("403")).when(currentUser).assertOrganizationAccess(any());

    assertThrows(
        RuntimeException.class, () -> service.set(eval.getId(), control.getId(), "VALIDATED"));
    assertThrows(RuntimeException.class, () -> service.listByEvaluation(eval.getId()));
    verify(repo, never()).save(any());
  }

  @Test
  void listReturnsTheVerdictsOfTheEvaluation() {
    AuditControlVerdict v =
        AuditControlVerdict.builder()
            .id(UUID.randomUUID())
            .evaluation(eval)
            .control(control)
            .verdict(AuditControlVerdict.Verdict.NOT_VALIDATED)
            .build();
    when(repo.findByEvaluationId(eval.getId())).thenReturn(List.of(v));

    List<ControlVerdictDto> result = service.listByEvaluation(eval.getId());

    assertEquals(1, result.size());
    assertEquals("NOT_VALIDATED", result.get(0).verdict());
  }

  // ---- C4a: aceptar una cita valida el control (reglas de D1, D5 y D10) ----

  private java.util.Map<String, Object> citationRef() {
    return java.util.Map.of("evidenceId", UUID.randomUUID().toString(), "fileName", "politica.pdf");
  }

  private AuditControlVerdict existing(AuditControlVerdict.Verdict verdict) {
    AuditControlVerdict v =
        AuditControlVerdict.builder().evaluation(eval).control(control).verdict(verdict).build();
    when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.of(v));
    return v;
  }

  @Test
  void acceptingACitationOnAControlWithoutVerdictValidatesItWithOriginCitation() {
    when(repo.findByEvaluationIdAndControlId(any(), any())).thenReturn(Optional.empty());
    var ref = citationRef();

    var change = service.applyCitation(eval, control, ref, false);

    assertTrue(change.changed());
    assertEquals("VALIDATED", change.verdict());
    assertEquals("CITATION", change.source());
    ArgumentCaptor<AuditControlVerdict> saved = ArgumentCaptor.forClass(AuditControlVerdict.class);
    verify(repo).save(saved.capture());
    assertEquals(AuditControlVerdict.Verdict.VALIDATED, saved.getValue().getVerdict());
    assertEquals(AuditControlVerdict.Source.CITATION, saved.getValue().getSource());
    assertEquals(ref, saved.getValue().getSourceRef());
  }

  @Test
  void anAlreadyValidatedControlIsLeftAsIsAndTheCitationIsExtraBackup() {
    var manual = existing(AuditControlVerdict.Verdict.VALIDATED);

    var change = service.applyCitation(eval, control, citationRef(), false);

    assertFalse(change.changed());
    assertEquals("MANUAL", change.source(), "no se pisa el origen del dictamen existente");
    verify(repo, never()).save(any());
    verify(auditLog, never()).record(any(), any(), any());
    assertEquals(AuditControlVerdict.Source.MANUAL, manual.getSource());
  }

  @Test
  void aManualNotValidatedVerdictNeedsConfirmationBeforeBeingReplaced() {
    existing(AuditControlVerdict.Verdict.NOT_VALIDATED);

    var ex =
        assertThrows(
            uy.edu.prisma.domain.exception.VerdictOverrideRequiredException.class,
            () -> service.applyCitation(eval, control, citationRef(), false));

    assertEquals("NOT_VALIDATED", ex.getCurrentVerdict());
    assertEquals(control.getId(), ex.getControlId());
    verify(repo, never()).save(any());
  }

  @Test
  void aManualNeedsMoreEvidenceVerdictAlsoNeedsConfirmation() {
    existing(AuditControlVerdict.Verdict.NEEDS_MORE_EVIDENCE);

    assertThrows(
        uy.edu.prisma.domain.exception.VerdictOverrideRequiredException.class,
        () -> service.applyCitation(eval, control, citationRef(), false));
  }

  @Test
  void withConfirmationTheManualVerdictIsReplacedAndTheChangeIsAudited() {
    var current = existing(AuditControlVerdict.Verdict.NOT_VALIDATED);

    var change = service.applyCitation(eval, control, citationRef(), true);

    assertTrue(change.changed());
    assertEquals(AuditControlVerdict.Verdict.VALIDATED, current.getVerdict());
    assertEquals(AuditControlVerdict.Source.CITATION, current.getSource());
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(auditLog)
        .record(eq("SET_CONTROL_VERDICT"), eq("evaluation:" + eval.getId()), payload.capture());
    assertTrue(payload.getValue().contains("\"previousVerdict\":\"NOT_VALIDATED\""));
  }

  @Test
  void theAuditLogRecordsTheOriginAndTheCitation() {
    when(repo.findByEvaluationIdAndControlId(any(), any())).thenReturn(Optional.empty());

    service.applyCitation(eval, control, citationRef(), false);

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(auditLog)
        .record(eq("SET_CONTROL_VERDICT"), eq("evaluation:" + eval.getId()), payload.capture());
    assertTrue(payload.getValue().contains("\"source\":\"CITATION\""));
    assertTrue(payload.getValue().contains("\"citation\":{"));
    assertTrue(payload.getValue().contains("politica.pdf"));
    assertTrue(payload.getValue().contains("\"controlId\":\"" + control.getId() + "\""));
  }

  @Test
  void aVerdictSetByHandDropsTheCitationOrigin() {
    var fromCitation = existing(AuditControlVerdict.Verdict.VALIDATED);
    fromCitation.setSource(AuditControlVerdict.Source.CITATION);
    fromCitation.setSourceRef(citationRef());
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));

    service.set(eval.getId(), control.getId(), "NOT_VALIDATED");

    assertEquals(AuditControlVerdict.Source.MANUAL, fromCitation.getSource());
    assertNull(fromCitation.getSourceRef());
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(auditLog).record(eq("SET_CONTROL_VERDICT"), any(), payload.capture());
    assertTrue(payload.getValue().contains("\"source\":\"MANUAL\""));
  }

  @Test
  void aCitationOnAControlOutsideTheCommunityProfileIsRejected() {
    var profile =
        uy.edu.prisma.domain.entity.CommunityProfile.builder()
            .id(UUID.randomUUID())
            .controls(new java.util.HashSet<>())
            .build();
    eval.setCommunityProfile(profile);

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.applyCitation(eval, control, citationRef(), true));
    verify(repo, never()).save(any());
  }

  @Test
  void theVerdictDtoExposesTheOriginAndTheCitation() {
    var v = existing(AuditControlVerdict.Verdict.VALIDATED);
    v.setSource(AuditControlVerdict.Source.CITATION);
    var ref = citationRef();
    v.setSourceRef(ref);
    when(repo.findByEvaluationId(eval.getId())).thenReturn(java.util.List.of(v));
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    var dto = service.listByEvaluation(eval.getId()).get(0);

    assertEquals("CITATION", dto.source());
    assertEquals(ref, dto.sourceRef());
  }

  // ---- C4a completo (hito 3): revertir al rechazar y «Deshacer» ----

  private uy.edu.prisma.domain.entity.EvidenceCitationResult acceptedCitation(String id) {
    return uy.edu.prisma.domain.entity.EvidenceCitationResult.builder()
        .id(UUID.randomUUID())
        .evaluation(eval)
        .control(control)
        .fragmentId(id)
        .fileName("otra.pdf")
        .location("Página 5")
        .snippet("texto")
        .score(0.5)
        .status(uy.edu.prisma.domain.entity.EvidenceCitationResult.Status.ACCEPTED)
        .build();
  }

  private AuditControlVerdict fromCitation(UUID citationId) {
    AuditControlVerdict v = existing(AuditControlVerdict.Verdict.VALIDATED);
    v.setSource(AuditControlVerdict.Source.CITATION);
    v.setSourceRef(new java.util.HashMap<>(java.util.Map.of("citationId", citationId.toString())));
    return v;
  }

  @Test
  void rejectingTheOnlyBackingCitationRevertsAVerdictThatCameFromACitation() {
    UUID citationId = UUID.randomUUID();
    var v = fromCitation(citationId);
    when(citationStore.accepted(eval.getId(), control.getId())).thenReturn(java.util.List.of());

    var change = service.onCitationRevoked(eval, control, citationId);

    assertNull(change.verdict());
    assertTrue(change.changed());
    verify(repo).delete(v);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(auditLog)
        .record(eq("SET_CONTROL_VERDICT"), eq("evaluation:" + eval.getId()), payload.capture());
    assertTrue(payload.getValue().contains("\"reverted\":true"));
    assertTrue(payload.getValue().contains("CITATION_REJECTED"));
    assertTrue(payload.getValue().contains("\"previousVerdict\":\"VALIDATED\""));
  }

  @Test
  void aVerdictConfirmedByHandIsKeptWhenTheCitationIsRejected() {
    var manual = existing(AuditControlVerdict.Verdict.VALIDATED);

    assertNull(service.onCitationRevoked(eval, control, UUID.randomUUID()));

    verify(repo, never()).delete(any());
    assertEquals(AuditControlVerdict.Source.MANUAL, manual.getSource());
  }

  @Test
  void withoutAnyVerdictRejectingACitationDoesNothing() {
    when(repo.findByEvaluationIdAndControlId(any(), any())).thenReturn(Optional.empty());

    assertNull(service.onCitationRevoked(eval, control, UUID.randomUUID()));
    verify(repo, never()).delete(any());
  }

  @Test
  void otherAcceptedCitationsKeepTheVerdictAndTakeOverAsTheBackup() {
    UUID rejected = UUID.randomUUID();
    var v = fromCitation(rejected);
    var other = acceptedCitation("e::7");
    when(citationStore.accepted(eval.getId(), control.getId()))
        .thenReturn(java.util.List.of(other));

    assertNull(service.onCitationRevoked(eval, control, rejected));

    verify(repo, never()).delete(any());
    assertEquals(other.getId().toString(), v.getSourceRef().get("citationId"));
    assertEquals("otra.pdf", v.getSourceRef().get("fileName"));
    verify(repo).save(v);
  }

  @Test
  void ifTheBackupWasAnotherCitationItIsLeftAlone() {
    var v = fromCitation(UUID.randomUUID());
    var remaining = acceptedCitation("e::7");
    when(citationStore.accepted(eval.getId(), control.getId()))
        .thenReturn(java.util.List.of(remaining));

    assertNull(service.onCitationRevoked(eval, control, UUID.randomUUID()));

    verify(repo, never()).save(any());
    assertNotEquals(remaining.getId().toString(), v.getSourceRef().get("citationId"));
  }

  @Test
  void undoRemovesAVerdictFromACitationAndLeavesItsCitationsUndecided() {
    var v = fromCitation(UUID.randomUUID());
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(citationStore.resetAccepted(eval.getId(), control.getId())).thenReturn(2);

    service.undoCitationVerdict(eval.getId(), control.getId());

    verify(repo).delete(v);
    verify(citationStore).resetAccepted(eval.getId(), control.getId());
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(auditLog).record(eq("SET_CONTROL_VERDICT"), any(), payload.capture());
    assertTrue(payload.getValue().contains("\"reason\":\"UNDO\""));
    assertTrue(payload.getValue().contains("\"citationsReset\":2"));
  }

  @Test
  void aManualVerdictCannotBeUndoneThroughTheCitationUndo() {
    existing(AuditControlVerdict.Verdict.VALIDATED);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.ConflictException.class,
        () -> service.undoCitationVerdict(eval.getId(), control.getId()));
    verify(repo, never()).delete(any());
    verifyNoInteractions(citationStore);
  }

  @Test
  void undoWithoutAVerdictIsNotFound() {
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(repo.findByEvaluationIdAndControlId(any(), any())).thenReturn(Optional.empty());

    assertThrows(
        uy.edu.prisma.domain.exception.ResourceNotFoundException.class,
        () -> service.undoCitationVerdict(eval.getId(), control.getId()));
  }

  @Test
  void undoOnlyWorksWhileTheEvaluationIsInAudit() {
    eval.setStatus(Evaluation.Status.RETURNED);
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));

    assertThrows(
        uy.edu.prisma.domain.exception.InvalidRequestException.class,
        () -> service.undoCitationVerdict(eval.getId(), control.getId()));
  }
}
