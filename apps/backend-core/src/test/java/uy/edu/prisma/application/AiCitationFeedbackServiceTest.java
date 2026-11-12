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
import uy.edu.prisma.domain.entity.AiCitationFeedback;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiCitationFeedbackRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto;
import uy.edu.prisma.web.dto.Dto.CitationFeedbackItemDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiCitationFeedbackServiceTest {

  @Mock private EvaluationRepository evaluationRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private AiCitationFeedbackRepository feedbackRepo;
  @Mock private CurrentUserService currentUser;

  private AiCitationFeedbackService service;
  private Evaluation eval;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service = new AiCitationFeedbackService(evaluationRepo, controlRepo, feedbackRepo, currentUser);
    eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("E")
            .organization(Organization.builder().id(UUID.randomUUID()).build())
            .status(Evaluation.Status.IN_AUDIT)
            .build();
    control = CatalogControl.builder().id(UUID.randomUUID()).code("CA.1-11").build();
    when(evaluationRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    when(controlRepo.findAllById(any())).thenReturn(List.of(control));
  }

  private CitationFeedbackItemDto item(String decision) {
    return new CitationFeedbackItemDto(
        control.getId(), "politica.pdf", "Página 2", "texto citado", 0.71, decision);
  }

  @Test
  void savesAcceptedAndRejectedCitationsWithTheControlAndTheFragment() {
    service.record(
        new CitationFeedbackBatchDto(eval.getId(), List.of(item("ACCEPTED"), item("REJECTED"))));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<AiCitationFeedback>> captor = ArgumentCaptor.forClass(List.class);
    verify(feedbackRepo).saveAll(captor.capture());
    var rows = captor.getValue();
    assertEquals(2, rows.size());
    assertEquals(control, rows.get(0).getControl());
    assertEquals("politica.pdf", rows.get(0).getFileName());
    assertEquals("Página 2", rows.get(0).getLocation());
    assertEquals("texto citado", rows.get(0).getSnippet());
    assertEquals(0.71, rows.get(0).getScore());
    assertEquals(AiCitationFeedback.Decision.ACCEPTED, rows.get(0).getDecision());
    assertEquals(AiCitationFeedback.Decision.REJECTED, rows.get(1).getDecision());
  }

  @Test
  void checksAccessToTheEvaluationsOrganization() {
    service.record(new CitationFeedbackBatchDto(eval.getId(), List.of(item("ACCEPTED"))));

    verify(currentUser).assertOrganizationAccess(eval.getOrganization().getId());
  }

  @Test
  void onlyWorksWhileTheEvaluationIsInAudit() {
    for (Evaluation.Status status : Evaluation.Status.values()) {
      if (status == Evaluation.Status.IN_AUDIT) {
        continue;
      }
      eval.setStatus(status);
      var ex =
          assertThrows(
              ConflictException.class,
              () ->
                  service.record(
                      new CitationFeedbackBatchDto(eval.getId(), List.of(item("ACCEPTED")))),
              status.name());
      assertTrue(ex.getMessage().contains(status.name()));
    }
    verify(feedbackRepo, never()).saveAll(any());
  }

  @Test
  void unknownEvaluationIsNotFound() {
    UUID other = UUID.randomUUID();
    when(evaluationRepo.findById(other)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.record(new CitationFeedbackBatchDto(other, List.of(item("ACCEPTED")))));
  }

  @Test
  void unknownControlIsNotFoundAndNothingIsSaved() {
    when(controlRepo.findAllById(any())).thenReturn(List.of());

    assertThrows(
        ResourceNotFoundException.class,
        () ->
            service.record(new CitationFeedbackBatchDto(eval.getId(), List.of(item("ACCEPTED")))));
    verify(feedbackRepo, never()).saveAll(any());
  }
}
