package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.web.dto.Dto.ChangePasswordDto;
import uy.edu.prisma.web.dto.Dto.SessionEventDto;
import uy.edu.prisma.web.dto.Dto.UpdateProfileDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

  @Mock private CurrentUserService currentUser;
  @Mock private KeycloakAdminClient keycloakAdmin;
  @Mock private AuditLogService auditLog;

  private AccountService service;
  private User user;
  private UUID keycloakId;

  @BeforeEach
  void setUp() {
    service = new AccountService(currentUser, keycloakAdmin, auditLog);
    keycloakId = UUID.randomUUID();
    user =
        User.builder().id(UUID.randomUUID()).email("jane@test.com").keycloakId(keycloakId).build();
    when(currentUser.currentUser()).thenReturn(Optional.of(user));
  }

  @Test
  void updateProfileSyncsNameLocallyAndInKeycloak() {
    user.setFirstName("OldName");
    user.setLastName("OldLast");
    user.setEnabled(true);

    service.updateProfile(new UpdateProfileDto("NewName", "NewLast"));

    assertEquals("NewName", user.getFirstName());
    assertEquals("NewLast", user.getLastName());
    verify(keycloakAdmin, times(1))
        .updateProfile(keycloakId, "jane@test.com", "NewName", "NewLast", true);
    verify(auditLog, times(1)).record(eq("UPDATE_PROFILE"), eq("user:" + user.getId()), any());
  }

  @Test
  void updateProfileSkipsKeycloakSyncWhenNeverProvisioned() {
    user.setKeycloakId(null);

    service.updateProfile(new UpdateProfileDto("NewName", "NewLast"));

    assertEquals("NewName", user.getFirstName());
    verify(keycloakAdmin, never()).updateProfile(any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void updateProfileThrowsWhenNotAuthenticated() {
    when(currentUser.currentUser()).thenReturn(Optional.empty());

    assertThrows(
        AccessDeniedException.class,
        () -> service.updateProfile(new UpdateProfileDto("NewName", "NewLast")));
  }

  @Test
  void changePasswordVerifiesCurrentPasswordAndSetsNewOnePermanently() {
    when(keycloakAdmin.verifyPassword("jane@test.com", "old-pass")).thenReturn(true);

    service.changePassword(new ChangePasswordDto("old-pass", "new-password-123"));

    verify(keycloakAdmin, times(1)).setPermanentPassword(keycloakId, "new-password-123");
    verify(keycloakAdmin, never()).resetPassword(any(), any());
    verify(auditLog, times(1)).record(eq("CHANGE_PASSWORD"), eq("user:" + user.getId()), any());
  }

  @Test
  void changePasswordThrowsWhenCurrentPasswordIsWrong() {
    when(keycloakAdmin.verifyPassword("jane@test.com", "wrong")).thenReturn(false);

    assertThrows(
        RuntimeException.class,
        () -> service.changePassword(new ChangePasswordDto("wrong", "new-password-123")));
    verify(keycloakAdmin, never()).setPermanentPassword(any(), any());
    verify(auditLog, never()).record(any(), any(), any());
  }

  @Test
  void changePasswordThrowsWhenUserHasNoKeycloakAccount() {
    user.setKeycloakId(null);

    assertThrows(
        RuntimeException.class,
        () -> service.changePassword(new ChangePasswordDto("old-pass", "new-password-123")));
    verify(keycloakAdmin, never()).verifyPassword(any(), any());
  }

  @Test
  void changePasswordThrowsWhenNotAuthenticated() {
    when(currentUser.currentUser()).thenReturn(Optional.empty());

    assertThrows(
        AccessDeniedException.class,
        () -> service.changePassword(new ChangePasswordDto("old-pass", "new-password-123")));
  }

  @Test
  void recordSessionEventAcceptsLogin() {
    service.recordSessionEvent(new SessionEventDto("LOGIN"));

    verify(auditLog, times(1)).record(eq("LOGIN"), eq("user:" + user.getId()), isNull());
  }

  @Test
  void recordSessionEventAcceptsLogout() {
    service.recordSessionEvent(new SessionEventDto("LOGOUT"));

    verify(auditLog, times(1)).record(eq("LOGOUT"), eq("user:" + user.getId()), isNull());
  }

  @Test
  void recordSessionEventRejectsUnknownEvent() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.recordSessionEvent(new SessionEventDto("DELETE_EVERYTHING")));
    verify(auditLog, never()).record(any(), any(), any());
  }

  @Test
  void recordSessionEventThrowsWhenNotAuthenticated() {
    when(currentUser.currentUser()).thenReturn(Optional.empty());

    assertThrows(
        AccessDeniedException.class, () -> service.recordSessionEvent(new SessionEventDto("LOGIN")));
  }
}
