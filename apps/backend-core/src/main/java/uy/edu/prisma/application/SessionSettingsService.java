package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.SessionSettings;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.SessionSettingsRepository;
import uy.edu.prisma.web.dto.Dto.SessionSettingsDto;

/**
 * Cierre de sesión por inactividad (control SF.4-6 del MCU 5.0). El frontend cierra la sesión
 * cuando el usuario pasa {@code idleTimeoutMinutes} sin actividad y lo avisa {@code warningSeconds}
 * antes. Los configura el Administrador de Plataforma; si falta la fila se usan los valores
 * iniciales (30 minutos y 60 segundos). Un cambio le llega a cada usuario al recargar la página o
 * volver a iniciar sesión.
 */
@Service
@Transactional
public class SessionSettingsService {

  public static final int MIN_IDLE_MINUTES = 5;
  public static final int MAX_IDLE_MINUTES = 120;
  public static final int MIN_WARNING_SECONDS = 15;
  public static final int MAX_WARNING_SECONDS = 300;
  public static final int DEFAULT_IDLE_MINUTES = 30;
  public static final int DEFAULT_WARNING_SECONDS = 60;

  private final SessionSettingsRepository repo;
  private final AuditLogService auditLog;

  public SessionSettingsService(SessionSettingsRepository repo, AuditLogService auditLog) {
    this.repo = repo;
    this.auditLog = auditLog;
  }

  @Transactional(readOnly = true)
  public SessionSettingsDto get() {
    return repo.findById(SessionSettings.SINGLETON_ID)
        .map(s -> new SessionSettingsDto(s.getIdleTimeoutMinutes(), s.getWarningSeconds()))
        .orElseGet(() -> new SessionSettingsDto(DEFAULT_IDLE_MINUTES, DEFAULT_WARNING_SECONDS));
  }

  public SessionSettingsDto update(SessionSettingsDto dto) {
    if (dto.idleTimeoutMinutes() == null) {
      throw new InvalidRequestException("El tiempo de inactividad es obligatorio");
    }
    if (dto.warningSeconds() == null) {
      throw new InvalidRequestException("El tiempo de aviso es obligatorio");
    }
    int idle = dto.idleTimeoutMinutes();
    int warning = dto.warningSeconds();
    if (idle < MIN_IDLE_MINUTES || idle > MAX_IDLE_MINUTES) {
      throw new InvalidRequestException(
          "El tiempo de inactividad debe estar entre %d y %d minutos"
              .formatted(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES));
    }
    if (warning < MIN_WARNING_SECONDS || warning > MAX_WARNING_SECONDS) {
      throw new InvalidRequestException(
          "El aviso previo debe estar entre %d y %d segundos"
              .formatted(MIN_WARNING_SECONDS, MAX_WARNING_SECONDS));
    }
    if (warning >= idle * 60) {
      throw new InvalidRequestException(
          "El aviso previo debe ser menor que el tiempo de inactividad");
    }

    SessionSettingsDto before = get();
    if (before.idleTimeoutMinutes() == idle && before.warningSeconds() == warning) {
      return before;
    }
    SessionSettings row =
        repo.findById(SessionSettings.SINGLETON_ID)
            .orElseGet(() -> SessionSettings.builder().build());
    row.setIdleTimeoutMinutes(idle);
    row.setWarningSeconds(warning);
    row.setUpdatedAt(OffsetDateTime.now());
    repo.save(row);
    auditLog.record(
        "UPDATE",
        "session-settings",
        ("{\"changes\":{\"idleTimeoutMinutes\":{\"from\":%d,\"to\":%d},"
                + "\"warningSeconds\":{\"from\":%d,\"to\":%d}}}")
            .formatted(before.idleTimeoutMinutes(), idle, before.warningSeconds(), warning));
    return get();
  }
}
