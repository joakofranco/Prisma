package uy.edu.prisma.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import uy.edu.prisma.config.KeycloakAdminConfig.KeycloakAdminProperties;

/**
 * Provisiona cuentas de Keycloak para los usuarios dados de alta en PRISMA.
 *
 * <p>{@code prisma.users} es la fuente de verdad para autorización (roles, tenant), pero Keycloak
 * es la única fuente de verdad para autenticación (login). Antes de este cliente, {@code POST
 * /api/users} solo insertaba la fila local: el usuario quedaba visible en la app pero no podía
 * loguearse nunca, porque Keycloak jamás se enteraba de que existía. Cada método público de acá
 * lanza (no absorbe la excepción) para que {@code UserService} pueda hacer rollback de la fila
 * local si la cuenta de Keycloak no se pudo crear/actualizar -- dejar un usuario "a medias" (con
 * fila local pero sin poder loguearse) es exactamente el bug que esto corrige, así que nunca debe
 * volver a pasar silenciosamente.
 */
@Component
public class KeycloakAdminClient {

  private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

  private final RestClient restClient;
  private final KeycloakAdminProperties properties;

  private volatile String cachedToken;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  public KeycloakAdminClient(
      RestClient keycloakAdminRestClient, KeycloakAdminProperties properties) {
    this.restClient = keycloakAdminRestClient;
    this.properties = properties;
  }

