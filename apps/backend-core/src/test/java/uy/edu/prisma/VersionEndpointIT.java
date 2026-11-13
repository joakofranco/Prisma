package uy.edu.prisma;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * La cadena de seguridad real: {@code /api/version} es público (lo muestra el login) y devuelve
 * menos datos sin sesión; el resto de la API sigue pidiendo autenticación.
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
@AutoConfigureMockMvc
class VersionEndpointIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("prisma_test")
          .withUsername("test")
          .withPassword("test")
          .withInitScript("db/init-test.sql");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
    registry.add("prisma.app.environment", () -> "staging");
    registry.add("prisma.app.commit", () -> "abc1234");
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  @Test
  void isPublicAndReturnsOnlyTheVersionAndTheEnvironmentWithoutASession() throws Exception {
    mockMvc
        .perform(get("/api/version"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.application").value("prisma-backend-core"))
        .andExpect(jsonPath("$.version").isNotEmpty())
        .andExpect(jsonPath("$.environment").value("staging"))
        .andExpect(jsonPath("$.development").value(false))
        .andExpect(jsonPath("$.commit").doesNotExist());
  }

  @Test
  void addsTheCommitWhenThereIsASession() throws Exception {
    mockMvc
        .perform(get("/api/version").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commit").value("abc1234"));
  }

  @Test
  void theRestOfTheApiStillRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/organizations")).andExpect(status().isUnauthorized());
  }
}
