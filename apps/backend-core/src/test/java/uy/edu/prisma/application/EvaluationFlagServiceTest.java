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
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationControlFlag;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationControlFlagRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.ControlFlagDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationFlagServiceTest {

  @Mock private EvaluationControlFlagRepository repo;
  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CurrentUserService currentUser;

  private EvaluationFlagService service;
  private Evaluation eval;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service = new EvaluationFlagService(repo, evaluationRepo, controlRepo, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .status(Evaluation.Status.IN_PROGRESS)
            .build();
    control = CatalogControl.builder().id(UUID.randomUUID()).code("C1").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findById(control.getId())).thenReturn(Optional.of(control));
    when(currentUser.currentUser()).thenReturn(Optional.empty());
    when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.empty());
  }

  @Test
  void flaggingCreatesTheMarkWithTheCurrentUser() {
    User who = User.builder().id(UUID.randomUUID()).firstName("Ana").lastName("P").build();
    when(currentUser.currentUser()).thenReturn(Optional.of(who));

    service.setFlagged(eval.getId(), control.getId(), true);

    ArgumentCaptor<EvaluationControlFlag> captor =
        ArgumentCaptor.forClass(EvaluationControlFlag.class);
    verify(repo).save(captor.capture());
    assertEquals(control, captor.getValue().getControl());
    assertEquals(eval, captor.getValue().getEvaluation());
    assertEquals(who, captor.getValue().getFlaggedBy());
  }

  @Test
  void flaggingAnAlreadyFlaggedControlIsIdempotent() {
    when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.of(EvaluationControlFlag.builder().build()));

    service.setFlagged(eval.getId(), control.getId(), true);

    verify(repo, never()).save(any());
  }

  @Test
  void unflaggingDeletesAnyoneSMark() {
    // La marca puede haberla puesto otra persona: desmarca igual (cualquier rol de trabajo).
    EvaluationControlFlag existing =
        EvaluationControlFlag.builder()
            .flaggedBy(User.builder().id(UUID.randomUUID()).build())
            .build();
    when(repo.findByEvaluationIdAndControlId(eval.getId(), control.getId()))
        .thenReturn(Optional.of(existing));

    service.setFlagged(eval.getId(), control.getId(), false);

    verify(repo).delete(existing);
  }

  @Test
  void unflaggingWhenNotFlaggedDoesNothing() {
    service.setFlagged(eval.getId(), control.getId(), false);

    verify(repo, never()).delete(any());
    verify(repo, never()).save(any());
  }

  @Test
  void allowedWhileEditableAndDuringAuditButNotAfterwards() {
    for (Evaluation.Status s :
        List.of(
            Evaluation.Status.DRAFT,
            Evaluation.Status.IN_PROGRESS,
            Evaluation.Status.RETURNED,
            Evaluation.Status.IN_AUDIT)) {
      eval.setStatus(s);
      assertDoesNotThrow(() -> service.setFlagged(eval.getId(), control.getId(), true), "" + s);
    }
    for (Evaluation.Status s :
        List.of(
            Evaluation.Status.READY_FOR_AUDIT,
            Evaluation.Status.APPROVED,
            Evaluation.Status.ARCHIVED)) {
      eval.setStatus(s);
      assertThrows(
          InvalidRequestException.class,
          () -> service.setFlagged(eval.getId(), control.getId(), true),
          "" + s);
    }
  }

  @Test
  void rejectedWhenControlOutsideCommunityProfile() {
    CatalogControl other = CatalogControl.builder().id(UUID.randomUUID()).code("OUT").build();
    eval.setCommunityProfile(
        CommunityProfile.builder().id(UUID.randomUUID()).controls(Set.of(other)).build());

    assertThrows(
        InvalidRequestException.class,
        () -> service.setFlagged(eval.getId(), control.getId(), true));
    verify(repo, never()).save(any());
  }

  @Test
  void rejectedWhenOrganizationAccessDenied() {
    doThrow(new RuntimeException("403")).when(currentUser).assertOrganizationAccess(any());

    assertThrows(
        RuntimeException.class, () -> service.setFlagged(eval.getId(), control.getId(), true));
    assertThrows(RuntimeException.class, () -> service.list(eval.getId()));
    verify(repo, never()).save(any());
  }

  @Test
  void throwsWhenEvaluationOrControlMissing() {
    UUID missing = UUID.randomUUID();
    when(evaluationRepo.findById(missing)).thenReturn(Optional.empty());
    when(controlRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class, () -> service.setFlagged(missing, control.getId(), true));
    assertThrows(
        ResourceNotFoundException.class, () -> service.setFlagged(eval.getId(), missing, true));
  }

  @Test
  void listReturnsEveryFlagWithWhoPutIt() {
    User who = User.builder().id(UUID.randomUUID()).firstName("Ana").lastName("Pérez").build();
    when(repo.findByEvaluationId(eval.getId()))
        .thenReturn(
            List.of(
                EvaluationControlFlag.builder().control(control).flaggedBy(who).build(),
                EvaluationControlFlag.builder().control(control).build()));

    List<ControlFlagDto> result = service.list(eval.getId());

    assertEquals(2, result.size());
    assertEquals(control.getId(), result.get(0).controlId());
    assertEquals("Ana Pérez", result.get(0).flaggedByName());
    assertNull(result.get(1).flaggedByName());
  }
}
