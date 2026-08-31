package uy.edu.prisma.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uy.edu.prisma.config.KeycloakAdminConfig.KeycloakAdminProperties;

/**
 * Cada método público de {@link KeycloakAdminClient} propaga las excepciones (a diferencia de
 * {@link AiEvidenceClient}, que es best-effort) -- ver el javadoc de la clase: dejar un usuario "a
 * medias" en Keycloak es justo el bug que este cliente corrige, así que un fallo debe notarse.
 */
class KeycloakAdminClientTest {

  private static final String BASE_URL = "http://keycloak.test";
  private static final KeycloakAdminProperties PROPERTIES =
      new KeycloakAdminProperties(BASE_URL, "prisma", "prisma-backend", "secret");

  private MockRestServiceServer server;
  private KeycloakAdminClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new KeycloakAdminClient(builder.build(), PROPERTIES);
  }

  private void expectAdminToken() {
    server
        .expect(requestTo(BASE_URL + "/realms/prisma/protocol/openid-connect/token"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"tok\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
  }

  @Test
  void createUserAssignsRolesAndReturnsKeycloakId() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .location(
                    java.net.URI.create(BASE_URL + "/admin/realms/prisma/users/" + keycloakId)));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/roles/AUDITOR"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("{\"id\":\"role-1\",\"name\":\"AUDITOR\"}", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    UUID result = client.createUser("a@test.com", "Ana", "Test", "Passw0rd!", Set.of("AUDITOR"));

    assertEquals(keycloakId, result);
    server.verify();
  }

  @Test
  void createUserThrowsWhenLocationHeaderMissing() {
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.CREATED)); // sin header Location

    assertThrows(
        IllegalStateException.class,
        () -> client.createUser("a@test.com", "Ana", "Test", "Passw0rd!", Set.of()));
  }

  @Test
  void createUserRollsBackWhenRoleAssignmentFails() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .location(
                    java.net.URI.create(BASE_URL + "/admin/realms/prisma/users/" + keycloakId)));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withServerError());
    // No debe quedar un usuario huérfano en Keycloak si no se le pudieron asignar los roles:
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());

    assertThrows(
        RestClientException.class,
        () -> client.createUser("a@test.com", "Ana", "Test", "Passw0rd!", Set.of("AUDITOR")));
    server.verify();
  }

  @Test
  void updateProfileSendsEnabledFlag() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andRespond(withSuccess());

    assertDoesNotThrow(() -> client.updateProfile(keycloakId, "a@test.com", "Ana", "Test", false));
    server.verify();
  }

  @Test
  void assignRealmRolesRemovesAndAddsToMatchDesiredSet() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("[{\"id\":\"r0\",\"name\":\"OLD_ROLE\"}]", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/roles/NEW_ROLE"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("{\"id\":\"r1\",\"name\":\"NEW_ROLE\"}", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    client.assignRealmRoles(keycloakId, Set.of("NEW_ROLE"));

    server.verify();
  }

  @Test
  void assignRealmRolesSkipsRolesAlreadyAssigned() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("[{\"id\":\"r1\",\"name\":\"AUDITOR\"}]", MediaType.APPLICATION_JSON));

    // Ni DELETE ni POST de role-mappings deberían ejecutarse: ya está el rol que se pidió.
    client.assignRealmRoles(keycloakId, Set.of("AUDITOR"));

    server.verify();
  }

  @Test
  void assignRealmRolesRemovesAllWhenDesiredIsNull() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("[{\"id\":\"r1\",\"name\":\"AUDITOR\"}]", MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/role-mappings/realm"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());

    client.assignRealmRoles(keycloakId, null);

    server.verify();
  }

  @Test
  void resetPasswordSendsTemporaryCredential() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(
            requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId + "/reset-password"))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess());

    assertDoesNotThrow(() -> client.resetPassword(keycloakId, "NuevaPass1!"));
    server.verify();
  }

  @Test
  void deleteUserSucceeds() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());

    assertDoesNotThrow(() -> client.deleteUser(keycloakId));
    server.verify();
  }

  @Test
  void deleteUserSwallowsErrorsFromKeycloak() {
    UUID keycloakId = UUID.randomUUID();
    expectAdminToken();
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withServerError());

    // No debe bloquear el borrado del usuario local por un problema del lado de Keycloak.
    assertDoesNotThrow(() -> client.deleteUser(keycloakId));
  }

  @Test
  void adminTokenIsCachedAcrossCallsWithinExpiry() {
    UUID keycloakId1 = UUID.randomUUID();
    UUID keycloakId2 = UUID.randomUUID();
    expectAdminToken(); // una sola vez: la segunda llamada reutiliza el token cacheado
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId1))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
        .andRespond(withSuccess());
    server
        .expect(requestTo(BASE_URL + "/admin/realms/prisma/users/" + keycloakId2))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
        .andRespond(withSuccess());

    client.deleteUser(keycloakId1);
    client.deleteUser(keycloakId2);

    server.verify();
  }

  @Test
  void fetchLoginFailuresFiltersOutEventsAtOrBeforeSince() {
    expectAdminToken();
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/events?type=LOGIN_ERROR&max=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                [
                  {"time": 1000, "type": "LOGIN_ERROR", "ipAddress": "1.1.1.1", "error": "invalid_user_credentials", "details": {"username": "old@test.com"}},
                  {"time": 2000, "type": "LOGIN_ERROR", "ipAddress": "2.2.2.2", "error": "user_not_found", "details": {"username": "new@test.com"}}
                ]
                """,
                MediaType.APPLICATION_JSON));

    List<KeycloakAdminClient.LoginFailureEvent> result = client.fetchLoginFailures(1000);

    assertEquals(1, result.size());
    assertEquals("new@test.com", result.get(0).username());
    assertEquals("2.2.2.2", result.get(0).ipAddress());
    assertEquals("user_not_found", result.get(0).error());
    assertEquals(2000, result.get(0).time());
    server.verify();
  }

  @Test
  void fetchLoginFailuresReturnsEmptyWhenKeycloakHasNoEvents() {
    expectAdminToken();
    server
        .expect(
            requestTo(
                BASE_URL + "/admin/realms/prisma/events?type=LOGIN_ERROR&max=100"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    List<KeycloakAdminClient.LoginFailureEvent> result = client.fetchLoginFailures(0);

    assertTrue(result.isEmpty());
  }
}
