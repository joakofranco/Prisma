package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * RF-TRZ-04 (PRI-325): la retención mínima de 365 días de la bitácora la impone el MOTOR de la base
 * (trigger trg_audit_logs_retention, migración V30), no el código de la aplicación -- por eso sólo
 * puede verificarse contra un Postgres real: los tests unitarios con mocks no ven el trigger.
 *
 * <p>Deliberadamente SIN @Transactional y vía JdbcTemplate (autocommit por sentencia): el RAISE del
 * trigger aborta la transacción en curso en Postgres, así que dentro de una única transacción de
 * test no se podría ni consultar después que la fila sobrevivió, ni se ejercitaría el commit real
 * del caso permitido. Las filas que quedan (las recientes no se pueden borrar, justamente) no
 * molestan: el contenedor es descartable y cada test usa su propio marcador en "resource".
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
class AuditLogRetentionIT {

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

  // Igual que en PrismaApplicationIT: no hay Keycloak levantado (sólo Postgres), y sin este mock
  // LoginFailureAuditSyncService intenta HTTP contra localhost en su hilo del scheduler.
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  @Autowired private JdbcTemplate jdbc;

  private int countByResource(String resource) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit.audit_logs WHERE resource = ?", Integer.class, resource);
    return n != null ? n : 0;
  }

  @Test
  void deleteOfRecentLogIsRejectedByTheDatabase() {
    jdbc.update(
        "INSERT INTO audit.audit_logs(action, resource) VALUES ('TEST_TRZ04', 'it:reciente')");

    DataAccessException ex =
        assertThrows(
            DataAccessException.class,
            () -> jdbc.update("DELETE FROM audit.audit_logs WHERE resource = 'it:reciente'"));

    assertTrue(
        ex.getMostSpecificCause().getMessage().contains("RF-TRZ-04"),
        "el error debe citar el requerimiento: " + ex.getMostSpecificCause().getMessage());
    // El registro tiene que seguir ahí: el DELETE rechazado no puede haber borrado nada.
    assertEquals(1, countByResource("it:reciente"));
  }

  @Test
  void deleteOfLogOlderThanOneYearIsAllowed() {
    jdbc.update(
        "INSERT INTO audit.audit_logs(action, resource, created_at) "
            + "VALUES ('TEST_TRZ04', 'it:viejo', now() - INTERVAL '400 days')");

    int deleted = jdbc.update("DELETE FROM audit.audit_logs WHERE resource = 'it:viejo'");

    assertEquals(1, deleted);
    assertEquals(0, countByResource("it:viejo"));
  }

  @Test
  void deleteAt364DaysIsStillRejected() {
    // Borde: a los 364 días la fila aún no es elegible para purga (el mínimo es 365).
    jdbc.update(
        "INSERT INTO audit.audit_logs(action, resource, created_at) "
            + "VALUES ('TEST_TRZ04', 'it:borde', now() - INTERVAL '364 days')");

    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM audit.audit_logs WHERE resource = 'it:borde'"));
    assertEquals(1, countByResource("it:borde"));
  }
}
