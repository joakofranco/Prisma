package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.AuditLogService;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.AuditLogDto;

/**
 * PRI-368: el buscador de la bitácora contra Postgres real. Cubre lo que los tests con mocks no
 * pueden: que la consulta JPQL (subconsultas, translate, CAST de uuid) sea válida y encuentre filas
 * tanto por lo que muestra la pantalla como por los códigos internos.
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
@Transactional
class AuditLogSearchIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("prisma_test")
          .withUsername("test")
          .withPassword("test")
          .withInitScript("db/init-test.sql");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
  }

  @Autowired private AuditLogService service;
  @Autowired private JdbcTemplate jdbc;

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  private final UUID orgId = UUID.randomUUID();
  private final UUID actorId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();
  private final UUID deletedUserId = UUID.randomUUID();
  private final UUID evaluationId = UUID.randomUUID();

  @BeforeEach
  void seed() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);

    jdbc.update(
        "INSERT INTO prisma.organizations (id, name, rut) VALUES (?, 'Organización Búsqueda', '1.234.567-8')",
        orgId);
    jdbc.update(
        "INSERT INTO prisma.users (id, email, first_name, last_name) VALUES (?, 'auditora@prisma.local', 'Ana', 'Pérez')",
        actorId);
    jdbc.update(
        "INSERT INTO prisma.users (id, email, first_name, last_name) VALUES (?, 'evaluador@prisma.local', 'Evaluador', 'Video')",
        targetId);
    jdbc.update(
        "INSERT INTO prisma.evaluations (id, name, organization_id) VALUES (?, 'Evaluación Anual', ?)",
        evaluationId,
        orgId);

    // Inicio de sesión de Ana, que pertenece a la organización.
    log(actorId, orgId, "LOGIN", "user:" + actorId);
    // Ana edita al evaluador (el recurso afectado es otro usuario).
    log(actorId, orgId, "UPDATE", "user:" + targetId);
    // Cambio de estado de una evaluación.
    log(actorId, orgId, "UPDATE_STATUS", "evaluation:" + evaluationId);
    // Un usuario que ya no existe (borrado): sólo queda su uuid en la bitácora.
    log(deletedUserId, null, "LOGIN", "user:" + deletedUserId);
    // Intento fallido: el recurso es directamente el email.
    log(null, null, "LOGIN_FAILED", "intruso@externo.com");
  }

  private void log(UUID userId, UUID tenantId, String action, String resource) {
    jdbc.update(
        "INSERT INTO audit.audit_logs (id, user_id, tenant_id, action, resource, ip_address, created_at)"
            + " VALUES (?, ?, ?, ?, ?, '10.9.8.7', ?)",
        UUID.randomUUID(),
        userId,
        tenantId,
        action,
        resource,
        OffsetDateTime.now());
  }

  private long total(String search) {
    return service.list(search, null, 0, 50).total();
  }

  @Test
  void sinTextoDevuelveTodo() {
    assertEquals(5, total(null));
    assertEquals(5, total("   "));
  }

  @Test
  void buscaPorLoQueMuestraLaPantalla() {
    // Columna Usuario: email completo y nombre completo, con o sin tildes y mayúsculas.
    assertEquals(3, total("auditora@prisma.local"));
    assertEquals(3, total("Ana Pérez"));
    assertEquals(3, total("ana perez"));
    // Columna Organización.
    assertEquals(3, total("Organización Búsqueda"));
    assertEquals(3, total("organizacion busqueda"));
    // Columna Acción, en español y con el código. Es búsqueda por contenido: «Inicio de Sesión»
    // también está dentro de «Inicio de Sesión Fallido» (para exactitud está el filtro de acción).
    assertEquals(3, total("Inicio de Sesión"));
    assertEquals(1, total("Inicio de Sesión Fallido"));
    assertEquals(1, total("cambio de estado"));
    assertEquals(3, total("LOGIN"));
    assertEquals(1, total("login_failed"));
    // Recurso afectado: nombre de la entidad y tipo.
    assertEquals(1, total("Evaluación Anual"));
    assertEquals(1, total("evaluacion anual"));
    assertEquals(1, total("Evaluador Video"));
    assertEquals(1, total("intruso@externo.com"));
    // Columna IP.
    assertEquals(5, total("10.9.8.7"));
  }

  @Test
  void buscaPorCodigosInternosYUuid() {
    // uuid del actor: todas sus filas.
    assertEquals(3, total(actorId.toString()));
    // uuid de un usuario afectado: la fila donde es el recurso.
    assertEquals(1, total(targetId.toString()));
    // uuid de la organización.
    assertEquals(3, total(orgId.toString()));
    // uuid de un usuario YA ELIMINADO: sigue apareciendo aunque no exista en prisma.users.
    assertEquals(1, total(deletedUserId.toString()));
    assertEquals(1, total(deletedUserId.toString().substring(0, 8)));
    // Prefijo interno del recurso.
    assertEquals(1, total("evaluation:"));
  }

  @Test
  void lasCoincidenciasQueNoExistenDevuelvenCero() {
    assertEquals(0, total("zzz-no-existe"));
  }

  @Test
  void elFiltroDeAccionSeCombinaConLaBusqueda() {
    assertEquals(1, service.list(actorId.toString(), "UPDATE", 0, 50).total());
    AuditLogDto first = service.list("Ana Pérez", "LOGIN", 0, 50).data().get(0);
    assertEquals("LOGIN", first.action());
  }

  @Test
  void elExportCsvUsaElMismoFiltro() {
    String csv = service.exportCsv("Evaluación Anual", null);
    assertEquals(2, csv.split("\r\n").length); // encabezado + 1 fila
  }
}
