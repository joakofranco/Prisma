package uy.edu.prisma.application;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.EmailSettingsDto;
import uy.edu.prisma.web.dto.Dto.UpdateEmailSettingsDto;

/**
 * Configuración del servidor SMTP que Keycloak usa para enviar emails -- en particular, el link de
 * "¿Olvidaste tu contraseña?" del login (ver {@code resetPasswordAllowed} en
 * infra/keycloak/realm-prisma.json, ya habilitado, pero que no manda nada mientras el realm no
 * tenga un SMTP configurado).
 *
 * <p>Delega en {@link KeycloakAdminClient} en vez de mantener su propio
 * EmailService/JavaMailSender: Keycloak ya es la única fuente de verdad para autenticación (login,
 * "forgot password", cambio de contraseña) en este proyecto -- duplicar el envío de emails acá
 * sería otro camino de reseteo de contraseña por fuera de Keycloak, exactamente el tipo de
 * credencial paralela que este proyecto evitó al migrar todo el login a Keycloak.
 */
@Service
public class EmailSettingsService {

  private final KeycloakAdminClient keycloakAdmin;
  private final AuditLogService auditLog;

  public EmailSettingsService(KeycloakAdminClient keycloakAdmin, AuditLogService auditLog) {
    this.keycloakAdmin = keycloakAdmin;
    this.auditLog = auditLog;
  }

  public EmailSettingsDto get() {
    Map<String, String> smtp = keycloakAdmin.getSmtpConfig();
    String host = smtp.getOrDefault("host", "");
    String from = smtp.getOrDefault("from", "");
    return new EmailSettingsDto(
        host,
        parsePort(smtp.get("port")),
        from,
        smtp.get("fromDisplayName"),
        parseBoolean(smtp.get("auth")),
        smtp.get("user"),
        parseBoolean(smtp.get("starttls")),
        parseBoolean(smtp.get("ssl")),
        !host.isBlank() && !from.isBlank());
  }

  public void update(UpdateEmailSettingsDto dto) {
    if (dto.authEnabled() && (dto.password() == null || dto.password().isBlank())) {
      throw new InvalidRequestException(
          "La contraseña es obligatoria cuando la autenticación SMTP está habilitada");
    }

    Map<String, String> smtp = new LinkedHashMap<>();
    smtp.put("host", dto.host());
    smtp.put("port", String.valueOf(dto.port()));
    smtp.put("from", dto.from());
    if (dto.fromDisplayName() != null && !dto.fromDisplayName().isBlank()) {
      smtp.put("fromDisplayName", dto.fromDisplayName());
    }
    smtp.put("auth", String.valueOf(dto.authEnabled()));
    smtp.put("starttls", String.valueOf(dto.starttls()));
    smtp.put("ssl", String.valueOf(dto.ssl()));
    if (dto.authEnabled()) {
      smtp.put("user", dto.username());
      smtp.put("password", dto.password());
    }

    keycloakAdmin.updateSmtpConfig(smtp);
    auditLog.record("UPDATE", "email-settings", "{\"host\":\"" + dto.host() + "\"}");
  }

  private static Integer parsePort(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean parseBoolean(String raw) {
    return Boolean.parseBoolean(raw);
  }
}
