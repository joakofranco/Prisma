package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.AlertSettings;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.AlertSettingsRepository;
import uy.edu.prisma.web.dto.Dto.AlertSettingsDto;
import uy.edu.prisma.web.dto.Dto.AlertTestResultDto;
import uy.edu.prisma.web.dto.Dto.UpdateAlertSettingsDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertSettingsServiceTest {

  private static final String TEMPLATE =
      "smarthost: \"__SMTP_SMARTHOST__\"\nfrom: \"__SMTP_FROM__\"\nuser: \"__SMTP_USER__\"\n"
          + "tls: __SMTP_REQUIRE_TLS__\nto: \"__EMAIL_TO__\"\n";

  @Mock private AlertSettingsRepository repo;
  @Mock private AuditLogService auditLog;
  @TempDir Path dir;

  private AlertSettings row;
  private AlertSettingsService service;

  @BeforeEach
  void setUp() throws IOException {
    row =
        AlertSettings.builder()
            .id((short) 1)
            .recipients("admin@prisma.uy")
            .smtpHost("mailpit")
            .smtpPort(1025)
            .smtpFrom("alertas@prisma.uy")
            .build();
    when(repo.findById((short) 1)).thenReturn(Optional.of(row));
    when(repo.save(any(AlertSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    Files.writeString(dir.resolve(AlertSettingsService.TEMPLATE_FILE), TEMPLATE);
    service = new AlertSettingsService(repo, auditLog, dir.toString(), "http://127.0.0.1:1");
  }

  private static UpdateAlertSettingsDto dto(
      String recipients,
      String host,
      Integer port,
      String from,
      boolean auth,
      String user,
      String password,
      boolean tls) {
    return new UpdateAlertSettingsDto(recipients, host, port, from, auth, user, password, tls);
  }

  private UpdateAlertSettingsDto valid() {
    return dto(
        "equipo@prisma.uy, ops@prisma.uy",
        "smtp.proveedor.com",
        587,
        "no-reply@prisma.uy",
        true,
        "no-reply@prisma.uy",
        "s3cret",
        true);
  }

  @Test
  void getDevuelveLosValoresIniciales() {
    AlertSettingsDto result = service.get();
    assertEquals("admin@prisma.uy", result.recipients());
    assertEquals("mailpit", result.smtpHost());
    assertFalse(result.configured());
    assertFalse(result.passwordSet());
    assertNull(result.warning());
  }

  @Test
  void updateGuardaConfiguracionYEscribeArchivos() throws IOException {
    AlertSettingsDto result = service.update(valid());

    assertEquals("equipo@prisma.uy,ops@prisma.uy", result.recipients());
    assertTrue(result.configured());
    assertTrue(result.passwordSet());
    assertNotNull(result.warning());
    String yml = Files.readString(dir.resolve(AlertSettingsService.CONFIG_FILE));
    assertTrue(yml.contains("smarthost: \"smtp.proveedor.com:587\""));
    assertTrue(yml.contains("from: \"no-reply@prisma.uy\""));
    assertTrue(yml.contains("user: \"no-reply@prisma.uy\""));
    assertTrue(yml.contains("tls: true"));
    assertTrue(yml.contains("to: \"equipo@prisma.uy,ops@prisma.uy\""));
    assertEquals("s3cret", Files.readString(dir.resolve(AlertSettingsService.PASSWORD_FILE)));
    assertTrue(Files.exists(dir.resolve(AlertSettingsService.MANAGED_MARKER)));
    verify(auditLog).record("UPDATE", "alert-settings", "{\"host\":\"smtp.proveedor.com\"}");
  }

  @Test
  void laContrasenaNuncaSeGuardaEnLaBaseNiSeDevuelve() throws IOException {
    service.update(valid());
    assertFalse(row.toString().contains("s3cret"));
    String yml = Files.readString(dir.resolve(AlertSettingsService.CONFIG_FILE));
    assertFalse(yml.contains("s3cret"));
  }

  @Test
  void sinContrasenaNuevaConservaLaExistente() throws IOException {
    service.update(valid());
    service.update(
        dto(
            "equipo@prisma.uy",
            "smtp.proveedor.com",
            465,
            "no-reply@prisma.uy",
            true,
            "no-reply@prisma.uy",
            "",
            true));
    assertEquals("s3cret", Files.readString(dir.resolve(AlertSettingsService.PASSWORD_FILE)));
    assertEquals(465, row.getSmtpPort());
  }

  @Test
  void conAutenticacionLaContrasenaEsObligatoriaLaPrimeraVez() {
    assertThrows(
        InvalidRequestException.class,
        () ->
            service.update(
                dto("a@prisma.uy", "smtp.x.com", 587, "b@prisma.uy", true, "usuario", "", true)));
  }

  @Test
  void sinAutenticacionVaciaElUsuarioYLaContrasena() throws IOException {
    service.update(valid());
    service.update(
        dto("a@prisma.uy", "mailpit", 1025, "b@prisma.uy", false, "ignorado", null, false));
    assertNull(row.getSmtpUser());
    assertFalse(row.isPasswordSet());
    assertEquals("", Files.readString(dir.resolve(AlertSettingsService.PASSWORD_FILE)));
    assertTrue(
        Files.readString(dir.resolve(AlertSettingsService.CONFIG_FILE)).contains("user: \"\""));
  }

  @Test
  void rechazaDestinatariosInvalidosOVacios() {
    for (String bad : new String[] {null, "", "  ", "no-es-correo", "a@b", "ok@prisma.uy, malo"}) {
      assertThrows(
          InvalidRequestException.class,
          () -> service.update(dto(bad, "mailpit", 1025, "b@prisma.uy", false, null, null, false)),
          String.valueOf(bad));
    }
  }

  @Test
  void rechazaMasDeDiezDestinatarios() {
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 11; i++) {
      many.append("u").append(i).append("@prisma.uy,");
    }
    assertThrows(
        InvalidRequestException.class,
        () ->
            service.update(
                dto(many.toString(), "mailpit", 1025, "b@prisma.uy", false, null, null, false)));
  }

  @Test
  void rechazaServidorConEsquemaPuertoORuta() {
    for (String bad :
        new String[] {null, "", "http://smtp.x.com", "smtp.x.com:587", "smtp.x.com/a", "a b"}) {
      assertThrows(
          InvalidRequestException.class,
          () ->
              service.update(dto("a@prisma.uy", bad, 587, "b@prisma.uy", false, null, null, false)),
          String.valueOf(bad));
    }
  }

  @Test
  void rechazaPuertoFueraDeRango() {
    for (Integer bad : new Integer[] {null, 0, -1, 65536}) {
      assertThrows(
          InvalidRequestException.class,
          () ->
              service.update(
                  dto("a@prisma.uy", "mailpit", bad, "b@prisma.uy", false, null, null, false)));
    }
  }

  @Test
  void rechazaUsuarioYContrasenaConCaracteresPeligrosos() {
    for (String bad : new String[] {"", "con espacio", "com\"illa", "barra\\invertida"}) {
      assertThrows(
          InvalidRequestException.class,
          () ->
              service.update(
                  dto("a@prisma.uy", "mailpit", 25, "b@prisma.uy", true, bad, "x", false)));
    }
    assertThrows(
        InvalidRequestException.class,
        () ->
            service.update(
                dto("a@prisma.uy", "mailpit", 25, "b@prisma.uy", true, "usuario", "a\nb", false)));
  }

  @Test
  void unaConfiguracionInvalidaNoTocaLosArchivos() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.update(dto("malo", "mailpit", 25, "b@prisma.uy", false, null, null, false)));
    assertFalse(Files.exists(dir.resolve(AlertSettingsService.CONFIG_FILE)));
    verify(repo, never()).save(any());
  }

  @Test
  void sinPlantillaFallaConUnMensajeClaro() throws IOException {
    Files.delete(dir.resolve(AlertSettingsService.TEMPLATE_FILE));
    InvalidRequestException ex =
        assertThrows(InvalidRequestException.class, () -> service.update(valid()));
    assertTrue(ex.getMessage().contains("plantilla"));
  }

  @Test
  void testSinServidorSmtpNiAlertmanagerInformaAmbosFallos() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    row.setSmtpHost("127.0.0.1");
    row.setSmtpPort(closedPort);
    AlertTestResultDto result = service.test();
    assertFalse(result.smtpReachable());
    assertFalse(result.alertSent());
    assertTrue(result.message().contains("No se pudo conectar"));
  }

  @Test
  void testConServidorSmtpAccesibleReportaLaAlertaNoEnviada() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      row.setSmtpHost("127.0.0.1");
      row.setSmtpPort(s.getLocalPort());
      AlertTestResultDto result = service.test();
      assertTrue(result.smtpReachable());
      assertFalse(result.alertSent());
      assertTrue(result.message().contains("Alertmanager"));
    }
  }

  @Test
  void syncOnStartupNoHaceNadaSiNuncaSeConfiguro() {
    service.syncOnStartup();
    assertFalse(Files.exists(dir.resolve(AlertSettingsService.CONFIG_FILE)));
  }

  @Test
  void syncOnStartupReescribeLaConfiguracionGuardada() throws IOException {
    row.setConfigured(true);
    row.setSmtpHost("smtp.guardado.com");
    service.syncOnStartup();
    String yml =
        Files.readString(dir.resolve(AlertSettingsService.CONFIG_FILE), StandardCharsets.UTF_8);
    assertTrue(yml.contains("smtp.guardado.com:1025"));
  }
}
