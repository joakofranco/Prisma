package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.EmailSettingsDto;
import uy.edu.prisma.web.dto.Dto.UpdateEmailSettingsDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSettingsServiceTest {

  @Mock private KeycloakAdminClient keycloakAdmin;
  @Mock private AuditLogService auditLog;

  private EmailSettingsService service;

  @BeforeEach
  void setUp() {
    service = new EmailSettingsService(keycloakAdmin, auditLog);
  }

  @Test
  void getReturnsUnconfiguredWhenSmtpServerIsEmpty() {
    when(keycloakAdmin.getSmtpConfig()).thenReturn(Map.of());

    EmailSettingsDto dto = service.get();

    assertFalse(dto.configured());
    assertEquals("", dto.host());
    assertEquals("", dto.from());
    assertNull(dto.port());
  }

  @Test
  void getMapsKeycloakSmtpMapToDto() {
    when(keycloakAdmin.getSmtpConfig())
        .thenReturn(
            Map.of(
                "host", "smtp.example.com",
                "port", "587",
                "from", "no-reply@prisma.local",
                "fromDisplayName", "PRISMA",
                "auth", "true",
                "user", "smtp-user",
                "starttls", "true",
                "ssl", "false"));

    EmailSettingsDto dto = service.get();

    assertTrue(dto.configured());
    assertEquals("smtp.example.com", dto.host());
    assertEquals(587, dto.port());
    assertEquals("no-reply@prisma.local", dto.from());
    assertEquals("PRISMA", dto.fromDisplayName());
    assertTrue(dto.authEnabled());
    assertEquals("smtp-user", dto.username());
    assertTrue(dto.starttls());
    assertFalse(dto.ssl());
  }

  @Test
  void getToleratesUnparseablePort() {
    when(keycloakAdmin.getSmtpConfig())
        .thenReturn(Map.of("host", "smtp.example.com", "from", "a@b.c", "port", "not-a-number"));

    assertNull(service.get().port());
  }

  @Test
  void updateRejectsMissingPasswordWhenAuthEnabled() {
    UpdateEmailSettingsDto dto =
        new UpdateEmailSettingsDto(
            "smtp.example.com",
            587,
            "no-reply@prisma.local",
            "PRISMA",
            true,
            "user",
            null,
            true,
            false);

    assertThrows(InvalidRequestException.class, () -> service.update(dto));
    verify(keycloakAdmin, never()).updateSmtpConfig(any());
  }

  @Test
  void updateAllowsNoAuthWithoutPassword() {
    when(keycloakAdmin.getSmtpConfig()).thenReturn(Map.of());
    UpdateEmailSettingsDto dto =
        new UpdateEmailSettingsDto(
            "mailhog", 1025, "no-reply@prisma.local", null, false, null, null, false, false);

    service.update(dto);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(keycloakAdmin, times(1)).updateSmtpConfig(captor.capture());
    Map<String, String> sent = captor.getValue();
    assertEquals("mailhog", sent.get("host"));
    assertEquals("1025", sent.get("port"));
    assertEquals("false", sent.get("auth"));
    assertFalse(sent.containsKey("user"));
    assertFalse(sent.containsKey("password"));
  }

  @Test
  void updateSendsCredentialsWhenAuthEnabled() {
    when(keycloakAdmin.getSmtpConfig()).thenReturn(Map.of());
    UpdateEmailSettingsDto dto =
        new UpdateEmailSettingsDto(
            "smtp.example.com",
            587,
            "no-reply@prisma.local",
            "PRISMA",
            true,
            "smtp-user",
            "smtp-pass",
            true,
            false);

    service.update(dto);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(keycloakAdmin, times(1)).updateSmtpConfig(captor.capture());
    Map<String, String> sent = captor.getValue();
    assertEquals("smtp-user", sent.get("user"));
    assertEquals("smtp-pass", sent.get("password"));
    assertEquals("true", sent.get("starttls"));
    assertEquals("PRISMA", sent.get("fromDisplayName"));
  }

  @Test
  void updateRecordsAuditLog() {
    when(keycloakAdmin.getSmtpConfig()).thenReturn(Map.of());
    UpdateEmailSettingsDto dto =
        new UpdateEmailSettingsDto(
            "mailhog", 1025, "no-reply@prisma.local", null, false, null, null, false, false);

    service.update(dto);

    verify(auditLog, times(1)).record(eq("UPDATE"), eq("email-settings"), anyString());
  }
}
