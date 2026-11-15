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

  private AuditVerdictService service;
  private Evaluation eval;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service = new AuditVerdictService(repo, evaluationRepo, controlRepo, currentUser, auditLog);
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
}
