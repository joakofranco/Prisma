package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.ImprovementPlan.Priority;
import uy.edu.prisma.domain.entity.PlanDefaultDeadline;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.PlanDefaultDeadlineRepository;
import uy.edu.prisma.web.dto.Dto.PlanDeadlinesDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanDeadlineSettingsServiceTest {

  @Mock private PlanDefaultDeadlineRepository repo;
  @Mock private AuditLogService auditLog;

  private PlanDeadlineSettingsService service;
  private List<PlanDefaultDeadline> rows;

  @BeforeEach
  void setUp() {
    // Lo que deja la migración V29: los valores del requerimiento.
    rows = new ArrayList<>();
    rows.add(PlanDefaultDeadline.builder().priority(Priority.HIGH).months(3).build());
    rows.add(PlanDefaultDeadline.builder().priority(Priority.MEDIUM).months(6).build());
    rows.add(PlanDefaultDeadline.builder().priority(Priority.LOW).months(12).build());
    when(repo.findAll()).thenAnswer(inv -> List.copyOf(rows));
    when(repo.findById(any()))
        .thenAnswer(
            inv -> rows.stream().filter(r -> r.getPriority() == inv.getArgument(0)).findFirst());
    when(repo.save(any(PlanDefaultDeadline.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new PlanDeadlineSettingsService(repo, auditLog);
  }

  @Test
  void getReturnsTheStoredMonths() {
    assertEquals(new PlanDeadlinesDto(3, 6, 12), service.get());
  }

  @Test
  void aMissingRowFallsBackToTheRequirementDefault() {
    rows.removeIf(r -> r.getPriority() == Priority.LOW);

    assertEquals(12, service.currentMonths().get(Priority.LOW));
  }

  @Test
  void defaultDueDateAddsTheMonthsOfThePriority() {
    LocalDate from = LocalDate.of(2026, 11, 9);

    assertEquals(LocalDate.of(2027, 2, 9), service.defaultDueDate(Priority.HIGH, from));
    assertEquals(LocalDate.of(2027, 5, 9), service.defaultDueDate(Priority.MEDIUM, from));
    assertEquals(LocalDate.of(2027, 11, 9), service.defaultDueDate(Priority.LOW, from));
  }

  @Test
  void updateSavesOnlyTheChangedValuesAndRecordsThem() {
    PlanDeadlinesDto result = service.update(new PlanDeadlinesDto(1, 6, 12));

    assertEquals(1, result.highMonths());
    verify(repo, times(1)).save(any(PlanDefaultDeadline.class));
    verify(auditLog)
        .record(
            "UPDATE", "plan-deadline-settings", "{\"changes\":{\"HIGH\":{\"from\":3,\"to\":1}}}");
  }

  @Test
  void oneMonthForEveryPriorityIsAllowed() {
    PlanDeadlinesDto result = service.update(new PlanDeadlinesDto(1, 1, 1));

    assertEquals(new PlanDeadlinesDto(1, 1, 1), result);
  }

  @Test
  void updateWithoutChangesIsNotRecorded() {
    service.update(new PlanDeadlinesDto(3, 6, 12));

    verify(repo, never()).save(any());
    verify(auditLog, never()).record(anyString(), anyString(), any());
  }

  @Test
  void valuesOutsideOneToSixtyMonthsAreRejected() {
    for (PlanDeadlinesDto dto :
        List.of(
            new PlanDeadlinesDto(0, 6, 12),
            new PlanDeadlinesDto(3, 6, 61),
            new PlanDeadlinesDto(null, 6, 12))) {
      assertThrows(InvalidRequestException.class, () -> service.update(dto), dto.toString());
    }
    verify(repo, never()).save(any());
  }

  @Test
  void aMoreUrgentPriorityCannotHaveALongerDeadline() {
    InvalidRequestException highOverMedium =
        assertThrows(
            InvalidRequestException.class, () -> service.update(new PlanDeadlinesDto(8, 6, 12)));
    InvalidRequestException mediumOverLow =
        assertThrows(
            InvalidRequestException.class, () -> service.update(new PlanDeadlinesDto(3, 13, 12)));

    assertTrue(highOverMedium.getMessage().contains("Alta"));
    assertTrue(mediumOverLow.getMessage().contains("Media"));
    verify(repo, never()).save(any());
  }

  @Test
  void aMissingRowIsCreatedOnUpdate() {
    rows.removeIf(r -> r.getPriority() == Priority.LOW);
    when(repo.findById(Priority.LOW)).thenReturn(Optional.empty());

    service.update(new PlanDeadlinesDto(3, 6, 24));

    verify(repo).save(argThat(r -> r.getPriority() == Priority.LOW && r.getMonths() == 24));
  }
}
