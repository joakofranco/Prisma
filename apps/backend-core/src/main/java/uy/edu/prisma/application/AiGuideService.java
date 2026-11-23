package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.AiJobProperties;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;
import uy.edu.prisma.domain.entity.AiGeneratedGuide.Status;
import uy.edu.prisma.domain.entity.AiSuggestionFeedback;
import uy.edu.prisma.domain.entity.AiSuggestionFeedback.Outcome;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AiGeneratedGuideRepository;
import uy.edu.prisma.domain.repository.AiSuggestionFeedbackRepository;

/**
 * Guías de IA persistidas (C5) y su feedback en el servidor. El feedback sigue siendo anónimo
 * (V32): {@link AiSuggestionFeedback} no lleva organización ni usuario aunque la guía sí.
 *
 * <ul>
 *   <li>Al crear la acción con la guía: USED + ACCEPTED_UNEDITED/ACCEPTED_EDITED, en la misma
 *       transacción que crea la acción.
 *   <li>Mostrada y no usada a los 30 días (D11): EXPIRED + REJECTED.
 *   <li>Nunca mostrada a los 30 días: EXPIRED sin feedback.
 * </ul>
 */
@Service
public class AiGuideService {

  private static final Logger log = LoggerFactory.getLogger(AiGuideService.class);

  private final AiGeneratedGuideRepository guideRepo;
  private final AiSuggestionFeedbackRepository feedbackRepo;
  private final CurrentUserService currentUser;
  private final AiJobProperties props;

  public AiGuideService(
      AiGeneratedGuideRepository guideRepo,
      AiSuggestionFeedbackRepository feedbackRepo,
      CurrentUserService currentUser,
      AiJobProperties props) {
    this.guideRepo = guideRepo;
    this.feedbackRepo = feedbackRepo;
    this.currentUser = currentUser;
    this.props = props;
  }

  /**
   * Marca la guía como usada al crear una acción con ella y registra el feedback.
   *
   * @param controlId control de la acción (si viene, tiene que ser el de la guía)
   * @param finalText texto final de la acción; solo se guarda si la persona editó la guía
   * @return id de la traza del modo investigación de la guía (C8), o null si no hay
   */
  @Transactional
  public UUID markUsed(UUID guideId, UUID controlId, boolean edited, String finalText) {
    AiGeneratedGuide guide =
        guideRepo
            .findById(guideId)
            .orElseThrow(() -> new ResourceNotFoundException("Guía de IA", guideId));
    assertAccess(guide);
    if (controlId != null && !controlId.equals(guide.getControl().getId())) {
      throw new InvalidRequestException("La guía no corresponde al control de la acción");
    }
    if (guide.getStatus() == Status.USED) {
      throw new InvalidRequestException("Esta guía ya se usó en otra acción");
    }
    if (guide.getStatus() == Status.EXPIRED) {
      throw new InvalidRequestException("La guía venció: generá una nueva");
    }
    boolean hasText = finalText != null && !finalText.isBlank();
    boolean editedWithText = edited && hasText;
    Outcome outcome = editedWithText ? Outcome.ACCEPTED_EDITED : Outcome.ACCEPTED_UNEDITED;
    guide.setStatus(Status.USED);
    guide.setUsedAt(OffsetDateTime.now());
    guideRepo.save(guide);
    feedbackRepo.save(feedbackOf(guide, outcome, editedWithText ? finalText : null));
    return guide.getTraceId();
  }

  private void assertAccess(AiGeneratedGuide guide) {
    if (guide.getOrganizationId() != null) {
      currentUser.assertOrganizationAccess(guide.getOrganizationId());
      return;
    }
    // Guía sin organización (PRISMA_ADMIN sin contexto): solo quien la pidió o un admin.
    if (!currentUser.isPrismaAdmin()
        && !guide.getRequestedBy().equals(currentUser.currentUserId())) {
      currentUser.assertOrganizationAccess(null);
    }
  }

  /** Cierra las guías abiertas con más de {@code guideFeedbackDays} días. Devuelve cuántas. */
  @Transactional
  public int expireOpenGuides() {
    OffsetDateTime before = OffsetDateTime.now().minusDays(props.guideFeedbackDays());
    List<AiGeneratedGuide> open = guideRepo.findOpenOlderThan(before);
    int rejected = 0;
    for (AiGeneratedGuide guide : open) {
      if (guide.getStatus() == Status.SHOWN) {
        feedbackRepo.save(feedbackOf(guide, Outcome.REJECTED, null));
        rejected++;
      }
      guide.setStatus(Status.EXPIRED);
    }
    guideRepo.saveAll(open);
    if (!open.isEmpty()) {
      log.info("Guías de IA vencidas: {} ({} registradas como rechazadas)", open.size(), rejected);
    }
    return open.size();
  }

  private static AiSuggestionFeedback feedbackOf(
      AiGeneratedGuide guide, Outcome outcome, String editedText) {
    return AiSuggestionFeedback.builder()
        .control(guide.getControl())
        .currentLevel(guide.getCurrentLevel())
        .targetLevel(guide.getTargetLevel())
        .originalSummary(guide.getSummary() == null ? "" : guide.getSummary())
        .originalTips(guide.getTips())
        .outcome(outcome)
        .editedText(editedText)
        .build();
  }
}
