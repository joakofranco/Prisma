package uy.edu.prisma.application;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.infrastructure.KeycloakAdminClient.LoginFailureEvent;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginFailureAuditSyncServiceTest {

  @Mock private KeycloakAdminClient keycloakAdminClient;
  @Mock private AuditLogService auditLogService;

  private LoginFailureAuditSyncService service;

  @BeforeEach
  void setUp() {
    service = new LoginFailureAuditSyncService(keycloakAdminClient, auditLogService);
  }

  @Test
  void mirrorsEachNewKeycloakEventIntoTheAuditLog() {
    when(keycloakAdminClient.fetchLoginFailures(anyLong()))
        .thenReturn(
            List.of(
                new LoginFailureEvent(1_700_000_000_000L, "1.1.1.1", "a@test.com", "invalid_user_credentials"),
                new LoginFailureEvent(1_700_000_005_000L, "2.2.2.2", "b@test.com", "user_not_found")));

    service.syncLoginFailures();

    ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
    verify(auditLogService, times(2))
        .recordLoginFailure(emailCaptor.capture(), anyString(), any(OffsetDateTime.class));
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of("a@test.com", "b@test.com"), emailCaptor.getAllValues());
  }

  @Test
  void doesNothingWhenThereAreNoNewEvents() {
    when(keycloakAdminClient.fetchLoginFailures(anyLong())).thenReturn(List.of());

    service.syncLoginFailures();

    verifyNoInteractions(auditLogService);
  }

  @Test
  void aKeycloakFailureDoesNotPropagateSoTheSchedulerKeepsRunning() {
    when(keycloakAdminClient.fetchLoginFailures(anyLong()))
        .thenThrow(new RuntimeException("Keycloak inalcanzable"));

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(service::syncLoginFailures);
    verifyNoInteractions(auditLogService);
  }

  @Test
  void advancesThePollWatermarkToTheLatestEventProcessed() {
    // Tiene que ser POSTERIOR al watermark inicial (System.currentTimeMillis() al construirse el
    // servicio, ver el comentario ahí) -- si no, Math.max lo descarta a propósito para no
    // retroceder el watermark, que es el comportamiento correcto pero rompería este test.
    long eventTime = System.currentTimeMillis() + 60_000;
    when(keycloakAdminClient.fetchLoginFailures(anyLong()))
        .thenReturn(
            List.of(new LoginFailureEvent(eventTime, "1.1.1.1", "a@test.com", "invalid_user_credentials")))
        .thenReturn(List.of());

    service.syncLoginFailures();
    service.syncLoginFailures();

    // La segunda corrida debe pedir eventos posteriores al que ya se proceso en la primera (no
    // el mismo timestamp inicial de arranque) -- sin esto, el mismo evento se reprocesaria en
    // cada intervalo para siempre.
    verify(keycloakAdminClient).fetchLoginFailures(eventTime);
  }
}