  /** Crea el usuario en Keycloak, le fija la contraseña y le asigna los roles de realm dados. */
  public UUID createUser(
      String email, String firstName, String lastName, String password, Set<String> realmRoles) {
    CredentialRepresentation credential = new CredentialRepresentation("password", password, true);
    UserRepresentation body =
        new UserRepresentation(email, email, firstName, lastName, true, true, List.of(credential));

    ResponseEntity<Void> response =
        restClient
            .post()
            .uri("/admin/realms/{realm}/users", properties.realm())
            .header("Authorization", "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toBodilessEntity();

    String location = response.getHeaders().getFirst("Location");
    if (location == null) {
      throw new IllegalStateException("Keycloak no devolvió la ubicación del usuario creado");
    }
    UUID keycloakId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    try {
      assignRealmRoles(keycloakId, realmRoles);
    } catch (Exception e) {
      // No dejar un usuario a medias en Keycloak (creado pero sin los roles pedidos): mejor que
      // la creación falle por completo y el llamador pueda reintentar, a que quede una cuenta
      // huérfana sin ningún rol asignado.
      deleteUser(keycloakId);
      throw e;
    }
    return keycloakId;
  }

  /** Sincroniza perfil + estado habilitado/deshabilitado de un usuario ya provisionado. */
  public void updateProfile(
      UUID keycloakId, String email, String firstName, String lastName, boolean enabled) {
    UserRepresentation body =
        new UserRepresentation(email, email, firstName, lastName, enabled, null, null);
    restClient
        .put()
        .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakId)
        .header("Authorization", "Bearer " + adminToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  /** Reemplaza el set de roles de realm de un usuario por exactamente {@code realmRoles}. */
  public void assignRealmRoles(UUID keycloakId, Set<String> realmRoles) {
    List<RoleRepresentation> current =
        List.of(
            restClient
                .get()
                .uri(
                    "/admin/realms/{realm}/users/{id}/role-mappings/realm",
                    properties.realm(),
                    keycloakId)
                .header("Authorization", "Bearer " + adminToken())
                .retrieve()
                .body(RoleRepresentation[].class));

    Set<String> desired = realmRoles == null ? Set.of() : realmRoles;
    List<RoleRepresentation> toRemove =
        current.stream().filter(r -> !desired.contains(r.name())).toList();
    Set<String> alreadyAssigned = new HashSet<>();
    current.forEach(r -> alreadyAssigned.add(r.name()));

    if (!toRemove.isEmpty()) {
      restClient
          .method(org.springframework.http.HttpMethod.DELETE)
          .uri(
              "/admin/realms/{realm}/users/{id}/role-mappings/realm",
              properties.realm(),
              keycloakId)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(toRemove)
          .retrieve()
          .toBodilessEntity();
    }

    List<RoleRepresentation> toAdd =
        desired.stream()
            .filter(name -> !alreadyAssigned.contains(name))
            .map(this::realmRole)
            .toList();
    if (!toAdd.isEmpty()) {
      restClient
          .post()
          .uri(
              "/admin/realms/{realm}/users/{id}/role-mappings/realm",
              properties.realm(),
              keycloakId)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(toAdd)
          .retrieve()
          .toBodilessEntity();
    }
  }

  /** Fija una contraseña nueva. {@code temporary=true}: el usuario debe cambiarla al loguearse. */
  public void resetPassword(UUID keycloakId, String newPassword) {
    setPassword(keycloakId, newPassword, true);
  }

  /**
   * Igual que {@link #resetPassword}, pero {@code temporary=false}: la clave queda operativa de
   * inmediato, sin forzar otro cambio en el próximo login. Se usa tanto para autoservicio (el
   * propio usuario cambiando SU contraseña vía AccountService, ya validada contra la actual con
   * {@link #verifyPassword}) como para que un PRISMA_ADMIN le restablezca la clave a otro usuario
   * ya activo desde el panel de usuarios (UserService.update) -- en ambos casos quien fija la
   * clave espera que esa sea, sin pasos extra, la que habilita el acceso.
   */
  public void setPermanentPassword(UUID keycloakId, String newPassword) {
    setPassword(keycloakId, newPassword, false);
  }

  private void setPassword(UUID keycloakId, String newPassword, boolean temporary) {
    CredentialRepresentation credential =
        new CredentialRepresentation("password", newPassword, temporary);
    restClient
        .put()
        .uri("/admin/realms/{realm}/users/{id}/reset-password", properties.realm(), keycloakId)
        .header("Authorization", "Bearer " + adminToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(credential)
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * Intenta autenticar {@code email}+{@code password} contra Keycloak (grant_type=password, ROPC)
   * usando las credenciales del client confidencial prisma-backend -- requiere {@code
   * directAccessGrantsEnabled=true} en ese client (ver infra/keycloak/realm-prisma.json). Nunca se
   * usa para loguear al usuario de verdad (el login real lo hace el frontend contra Keycloak
   * directamente): es sólo la forma de comprobar "¿esta es realmente su contraseña actual?" antes
   * de dejarlo fijar una nueva, sin tener que guardar ni comparar hashes acá.
   *
   * <p>Devuelve {@code false} en caso de credenciales inválidas (Keycloak responde 400
   * invalid_grant) en vez de lanzar: es el resultado esperado de "escribió mal la contraseña
   * actual", no una falla de la integración. Cualquier otro error (Keycloak caído, red, 5xx) sí se
   * propaga -- no hay forma de distinguir "está mal" de "no se pudo verificar" y tratarlo como
   * "está mal" dejaría a cualquiera cambiar la contraseña de otro con solo tumbar Keycloak.
   */
  public boolean verifyPassword(String email, String password) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "password");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());
    form.add("username", email);
    form.add("password", password);

    try {
      restClient
          .post()
          .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .body(TokenResponse.class);
      return true;
    } catch (org.springframework.web.client.HttpClientErrorException.BadRequest e) {
      return false;
    }
  }

  /**
   * Best-effort: si falla (Keycloak caído, red, etc.), se registra pero no se propaga -- no debe
   * bloquear el borrado del usuario local por un problema del lado de Keycloak.
   */
  public void deleteUser(UUID keycloakId) {
    try {
      restClient
          .delete()
          .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakId)
          .header("Authorization", "Bearer " + adminToken())
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("No se pudo borrar la cuenta de Keycloak {}: {}", keycloakId, e.getMessage());
    }
  }

  /**
   * Configuración SMTP del realm (envío de emails: recuperación de contraseña, etc.). Keycloak
   * SIEMPRE enmascara el password como "**********" al leerlo -- no hay forma de recuperar el valor
   * real vía la Admin REST API una vez guardado, así que quien llame a esto debe pedirle al usuario
   * que lo vuelva a ingresar en cada actualización en vez de intentar "conservarlo".
   */
  public Map<String, String> getSmtpConfig() {
    RealmSmtpRepresentation realm =
        restClient
            .get()
            .uri("/admin/realms/{realm}", properties.realm())
            .header("Authorization", "Bearer " + adminToken())
            .retrieve()
            .body(RealmSmtpRepresentation.class);
    return realm != null && realm.smtpServer() != null ? realm.smtpServer() : Map.of();
  }

