package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.SessionSettings;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.SessionSettingsRepository;
import uy.edu.prisma.web.dto.Dto.SessionSettingsDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionSettingsServiceTest {

  @Mock private SessionSettingsRepository repo;
  @Mock private AuditLogService auditLog;

  private SessionSettingsService service;
  private SessionSettings row;

  @BeforeEach
  void setUp() {
    row = SessionSettings.builder().idleTimeoutMinutes(30).warningSeconds(60).build();
    when(repo.findById(SessionSettings.SINGLETON_ID)).thenAnswer(inv -> Optional.ofNullable(row));
    when(repo.save(any(SessionSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new SessionSettingsService(repo, auditLog);
  }

  @Test
  void getReturnsTheStoredValues() {
    assertEquals(new SessionSettingsDto(30, 60), service.get());
  }

  @Test
  void aMissingRowFallsBackToTheDefaults() {
    row = null;

    assertEquals(new SessionSettingsDto(30, 60), service.get());
  }

  @Test
  void updateSavesAndRecordsTheChange() {
    SessionSettingsDto result = service.update(new SessionSettingsDto(15, 45));

    assertEquals(new SessionSettingsDto(15, 45), result);
    verify(repo).save(row);
    verify(auditLog)
        .record(
            "UPDATE",
            "session-settings",
            "{\"changes\":{\"idleTimeoutMinutes\":{\"from\":30,\"to\":15},"
                + "\"warningSeconds\":{\"from\":60,\"to\":45}}}");
  }

  @Test
  void updateCreatesTheRowWhenItIsMissing() {
    row = null;

    service.update(new SessionSettingsDto(10, 30));

    verify(repo).save(any(SessionSettings.class));
  }

  @Test
  void updateWithoutChangesIsNotRecorded() {
    service.update(new SessionSettingsDto(30, 60));

    verify(repo, never()).save(any());
    verify(auditLog, never()).record(anyString(), anyString(), any());
  }

  @Test
  void theLimitsAreAccepted() {
    assertDoesNotThrow(() -> service.update(new SessionSettingsDto(5, 15)));
    assertDoesNotThrow(() -> service.update(new SessionSettingsDto(120, 300)));
  }

  @Test
  void valuesOutsideTheRangesAreRejected() {
    for (SessionSettingsDto dto :
        new SessionSettingsDto[] {
          new SessionSettingsDto(4, 60),
          new SessionSettingsDto(121, 60),
          new SessionSettingsDto(30, 14),
          new SessionSettingsDto(30, 301),
        }) {
      assertThrows(InvalidRequestException.class, () -> service.update(dto), dto.toString());
    }
    verify(repo, never()).save(any());
  }

  @Test
  void missingValuesAreRejected() {
    assertThrows(
        InvalidRequestException.class, () -> service.update(new SessionSettingsDto(null, 60)));
    assertThrows(
        InvalidRequestException.class, () -> service.update(new SessionSettingsDto(30, null)));
  }

  @Test
  void theWarningMustBeShorterThanTheIdleTimeout() {
    // 5 minutos = 300 segundos: un aviso de 300 s no deja tiempo de actividad.
    assertThrows(
        InvalidRequestException.class, () -> service.update(new SessionSettingsDto(5, 300)));
  }
}
