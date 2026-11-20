package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
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
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.entity.EvidenceCitationResult.Status;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.EvidenceCitationResultRepository;
import uy.edu.prisma.web.dto.Dto.EvidenceCitationDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceCitationStoreTest {

  @Mock private EvidenceCitationResultRepository repo;
  @Mock private uy.edu.prisma.domain.repository.EvidenceRepository evidenceRepo;

  private EvidenceCitationStore store;
  private Evaluation eval;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    store = new EvidenceCitationStore(repo, evidenceRepo);
    // Todas las evidencias de los tests existen.
    when(evidenceRepo.findAllById(any()))
        .thenAnswer(
            inv -> {
              java.util.List<uy.edu.prisma.domain.entity.Evidence> alive =
                  new java.util.ArrayList<>();
              for (Object id : (Iterable<?>) inv.getArgument(0)) {
                alive.add(uy.edu.prisma.domain.entity.Evidence.builder().id((UUID) id).build());
              }
              return alive;
            });
    eval = Evaluation.builder().id(UUID.randomUUID()).name("E").build();
    control = CatalogControl.builder().id(UUID.randomUUID()).code("PR.AA-01").build();
    when(repo.saveAll(any()))
        .thenAnswer(
            inv -> {
              List<EvidenceCitationResult> rows = inv.getArgument(0);
              rows.forEach(
                  r -> {
                    if (r.getId() == null) {
                      r.setId(UUID.randomUUID());
                    }
                  });
              return rows;
            });
  }

  private EvidenceCitationDto fresh(String fragmentId, double score, String snippet) {
    return new EvidenceCitationDto(
        UUID.randomUUID(),
        "politica.pdf",
        "Página 1",
        snippet,
        score,
        fragmentId,
        null,
        null,
        null,
        null,
        false);
  }

  private EvidenceCitationResult existing(String fragmentId, Status status, int position) {
    return EvidenceCitationResult.builder()
        .id(UUID.randomUUID())
        .evaluation(eval)
        .control(control)
        .fragmentId(fragmentId)
        .snippet("viejo")
        .position(position)
        .status(status)
        .stale(true)
        .build();
  }

  private void savedAre(EvidenceCitationResult... rows) {
    when(repo.findByEvaluationIdAndControlIdOrderByPositionAsc(eval.getId(), control.getId()))
        .thenReturn(List.of(rows));
  }

  @Test
  void firstSearchSavesEverythingAsPendingInOrder() {
    savedAre();

    List<EvidenceCitationResult> result =
        store.merge(eval, control, List.of(fresh("e::0", 0.9, "a"), fresh("e::3", 0.5, "b")));

    assertEquals(2, result.size());
    assertEquals(Status.PENDING, result.get(0).getStatus());
    assertEquals(0, result.get(0).getPosition());
    assertEquals(1, result.get(1).getPosition());
    assertEquals("e::3", result.get(1).getFragmentId());
    assertEquals(eval, result.get(0).getEvaluation());
    assertEquals(control, result.get(0).getControl());
  }

  @Test
  void searchingAgainKeepsTheDecisionOfFragmentsThatStillAppear() {
    EvidenceCitationResult accepted = existing("e::0", Status.ACCEPTED, 0);
    EvidenceCitationResult rejected = existing("e::3", Status.REJECTED, 1);
    savedAre(accepted, rejected);

    // El orden cambió: el que estaba segundo ahora es primero.
    List<EvidenceCitationResult> result =
        store.merge(
            eval, control, List.of(fresh("e::3", 0.95, "nuevo texto"), fresh("e::0", 0.6, "x")));

    assertSame(rejected, result.get(0));
    assertEquals(Status.REJECTED, result.get(0).getStatus());
    assertEquals(0, result.get(0).getPosition());
    assertEquals(0.95, result.get(0).getScore());
    assertEquals("nuevo texto", result.get(0).getSnippet());
    assertSame(accepted, result.get(1));
    assertEquals(Status.ACCEPTED, result.get(1).getStatus());
    assertEquals(1, result.get(1).getPosition());
    assertFalse(result.get(0).isStale());
    assertFalse(result.get(1).isStale());
  }

  @Test
  void aNewFragmentEntersPendingAndOneThatVanishedIsDropped() {
    EvidenceCitationResult stays = existing("e::0", Status.ACCEPTED, 0);
    EvidenceCitationResult gone = existing("e::9", Status.ACCEPTED, 1);
    savedAre(stays, gone);

    List<EvidenceCitationResult> result =
        store.merge(eval, control, List.of(fresh("e::0", 0.9, "a"), fresh("e::5", 0.4, "b")));

    assertEquals(Status.ACCEPTED, result.get(0).getStatus());
    assertEquals(Status.PENDING, result.get(1).getStatus());
    ArgumentCaptor<List<EvidenceCitationResult>> deleted = ArgumentCaptor.forClass(List.class);
    verify(repo).deleteAll(deleted.capture());
    assertEquals(List.of(gone), deleted.getValue());
  }

  @Test
  void theVanishedAreDeletedBeforeTheInsertToAvoidTheUniqueIndex() {
    savedAre(existing("e::9", Status.PENDING, 0));

    store.merge(eval, control, List.of(fresh("e::9x", 0.9, "a")));

    var order = inOrder(repo);
    order.verify(repo).deleteAll(any());
    order.verify(repo).flush();
    order.verify(repo).saveAll(any());
  }

  @Test
  void fragmentsWithoutAnIdCannotBeMatchedAndAreRecreated() {
    // backend-ai viejo (sin fragment_id): no se puede conservar nada, pero no se rompe.
    EvidenceCitationResult old = existing(null, Status.ACCEPTED, 0);
    savedAre(old);

    List<EvidenceCitationResult> result =
        store.merge(eval, control, List.of(fresh(null, 0.9, "a")));

    assertEquals(Status.PENDING, result.get(0).getStatus());
    verify(repo).deleteAll(List.of(old));
  }

  @Test
  void aFragmentWhoseEvidenceWasDeletedMeanwhileIsNotSaved() {
    savedAre();
    UUID gone = UUID.randomUUID();
    doReturn(List.of()).when(evidenceRepo).findAllById(any());

    List<EvidenceCitationResult> result =
        store.merge(
            eval,
            control,
            List.of(
                new EvidenceCitationDto(
                    gone, "a.pdf", "p", "t", 0.9, "e::0", null, null, null, null, false),
                new EvidenceCitationDto(
                    null, "b.pdf", "p", "t", 0.8, "e::1", null, null, null, null, false)));

    assertEquals(1, result.size());
    assertEquals("e::1", result.get(0).getFragmentId());
  }

  @Test
  void invalidatingAControlDropsTheUndecidedAndMarksTheDecidedStale() {
    store.invalidateControl(eval.getId(), control.getId());

    verify(repo).deleteByControlAndStatus(eval.getId(), control.getId(), Status.PENDING);
    verify(repo).markDecidedStale(eval.getId(), control.getId(), Status.PENDING);
  }

  @Test
  void leavingTheAuditDropsEverythingOfTheEvaluation() {
    when(repo.deleteByEvaluation(eval.getId())).thenReturn(7);

    assertEquals(7, store.invalidateEvaluation(eval.getId()));
  }

  @Test
  void decidingACitationRecordsStatusAuthorAndPreviousStatus() {
    EvidenceCitationResult r = existing("e::0", Status.PENDING, 0);
    when(repo.findById(r.getId())).thenReturn(Optional.of(r));
    User auditor = User.builder().id(UUID.randomUUID()).build();

    var decided = store.decide(r.getId(), eval.getId(), control.getId(), Status.ACCEPTED, auditor);

    assertEquals(Status.PENDING, decided.previous());
    assertEquals(Status.ACCEPTED, r.getStatus());
    assertEquals(auditor, r.getDecidedBy());
    assertNotNull(r.getDecidedAt());
    verify(repo).save(r);
  }

  @Test
  void decidingACitationOfAnotherControlOrEvaluationIsRejected() {
    EvidenceCitationResult r = existing("e::0", Status.PENDING, 0);
    when(repo.findById(r.getId())).thenReturn(Optional.of(r));

    assertThrows(
        InvalidRequestException.class,
        () -> store.decide(r.getId(), UUID.randomUUID(), control.getId(), Status.ACCEPTED, null));
    assertThrows(
        InvalidRequestException.class,
        () -> store.decide(r.getId(), eval.getId(), UUID.randomUUID(), Status.ACCEPTED, null));
    verify(repo, never()).save(any());
  }

  @Test
  void decidingAnUnknownCitationIsNotFound() {
    UUID id = UUID.randomUUID();
    when(repo.findById(id)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> store.decide(id, eval.getId(), control.getId(), Status.REJECTED, null));
  }

  @Test
  void undoingAVerdictLeavesItsAcceptedCitationsUndecided() {
    EvidenceCitationResult a = existing("e::0", Status.ACCEPTED, 0);
    a.setDecidedBy(User.builder().id(UUID.randomUUID()).build());
    when(repo.findByEvaluationIdAndControlIdAndStatus(
            eval.getId(), control.getId(), Status.ACCEPTED))
        .thenReturn(List.of(a));

    assertEquals(1, store.resetAccepted(eval.getId(), control.getId()));

    assertEquals(Status.PENDING, a.getStatus());
    assertNull(a.getDecidedBy());
    assertNull(a.getDecidedAt());
  }

  @Test
  void theDtoCarriesTheIdsStatusPositionAndControl() {
    EvidenceCitationResult r = existing("e::2", Status.ACCEPTED, 3);
    r.setScore(null);

    EvidenceCitationDto dto = EvidenceCitationStore.toDto(r);

    assertEquals(r.getId(), dto.citationId());
    assertEquals("e::2", dto.fragmentId());
    assertEquals("ACCEPTED", dto.status());
    assertEquals(3, dto.position());
    assertEquals(control.getId(), dto.controlId());
    assertEquals(0.0, dto.score());
    assertTrue(dto.stale());
  }
}
