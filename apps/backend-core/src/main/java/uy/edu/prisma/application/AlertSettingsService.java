package uy.edu.prisma.application;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AlertSettings;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.AlertSettingsRepository;
import uy.edu.prisma.web.dto.Dto.AlertSettingsDto;
import uy.edu.prisma.web.dto.Dto.AlertTestResultDto;
import uy.edu.prisma.web.dto.Dto.UpdateAlertSettingsDto;

@Service
@Transactional
public class AlertSettingsService {

  private static final Logger log = LoggerFactory.getLogger(AlertSettingsService.class);

  static final short ROW_ID = 1;
  static final int MAX_RECIPIENTS = 10;
  static final String TEMPLATE_FILE = "alertmanager.yml.tpl";
  static final String CONFIG_FILE = "alertmanager.yml";
  static final String PASSWORD_FILE = "smtp_password";
  static final String MANAGED_MARKER = ".managed";

  private static final Pattern EMAIL =
      Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\\.[A-Za-z]{2,}$");
  private static final Pattern HOST =
      Pattern.compile(
          "^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
              + "|\\[[0-9A-Fa-f:.]+\\])$");
  private static final Pattern USERNAME = Pattern.compile("^[^\\s\"\\\\]{1,255}$");

  private final AlertSettingsRepository repo;
  private final AuditLogService auditLog;
  private final Path configDir;
  private final String alertmanagerUrl;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public AlertSettingsService(
      AlertSettingsRepository repo,
      AuditLogService auditLog,
      @Value("${prisma.alerting.config-dir:/var/prisma/alerting}") String configDir,
      @Value("${prisma.alerting.alertmanager-url:http://alertmanager:9093}")
          String alertmanagerUrl) {
    this.repo = repo;
    this.auditLog = auditLog;
    this.configDir = Path.of(configDir);
    this.alertmanagerUrl = alertmanagerUrl.replaceAll("/+$", "");
  }

  @Transactional(readOnly = true)
  public AlertSettingsDto get() {
    return toDto(load(), null);
  }

  public AlertSettingsDto update(UpdateAlertSettingsDto dto) {
    AlertSettings row = load();

    String recipients = validateRecipients(dto.recipients());
    String host = validateHost(dto.smtpHost());
    int port = validatePort(dto.smtpPort());
    String from = validateEmail(dto.from(), "El remitente");
    boolean newPassword = dto.password() != null && !dto.password().isEmpty();

    String username = null;
    if (dto.authEnabled()) {
      username = validateUsername(dto.username());
      if (!newPassword && !row.isPasswordSet()) {
        throw new InvalidRequestException(
            "La contraseña es obligatoria cuando la autenticación SMTP está habilitada");
      }
      if (newPassword) {
        validatePassword(dto.password());
      }
    }

    row.setRecipients(recipients);
    row.setSmtpHost(host);
    row.setSmtpPort(port);
    row.setSmtpFrom(from);
    row.setAuthEnabled(dto.authEnabled());
    row.setSmtpUser(username);
    row.setRequireTls(dto.requireTls());
    row.setConfigured(true);
    row.setUpdatedAt(OffsetDateTime.now());

    if (!dto.authEnabled()) {
      row.setPasswordSet(false);
      writeAtomically(PASSWORD_FILE, "", "rw-r-----");
    } else if (newPassword) {
      row.setPasswordSet(true);
      writeAtomically(PASSWORD_FILE, dto.password(), "rw-r-----");
    }
    writeAtomically(CONFIG_FILE, render(row), "rw-r--r--");
    writeAtomically(MANAGED_MARKER, "", "rw-r--r--");
    repo.save(row);
    auditLog.record("UPDATE", "alert-settings", "{\"host\":\"" + host + "\"}");

    String warning = reload() ? null : "Se guardó la configuración, pero Alertmanager no respondió a la recarga. Se aplicará cuando se reinicie el servicio.";
    return toDto(row, warning);
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional(readOnly = true)
  public void syncOnStartup() {
    try {
      AlertSettings row = load();
      if (!row.isConfigured()) {
        return;
      }
      writeAtomically(CONFIG_FILE, render(row), "rw-r--r--");
      reload();
    } catch (RuntimeException e) {
      log.warn("No se pudo sincronizar la configuración de alertas al arrancar: {}", e.getMessage());
    }
  }

  public AlertTestResultDto test() {
    AlertSettings row = load();
    boolean reachable = canConnect(row.getSmtpHost(), row.getSmtpPort());
    boolean sent = sendTestAlert();
    String message;
    if (!reachable) {
      message =
          "No se pudo conectar al servidor SMTP "
              + row.getSmtpHost()
              + ":"
              + row.getSmtpPort()
              + ". Revise el servidor y el puerto.";
    } else if (!sent) {
      message = "No se pudo enviar la alerta de prueba: Alertmanager no está disponible.";
    } else {
      message =
          "Alerta de prueba enviada. El correo debería llegar a "
              + row.getRecipients()
              + " en menos de un minuto.";
    }
    return new AlertTestResultDto(reachable, sent, message);
  }

  private AlertSettings load() {
    return repo.findById(ROW_ID)
        .orElseThrow(() -> new InvalidRequestException("Configuración de alertas no inicializada"));
  }

  private static AlertSettingsDto toDto(AlertSettings r, String warning) {
    return new AlertSettingsDto(
        r.getRecipients(),
        r.getSmtpHost(),
        r.getSmtpPort(),
        r.getSmtpFrom(),
        r.isAuthEnabled(),
        r.getSmtpUser(),
        r.isPasswordSet(),
        r.isRequireTls(),
        r.isConfigured(),
        r.getUpdatedAt(),
        warning);
  }

  static String validateRecipients(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidRequestException("Indique al menos un destinatario");
    }
    List<String> emails = new ArrayList<>();
    for (String part : raw.split("[,;\\s]+")) {
      if (part.isBlank()) {
        continue;
      }
      emails.add(validateEmail(part, "El destinatario " + part));
    }
    if (emails.isEmpty()) {
      throw new InvalidRequestException("Indique al menos un destinatario");
    }
    if (emails.size() > MAX_RECIPIENTS) {
      throw new InvalidRequestException("Máximo " + MAX_RECIPIENTS + " destinatarios");
    }
    return String.join(",", emails);
  }

