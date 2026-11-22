package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
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
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiGeneratedGuide.Status;
import uy.edu.prisma.domain.entity.AiSuggestionFeedback;
import uy.edu.prisma.domain.entity.AiSuggestionFeedback.Outcome;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiSuggestionFeedbackRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiGuideServiceTest {

  @Mock private AiGeneratedGuideRepository guideRepo;
  @Mock private AiSuggestionFeedbackRepository feedbackRepo;
  @Mock private CurrentUserService currentUser;

  private AiGuideService service;
  private CatalogControl control;
  private final UUID orgId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new AiGuideService(guideRepo, feedbackRepo, currentUser, new AiJobProperties());
    control = CatalogControl.builder().id(UUID.randomUUID()).code("PR.AA-01").build();
  }

  private AiGeneratedGuide guide(Status status) {
    AiGeneratedGuide g =
        AiGeneratedGuide.builder()
            .id(UUID.randomUUID())
            .organizationId(orgId)
            .requestedBy(UUID.randomUUID())
            .control(control)
            .currentLevel(0)
            .targetLevel(2)
            .summary("Resumen original")
            .tips(List.of("Paso 1", "Paso 2"))
            .status(status)
            .createdAt(OffsetDateTime.now().minusDays(31))
            .build();
    when(guideRepo.findById(g.getId())).thenReturn(Optional.of(g));
    return g;
  }

  private AiSuggestionFeedback savedFeedback() {
    ArgumentCaptor<AiSuggestionFeedback> captor =
        ArgumentCaptor.forClass(AiSuggestionFeedback.class);
    verify(feedbackRepo).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void usingAGuideUneditedRecordsAcceptedUnedited() {
    AiGeneratedGuide g = guide(Status.SHOWN);

    service.markUsed(g.getId(), control.getId(), false, "texto de la acción");

    assertEquals(Status.USED, g.getStatus());
    assertNotNull(g.getUsedAt());
    AiSuggestionFeedback fb = savedFeedback();
    assertEquals(Outcome.ACCEPTED_UNEDITED, fb.getOutcome());
    assertNull(fb.getEditedText());
    assertEquals("Resumen original", fb.getOriginalSummary());
    assertEquals(List.of("Paso 1", "Paso 2"), fb.getOriginalTips());
    assertEquals(control, fb.getControl());
    verify(currentUser).assertOrganizationAccess(orgId);
  }

  @Test
  void usingAnEditedGuideStoresTheFinalText() {
    AiGeneratedGuide g = guide(Status.SHOWN);

    service.markUsed(g.getId(), control.getId(), true, "texto final editado");

    AiSuggestionFeedback fb = savedFeedback();
    assertEquals(Outcome.ACCEPTED_EDITED, fb.getOutcome());
    assertEquals("texto final editado", fb.getEditedText());
  }

  @Test
  void anEditedFlagWithoutTextCountsAsUnedited() {
    AiGeneratedGuide g = guide(Status.SHOWN);

    service.markUsed(g.getId(), control.getId(), true, "  ");

    assertEquals(Outcome.ACCEPTED_UNEDITED, savedFeedback().getOutcome());
  }

  @Test
  void theFeedbackNeverCarriesOrganizationOrUser() {
    // V32: el dataset es anónimo. La entidad de feedback no tiene esos campos.
    for (var field : AiSuggestionFeedback.class.getDeclaredFields()) {
      String name = field.getName().toLowerCase();
      assertFalse(name.contains("organization") || name.contains("user"), name);
    }
  }

  @Test
  void aGuideCannotBeUsedTwice() {
    AiGeneratedGuide g = guide(Status.USED);

    assertThrows(
        InvalidRequestException.class,
        () -> service.markUsed(g.getId(), control.getId(), false, "x"));
    verify(feedbackRepo, never()).save(any());
  }

  @Test
  void anExpiredGuideCannotBeUsed() {
    AiGeneratedGuide g = guide(Status.EXPIRED);

    assertThrows(
        InvalidRequestException.class,
        () -> service.markUsed(g.getId(), control.getId(), false, "x"));
  }

  @Test
  void theGuideMustBelongToTheControlOfTheAction() {
    AiGeneratedGuide g = guide(Status.SHOWN);

    assertThrows(
        InvalidRequestException.class,
        () -> service.markUsed(g.getId(), UUID.randomUUID(), false, "x"));
    verify(feedbackRepo, never()).save(any());
  }

  @Test
  void aMissingControlOnTheActionIsAccepted() {
    AiGeneratedGuide g = guide(Status.SHOWN);

    assertDoesNotThrow(() -> service.markUsed(g.getId(), null, false, "x"));
  }

  @Test
  void usingAGuideOfAnotherOrganizationIsDenied() {
    AiGeneratedGuide g = guide(Status.SHOWN);
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(orgId);

    assertThrows(
        AccessDeniedException.class,
        () -> service.markUsed(g.getId(), control.getId(), false, "x"));
    verify(feedbackRepo, never()).save(any());
  }

  @Test
  void anUnknownGuideIsNotFound() {
    UUID id = UUID.randomUUID();
    when(guideRepo.findById(id)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.markUsed(id, null, false, "x"));
  }

  @Test
  void aGuideWithoutOrganizationIsOnlyUsableByItsRequesterOrAnAdmin() {
    AiGeneratedGuide g = guide(Status.SHOWN);
    g.setOrganizationId(null);
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(null);

    assertThrows(
        AccessDeniedException.class,
        () -> service.markUsed(g.getId(), control.getId(), false, "x"));

    when(currentUser.currentUserId()).thenReturn(g.getRequestedBy());
    assertDoesNotThrow(() -> service.markUsed(g.getId(), control.getId(), false, "x"));
  }

  // ----------------------------------------------------------------- vencimiento

  @Test
  void shownAndUnusedGuidesExpireAsRejectedAfterThirtyDays() {
    AiGeneratedGuide shown = guide(Status.SHOWN);
    when(guideRepo.findOpenOlderThan(any())).thenReturn(List.of(shown));

    assertEquals(1, service.expireOpenGuides());

    assertEquals(Status.EXPIRED, shown.getStatus());
    assertEquals(Outcome.REJECTED, savedFeedback().getOutcome());
  }

  @Test
  void neverShownGuidesExpireWithoutFeedback() {
    AiGeneratedGuide neverShown = guide(Status.GENERATED);
    when(guideRepo.findOpenOlderThan(any())).thenReturn(List.of(neverShown));

    assertEquals(1, service.expireOpenGuides());

    assertEquals(Status.EXPIRED, neverShown.getStatus());
    verify(feedbackRepo, never()).save(any());
  }

  @Test
  void theExpiryCutoffIsThirtyDaysByDefault() {
    when(guideRepo.findOpenOlderThan(any())).thenReturn(List.of());

    service.expireOpenGuides();

    ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(guideRepo).findOpenOlderThan(cutoff.capture());
    OffsetDateTime expected = OffsetDateTime.now().minusDays(30);
    assertTrue(Math.abs(cutoff.getValue().toEpochSecond() - expected.toEpochSecond()) < 5);
  }
}