  /**
   * Reemplaza la configuración SMTP completa del realm. Keycloak reemplaza el objeto {@code
   * smtpServer} entero (no hace merge campo a campo) y además valida el resultado -- un "from"
   * vacío o mal formado hace fallar el PUT con 400 -- así que este método siempre exige el mapa
   * completo, nunca un delta parcial.
   */
  public void updateSmtpConfig(Map<String, String> smtpServer) {
    restClient
        .put()
        .uri("/admin/realms/{realm}", properties.realm())
        .header("Authorization", "Bearer " + adminToken())
        .contentType(MediaType.APPLICATION_JSON)
        .body(new RealmSmtpRepresentation(smtpServer))
        .retrieve()
        .toBodilessEntity();
  }

  /**
   * Intentos de login fallidos desde {@code sinceEpochMillisExclusive} (exclusivo), más recientes
   * primero según Keycloak. Requiere {@code eventsEnabled=true} en el realm (ver
   * realm-prisma.json) -- si no, Keycloak simplemente no guarda estos eventos y esto siempre
   * devuelve vacío, no falla. El client "prisma-backend" necesita además el rol "view-events" de
   * realm-management (ver el mismo archivo) o la Admin REST API responde 403.
   *
   * <p>{@code max=100}: no pagina más allá de eso -- pensado para sondearse cada
   * pocos segundos (ver LoginFailureAuditSyncService), así que un burst que superara 100 intentos
   * fallidos entre dos sondeos ya es en sí mismo un ataque en curso mucho más urgente que
   * completar el historial exacto.
   */
  public List<LoginFailureEvent> fetchLoginFailures(long sinceEpochMillisExclusive) {
    KeycloakEventRepresentation[] events =
        restClient
            .get()
            .uri("/admin/realms/{realm}/events?type=LOGIN_ERROR&max=100", properties.realm())
            .header("Authorization", "Bearer " + adminToken())
            .retrieve()
            .body(KeycloakEventRepresentation[].class);
    if (events == null) {
      return List.of();
    }
    return Arrays.stream(events)
        .filter(e -> e.time() > sinceEpochMillisExclusive)
        .map(
            e ->
                new LoginFailureEvent(
                    e.time(),
                    e.ipAddress(),
                    e.details() != null ? e.details().get("username") : null,
                    e.error()))
        .toList();
  }

  public record LoginFailureEvent(long time, String ipAddress, String username, String error) {}

  // Representación PARCIAL del evento de Keycloak -- sólo los campos que se usan. "details" trae
  // el username tal cual se escribió en el formulario de login (existe o no la cuenta), que es
  // justo lo que hace falta para auditar "quién intentó entrar y falló".
  private record KeycloakEventRepresentation(
      long time, String type, String ipAddress, String error, Map<String, String> details) {}

  private RoleRepresentation realmRole(String name) {
    return restClient
        .get()
        .uri("/admin/realms/{realm}/roles/{name}", properties.realm(), name)
        .header("Authorization", "Bearer " + adminToken())
        .retrieve()
        .body(RoleRepresentation.class);
  }

  private synchronized String adminToken() {
    if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)) {
      return cachedToken;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", properties.clientId());
    form.add("client_secret", properties.clientSecret());

    TokenResponse response =
        restClient
            .post()
            .uri("/realms/{realm}/protocol/openid-connect/token", properties.realm())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    if (response == null) {
      throw new IllegalStateException("No se pudo obtener un token de administración de Keycloak");
    }
    cachedToken = response.accessToken();
    tokenExpiresAt = Instant.now().plusSeconds(Math.max(response.expiresIn() - 10, 5));
    return cachedToken;
  }

  private record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}

  private record CredentialRepresentation(String type, String value, boolean temporary) {}

  private record UserRepresentation(
      String username,
      String email,
      @JsonProperty("firstName") String firstName,
      @JsonProperty("lastName") String lastName,
      Boolean enabled,
      @JsonProperty("emailVerified") Boolean emailVerified,
      List<CredentialRepresentation> credentials) {}

  private record RoleRepresentation(String id, String name) {}

  // Representación PARCIAL del realm -- Keycloak acepta un JSON con sólo este campo en el PUT
  // /admin/realms/{realm} y deja el resto de la configuración del realm intacta (verificado
  // contra la Admin REST API real: un PUT con únicamente {"smtpServer": {...}} no toca
  // resetPasswordAllowed, loginWithEmailAllowed, etc.).
  private record RealmSmtpRepresentation(
      @JsonProperty("smtpServer") Map<String, String> smtpServer) {}
}