  static String validateEmail(String raw, String label) {
    String value = raw == null ? "" : raw.trim();
    if (!EMAIL.matcher(value).matches()) {
      throw new InvalidRequestException(label + " no es una dirección de correo válida");
    }
    return value;
  }

  static String validateHost(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.length() > 255 || !HOST.matcher(value).matches()) {
      throw new InvalidRequestException(
          "El servidor SMTP debe ser un nombre de host o una IP, sin esquema, puerto ni ruta");
    }
    return value;
  }

  static int validatePort(Integer port) {
    if (port == null || port < 1 || port > 65535) {
      throw new InvalidRequestException("El puerto SMTP debe estar entre 1 y 65535");
    }
    return port;
  }

  static String validateUsername(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (!USERNAME.matcher(value).matches()) {
      throw new InvalidRequestException(
          "El usuario SMTP es obligatorio y no puede tener espacios, comillas ni barras invertidas");
    }
    return value;
  }

  static void validatePassword(String password) {
    if (password.length() > 255 || password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0) {
      throw new InvalidRequestException(
          "La contraseña SMTP no puede superar 255 caracteres ni tener saltos de línea");
    }
  }

  String render(AlertSettings r) {
    String template;
    try {
      template = Files.readString(configDir.resolve(TEMPLATE_FILE), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new InvalidRequestException(
          "No se encontró la plantilla de Alertmanager: el servicio de alertas no está disponible");
    }
    return template
        .replace("__SMTP_SMARTHOST__", r.getSmtpHost() + ":" + r.getSmtpPort())
        .replace("__SMTP_FROM__", r.getSmtpFrom())
        .replace("__SMTP_USER__", r.isAuthEnabled() && r.getSmtpUser() != null ? r.getSmtpUser() : "")
        .replace("__SMTP_REQUIRE_TLS__", String.valueOf(r.isRequireTls()))
        .replace("__EMAIL_TO__", r.getRecipients());
  }

  private void writeAtomically(String fileName, String content, String posixPermissions) {
    try {
      Files.createDirectories(configDir);
      Path tmp = Files.createTempFile(configDir, fileName, ".tmp");
      try {
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
          Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(posixPermissions));
        } catch (UnsupportedOperationException ignored) {
          log.debug("El sistema de archivos no admite permisos POSIX");
        }
        try {
          Files.move(
              tmp,
              configDir.resolve(fileName),
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
          Files.move(tmp, configDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      log.error("No se pudo escribir {}", fileName, e);
      throw new InvalidRequestException(
          "No se pudo guardar la configuración en el servicio de alertas");
    }
  }

  private boolean reload() {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(alertmanagerUrl + "/-/reload"))
              .timeout(Duration.ofSeconds(5))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException | RuntimeException e) {
      log.warn("No se pudo recargar Alertmanager: {}", e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean sendTestAlert() {
    OffsetDateTime now = OffsetDateTime.now();
    DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    String body =
        "[{\"labels\":{\"alertname\":\"PruebaDeCorreo\",\"severity\":\"critical\","
            + "\"category\":\"test\"},\"annotations\":{\"summary\":\"Prueba de correo de alertas\","
            + "\"description\":\"Esta es una alerta de prueba enviada desde Configuración de Alertas.\"},"
            + "\"startsAt\":\""
            + fmt.format(now)
            + "\",\"endsAt\":\""
            + fmt.format(now.plusMinutes(2))
            + "\"}]";
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(alertmanagerUrl + "/api/v2/alerts"))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException | RuntimeException e) {
      log.warn("No se pudo enviar la alerta de prueba: {}", e.getMessage());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean canConnect(String host, int port) {
    String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(h, port), 3000);
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
