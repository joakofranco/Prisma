package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

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
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.OrganizationService;
import uy.edu.prisma.application.UserService;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * PRI-368: los buscadores de Usuarios y Organizaciones contra Postgres real. Tienen que ignorar
 * mayúsculas, tildes y puntos, y el de usuarios además encontrar por «Nombre Apellido» y por el
 * nombre de la organización.
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
@Transactional
class UserOrganizationSearchIT {

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

  @Autowired private UserService userService;
  @Autowired private OrganizationService organizationService;
  @Autowired private JdbcTemplate jdbc;

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  @BeforeEach
  void seed() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);

    UUID demo = UUID.randomUUID();
    UUID privada = UUID.randomUUID();
    UUID sinRut = UUID.randomUUID();
    org(demo, "Organización Demo", "1.234.567-8");
    org(privada, "Compañía Privada", "21 345 678 9");
    org(sinRut, "Sin Documento", null);

    UUID ana = UUID.randomUUID();
    UUID jose = UUID.randomUUID();
    UUID sinOrg = UUID.randomUUID();
    user(ana, "ana.perez@prisma.local", "Ana", "Pérez");
    user(jose, "jose@prisma.local", "José", "Núñez");
    user(sinOrg, "solo@prisma.local", "Solo", "Usuario");
    member(ana, demo);
    member(jose, privada);
    member(jose, demo);
  }

  private void org(UUID id, String name, String identifier) {
    jdbc.update(
        "INSERT INTO prisma.organizations (id, name, identifier) VALUES (?, ?, ?)",
        id,
        name,
        identifier);
  }

  private void user(UUID id, String email, String first, String last) {
    jdbc.update(
        "INSERT INTO prisma.users (id, email, first_name, last_name) VALUES (?, ?, ?, ?)",
        id,
        email,
        first,
        last);
  }

  private void member(UUID userId, UUID orgId) {
    jdbc.update(
        "INSERT INTO prisma.user_organizations (user_id, organization_id) VALUES (?, ?)",
        userId,
        orgId);
  }

  private long users(String search) {
    return userService.list(search, 0, 50).total();
  }

  private long orgs(String search) {
    return organizationService.list(search, 0, 50).total();
  }

  // ---- Usuarios ----

  @Test
  void usuariosSinTextoDevuelveTodos() {
    // Las migraciones ya siembran un usuario administrador: se compara contra la lista completa.
    long all = userService.list(null, 0, 50).total();
    assertEquals(all, users("  "));
    assertEquals(true, all >= 3);
  }

  @Test
  void usuariosIgnoranTildesMayusculasYPuntos() {
    assertEquals(1, users("jose"));
    assertEquals(1, users("JOSÉ"));
    assertEquals(1, users("nunez"));
    assertEquals(1, users("Núñez"));
    assertEquals(1, users("perez"));
    // El punto del email y el que escribe quien busca no tienen que importar.
    assertEquals(1, users("ana.perez"));
    assertEquals(1, users("anaperez"));
  }

  @Test
  void usuariosEncuentranPorNombreCompleto() {
    assertEquals(1, users("Ana Pérez"));
    assertEquals(1, users("ana perez"));
    assertEquals(1, users("José Núñez"));
    assertEquals(0, users("Pérez Ana"));
  }

  @Test
  void usuariosEncuentranPorOrganizacion() {
    assertEquals(2, users("Organización Demo")); // Ana y José
    assertEquals(2, users("organizacion demo"));
    assertEquals(1, users("Compania Privada")); // sólo José
    assertEquals(0, users("zzz-no-existe"));
  }

  // ---- Organizaciones ----

  @Test
  void organizacionesIgnoranTildesYMayusculas() {
    assertEquals(1, orgs("Organización"));
    assertEquals(1, orgs("organizacion"));
    assertEquals(1, orgs("COMPAÑIA"));
    assertEquals(1, orgs("compania privada"));
  }

  @Test
  void organizacionesEncuentranElRutConOSinPuntosYGuiones() {
    assertEquals(1, orgs("1.234.567-8"));
    assertEquals(1, orgs("1234567-8"));
    assertEquals(1, orgs("12345678"));
    assertEquals(1, orgs("234.567"));
    // El identificador guardado con espacios también se encuentra con puntos o pegado.
    assertEquals(1, orgs("21.345.678-9"));
    assertEquals(1, orgs("213456789"));
  }

  @Test
  void organizacionesConRutNuloNoRompenLaBusqueda() {
    assertEquals(1, orgs("Sin Documento"));
    assertEquals(3, orgs(null));
  }

  @Test
  void soloSeparadoresNoCoincideConTodo() {
    assertEquals(0, orgs("---"));
    assertEquals(0, orgs("."));
  }
}
