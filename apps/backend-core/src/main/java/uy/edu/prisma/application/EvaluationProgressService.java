package uy.edu.prisma.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.web.dto.Dto.ControlChangeDto;
import uy.edu.prisma.web.dto.Dto.EvaluationAuditCycleEventDto;
import uy.edu.prisma.web.dto.Dto.EvaluationProgressDto;
import uy.edu.prisma.web.dto.Dto.ProgressPeriodDto;
import uy.edu.prisma.web.dto.Dto.ProgressStatusEventDto;

/**
 * RF-VIZ-07 (PRI-321): avance por día, semana o mes de UNA evaluación, también mientras está en
 * curso o en auditoría (que pueden llevar meses).
 *
 * <p>Se reconstruye con la bitácora que ya existe: cada {@code SAVE_RESPONSE} guarda el control y
 * si quedó «Cumple» o no, con su fecha y su autor. Recorriendo esos eventos en orden se sabe
 * cuántos controles cumplía la organización al final de cada semana y qué controles cambiaron. Un
 * control respondido sin evento en la bitácora (anterior a ella) entra con su respuesta actual en
 * la fecha de esa respuesta. Repetir el mismo valor no es un cambio. Solo cuentan los controles del
 * alcance de la evaluación (su perfil o todo el catálogo).
 *
 * <p>Multitenant: un ORG_RESPONSIBLE solo ve evaluaciones de su organización. Solo lee.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationProgressService {

  static final String NO_FUNCTION = "Sin función";

  private static final Pattern CONTROL_ID =
      Pattern.compile("\"controlId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static final Pattern COMPLIANT = Pattern.compile("\"compliant\"\\s*:\\s*(true|false)");

  private final EvaluationRepository evalRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationResponseRepository responseRepo;
  private final AuditLogRepository auditRepo;
  private final EvidenceRepository evidenceRepo;
  private final AuditLogService auditLogService;
  private final UserRepository userRepo;
  private final CurrentUserService currentUser;

  public EvaluationProgressService(
      EvaluationRepository evalRepo,
      CatalogControlRepository controlRepo,
      EvaluationResponseRepository responseRepo,
      AuditLogRepository auditRepo,
      EvidenceRepository evidenceRepo,
      AuditLogService auditLogService,
      UserRepository userRepo,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.controlRepo = controlRepo;
    this.responseRepo = responseRepo;
    this.auditRepo = auditRepo;
    this.evidenceRepo = evidenceRepo;
    this.auditLogService = auditLogService;
    this.userRepo = userRepo;
    this.currentUser = currentUser;
  }

  /** Un cambio de respuesta reconstruido (de la bitácora o de la respuesta actual). */
  private record Change(UUID controlId, boolean compliant, OffsetDateTime at, UUID userId) {}

  /**
   * Escala del eje de tiempo. AUTO elige según lo que dura la historia: hasta 2 semanas, días;
   * hasta 6 meses, semanas; más, meses.
   */
  public enum Granularity {
    AUTO,
    DAY,
    WEEK,
    MONTH
  }

  public EvaluationProgressDto progress(UUID evaluationId) {
    return progress(evaluationId, Granularity.AUTO);
  }

  public EvaluationProgressDto progress(UUID evaluationId, Granularity requested) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    assertCanSee(eval);

    Map<UUID, CatalogControl> scope = scope(eval);
    List<Change> changes = changes(eval, scope.keySet());
    Map<UUID, String> userNames = userNames(changes);
    ZoneId zone = ZoneId.systemDefault();
    List<LocalDate> evidenceDays =
        evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(eval.getId()).stream()
            .map(Evidence::getUploadedAt)
            .filter(Objects::nonNull)
            .map(at -> at.atZoneSameInstant(zone).toLocalDate())
            .toList();

    OffsetDateTime end =
        eval.getApprovedAt() != null && ReportService.isReportable(eval)
            ? eval.getApprovedAt()
            : OffsetDateTime.now();
    LocalDate endDay = end.atZoneSameInstant(zone).toLocalDate();
    Granularity granularity = Granularity.WEEK;
    List<ProgressPeriodDto> periods = new ArrayList<>();
    Map<UUID, Boolean> state = new HashMap<>();
    if (!changes.isEmpty()) {
      LocalDate firstDay = changes.get(0).at().atZoneSameInstant(zone).toLocalDate();
      granularity = resolve(requested, firstDay, endDay);
      // Un período vacío antes del primero: la curva arranca en 0 y se ve el salto aunque todo se
      // haya cargado en un solo día.
      LocalDate first = periodStart(firstDay, granularity);
      LocalDate previous = previous(first, granularity);
      periods.add(
          new ProgressPeriodDto(
              previous,
              next(previous, granularity).minusDays(1),
              0,
              0,
              scope.size(),
              0,
              0,
              List.of(),
              List.of()));
      LocalDate periodStart = first;
      LocalDate last = periodStart(endDay, granularity);
      int i = 0;
      while (!periodStart.isAfter(last)) {
        LocalDate next = next(periodStart, granularity);
        List<ControlChangeDto> gained = new ArrayList<>();
        List<ControlChangeDto> lost = new ArrayList<>();
        int responses = 0;
        while (i < changes.size()
            && changes.get(i).at().atZoneSameInstant(zone).toLocalDate().isBefore(next)) {
          Change c = changes.get(i++);
          responses++;
          Boolean before = state.put(c.controlId(), c.compliant());
          boolean wasCompliant = Boolean.TRUE.equals(before);
          if (c.compliant() && !wasCompliant) {
            gained.add(toDto(c, scope, userNames));
          } else if (!c.compliant() && wasCompliant) {
            lost.add(toDto(c, scope, userNames));
          }
        }
        LocalDate from = periodStart;
        int evidences =
            (int) evidenceDays.stream().filter(d -> !d.isBefore(from) && d.isBefore(next)).count();
        periods.add(
            new ProgressPeriodDto(
                periodStart,
                next.minusDays(1),
                count(state, true),
                state.size(),
                scope.size(),
                responses,
                evidences,
                gained,
                lost));
        periodStart = next;
      }
    }

    int compliantNow = count(state, true);
    int compliant30DaysAgo = compliantAt(changes, end.minusDays(30));
    return new EvaluationProgressDto(
        eval.getId(),
        eval.getName(),
        eval.getStatus().name(),
        ReportService.statusLabel(eval.getStatus()),
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getName()
            : MaturityTrendService.FULL_CATALOG,
        changes.isEmpty() ? null : changes.get(0).at(),
        compliantNow,
        state.size(),
        scope.size(),
        compliantNow - compliant30DaysAgo,
        state.size() - answeredAt(changes, end.minusDays(30)),
        granularity.name(),
        periods,
        statusEvents(eval));
  }

  static Granularity resolve(Granularity requested, LocalDate first, LocalDate last) {
    if (requested != null && requested != Granularity.AUTO) {
      return requested;
    }
    long days = java.time.temporal.ChronoUnit.DAYS.between(first, last);
    if (days <= 14) return Granularity.DAY;
    if (days <= 183) return Granularity.WEEK;
    return Granularity.MONTH;
  }

  static LocalDate periodStart(LocalDate day, Granularity g) {
    return switch (g) {
      case DAY -> day;
      case MONTH -> day.withDayOfMonth(1);
      default -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    };
  }

  private static LocalDate next(LocalDate start, Granularity g) {
    return switch (g) {
      case DAY -> start.plusDays(1);
      case MONTH -> start.plusMonths(1);
      default -> start.plusWeeks(1);
    };
  }

  private static LocalDate previous(LocalDate start, Granularity g) {
    return switch (g) {
      case DAY -> start.minusDays(1);
      case MONTH -> start.minusMonths(1);
      default -> start.minusWeeks(1);
    };
  }

  /** El responsable solo ve evaluaciones de su organización; el administrador, cualquiera. */
  private void assertCanSee(Evaluation eval) {
    UUID organizationId = eval.getOrganization().getId();
    if (!currentUser.isPrismaAdmin() && !organizationId.equals(currentUser.currentTenantId())) {
      throw new AccessDeniedException(
          "Solo podés ver el avance de las evaluaciones de tu organización");
    }
    currentUser.assertOrganizationAccess(organizationId);
  }

  private Map<UUID, CatalogControl> scope(Evaluation eval) {
    Set<UUID> profileControls =
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getControls().stream()
                .map(CatalogControl::getId)
                .collect(Collectors.toSet())
            : null;
    Map<UUID, CatalogControl> scope = new HashMap<>();
    for (CatalogControl c : controlRepo.findByVersionOrdered(eval.getCatalogVersion())) {
      if (profileControls == null || profileControls.contains(c.getId())) {
        scope.put(c.getId(), c);
      }
    }
    return scope;
  }

  /** Cambios en orden cronológico: los de la bitácora más los de respuestas sin bitácora. */
  private List<Change> changes(Evaluation eval, Set<UUID> scope) {
    List<Change> changes = new ArrayList<>();
    Set<UUID> logged = new java.util.HashSet<>();
    for (AuditLog log :
        auditRepo.findByResourceAndActionInOrderByCreatedAtAsc(
            "evaluation:" + eval.getId(), List.of("SAVE_RESPONSE"))) {
      UUID controlId = parseControl(log.getPayload());
      java.util.Optional<Boolean> parsed = parseCompliant(log.getPayload());
      if (controlId == null || parsed.isEmpty() || !scope.contains(controlId)) {
        continue;
      }
      boolean compliant = parsed.get();
      logged.add(controlId);
      changes.add(new Change(controlId, compliant, log.getCreatedAt(), log.getUserId()));
    }
    for (EvaluationResponse r : responseRepo.findByEvaluationId(eval.getId())) {
      UUID controlId = r.getControl().getId();
      if (scope.contains(controlId) && !logged.contains(controlId)) {
        changes.add(
            new Change(
                controlId,
                Boolean.TRUE.equals(r.getCompliant()),
                r.getRespondedAt(),
                r.getRespondedBy() != null ? r.getRespondedBy().getId() : null));
      }
    }
    changes.removeIf(c -> c.at() == null);
    changes.sort(Comparator.comparing(Change::at));
    return changes;
  }

  private Map<UUID, String> userNames(List<Change> changes) {
    Set<UUID> ids =
        changes.stream().map(Change::userId).filter(Objects::nonNull).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return userRepo.findAllById(ids).stream()
        .collect(
            Collectors.toMap(User::getId, u -> (u.getFirstName() + " " + u.getLastName()).trim()));
  }

  private static ControlChangeDto toDto(
      Change c, Map<UUID, CatalogControl> scope, Map<UUID, String> userNames) {
    CatalogControl control = scope.get(c.controlId());
    return new ControlChangeDto(
        control.getId(),
        control.getCode(),
        control.getDescription(),
        functionName(control),
        c.at(),
        c.userId() != null ? userNames.get(c.userId()) : null);
  }

  /** Función del control: la de su primera subcategoría efectiva. */
  static String functionName(CatalogControl control) {
    return control.effectiveSubcategories().stream()
        .map(CatalogSubcategory::getCategory)
        .filter(Objects::nonNull)
        .map(category -> category.getFunction_())
        .filter(Objects::nonNull)
        .map(f -> f.getName())
        .findFirst()
        .orElse(NO_FUNCTION);
  }

  /** Controles respondidos según los cambios ocurridos hasta {@code moment} inclusive. */
  private static int answeredAt(List<Change> changes, OffsetDateTime moment) {
    Set<UUID> answered = new java.util.HashSet<>();
    for (Change c : changes) {
      if (c.at().isAfter(moment)) {
        break;
      }
      answered.add(c.controlId());
    }
    return answered.size();
  }

  /** Controles cumplidos según los cambios ocurridos hasta {@code moment} inclusive. */
  private static int compliantAt(List<Change> changes, OffsetDateTime moment) {
    Map<UUID, Boolean> state = new HashMap<>();
    for (Change c : changes) {
      if (c.at().isAfter(moment)) {
        break;
      }
      state.put(c.controlId(), c.compliant());
    }
    return count(state, true);
  }

  private static int count(Map<UUID, Boolean> state, boolean value) {
    return (int) state.values().stream().filter(v -> v == value).count();
  }

  private List<ProgressStatusEventDto> statusEvents(Evaluation eval) {
    List<ProgressStatusEventDto> events = new ArrayList<>();
    for (EvaluationAuditCycleEventDto e : auditLogService.getEvaluationAuditTrail(eval.getId())) {
      if (e.status() == null || e.occurredAt() == null) {
        continue;
      }
      Evaluation.Status status;
      try {
        status = Evaluation.Status.valueOf(e.status());
      } catch (IllegalArgumentException ex) {
        continue;
      }
      events.add(
          new ProgressStatusEventDto(
              e.status(), ReportService.statusLabel(status), e.occurredAt()));
    }
    return events;
  }

  static UUID parseControl(String payload) {
    if (payload == null) return null;
    Matcher m = CONTROL_ID.matcher(payload);
    return m.find() ? UUID.fromString(m.group(1)) : null;
  }

  static java.util.Optional<Boolean> parseCompliant(String payload) {
    if (payload == null) return java.util.Optional.empty();
    Matcher m = COMPLIANT.matcher(payload);
    return m.find()
        ? java.util.Optional.of(Boolean.valueOf(m.group(1)))
        : java.util.Optional.empty();
  }
}
