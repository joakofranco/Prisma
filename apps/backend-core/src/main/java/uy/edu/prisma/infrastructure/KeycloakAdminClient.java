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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uy.edu.prisma.config.KeycloakAdminConfig.KeycloakAdminProperties;
import uy.edu.prisma.domain.exception.InvalidRequestException;

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
      @Qualifier("keycloakAdminRestClient") RestClient keycloakAdminRestClient,
      KeycloakAdminProperties properties) {
    this.restClient = keycloakAdminRestClient;
    this.properties = properties;
  }

  /** Crea el usuario en Keycloak, le fija la contraseña y le asigna los roles de realm dados. */
  public UUID createUser(
      String email, String firstName, String lastName, String password, Set<String> realmRoles) {
    CredentialRepresentation credential = new CredentialRepresentation("password", password, true);
    return provision(
        new UserRepresentation(email, email, firstName, lastName, true, true, List.of(credential)),
        realmRoles);
  }

  /**
   * Crea un usuario invitado (RF-USR-05, PRI-240): sin contraseña y con el email sin verificar. No
   * puede iniciar sesión hasta seguir el enlace de {@link #sendExecuteActionsEmail}, que le pide
   * verificar el email y crear su contraseña: nadie más la conoce.
   */
  public UUID createInvitedUser(
      String email, String firstName, String lastName, Set<String> realmRoles) {
    return provision(
        new UserRepresentation(email, email, firstName, lastName, true, false, null), realmRoles);
  }

  /**
   * Pide a Keycloak que le envíe al usuario el correo de «acciones requeridas» (en PRISMA, la
   * invitación: ver infra/keycloak/themes/prisma/email/html/executeActions.ftl). El enlace vence en
   * {@code lifespanSeconds} y, completadas las acciones, lleva a {@code redirectUri}. Si el correo
   * no sale (SMTP mal configurado, Keycloak caído...) lanza {@link EmailDeliveryException}: quien
   * llama decide si deshace el alta.
   */
  public void sendExecuteActionsEmail(
      UUID keycloakId,
      List<String> actions,
      int lifespanSeconds,
      String clientId,
      String redirectUri) {
    try {
      restClient
          .put()
          .uri(
              "/admin/realms/{realm}/users/{id}/execute-actions-email"
                  + "?lifespan={lifespan}&client_id={clientId}&redirect_uri={redirectUri}",
              properties.realm(),
              keycloakId,
              lifespanSeconds,
              clientId,
              redirectUri)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(actions)
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException e) {
      log.warn(
          "Keycloak no pudo enviar el correo de acciones a {}: {}", keycloakId, e.getMessage());
      throw new EmailDeliveryException(e);
    }
  }

  /** El correo de {@link #sendExecuteActionsEmail} no se pudo enviar. */
  public static class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(Throwable cause) {
      super("Keycloak no pudo enviar el correo", cause);
    }
  }

  private UUID provision(UserRepresentation body, Set<String> realmRoles) {
    ResponseEntity<Void> response;
    try {
      response =
          restClient
              .post()
              .uri("/admin/realms/{realm}/users", properties.realm())
              .header("Authorization", "Bearer " + adminToken())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toBodilessEntity();
    } catch (HttpClientErrorException.BadRequest e) {
      throw invalidData(e);
    }

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
    try {
      restClient
          .put()
          .uri("/admin/realms/{realm}/users/{id}", properties.realm(), keycloakId)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.BadRequest e) {
      throw invalidData(e);
    }
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
   * ya activo desde el panel de usuarios (UserService.update) -- en ambos casos quien fija la clave
   * espera que esa sea, sin pasos extra, la que habilita el acceso.
   */
  public void setPermanentPassword(UUID keycloakId, String newPassword) {
    setPassword(keycloakId, newPassword, false);
  }

  /**
   * Borra TODAS las credenciales OTP (MFA) del usuario en Keycloak y devuelve cuántas borró. Es lo
   * que hace un PRISMA_ADMIN cuando le restablece la contraseña a quien perdió el acceso a su
   * autenticador (PRI-365): así no hace falta entrar a la consola de Keycloak. A diferencia de
   * {@link #deleteUser}, NO es best-effort: si falla, quien lo pidió tiene que enterarse (si no, el
   * usuario seguiría pidiendo un código que ya no puede generar).
   */
  public int removeOtpCredentials(UUID keycloakId) {
    CredentialSummary[] credentials =
        restClient
            .get()
            .uri("/admin/realms/{realm}/users/{id}/credentials", properties.realm(), keycloakId)
            .header("Authorization", "Bearer " + adminToken())
            .retrieve()
            .body(CredentialSummary[].class);
    int removed = 0;
    for (CredentialSummary credential :
        credentials != null ? credentials : new CredentialSummary[0]) {
      if (!"otp".equals(credential.type())) {
        continue;
      }
      restClient
          .delete()
          .uri(
              "/admin/realms/{realm}/users/{id}/credentials/{credentialId}",
              properties.realm(),
              keycloakId,
              credential.id())
          .header("Authorization", "Bearer " + adminToken())
          .retrieve()
          .toBodilessEntity();
      removed++;
    }
    return removed;
  }

  /**
   * Estado de la protección contra fuerza bruta de Keycloak para un usuario (PRI-233): cuántos
   * intentos fallidos lleva seguidos y si está bloqueado ahora. Igual que {@link
   * #removeOtpCredentials}, NO es best-effort: si falla, quien lo pidió tiene que enterarse.
   */
  public LoginFailureStatus loginFailureStatus(UUID keycloakId) {
    LoginFailureStatus status =
        restClient
            .get()
            .uri(
                "/admin/realms/{realm}/attack-detection/brute-force/users/{id}",
                properties.realm(),
                keycloakId)
            .header("Authorization", "Bearer " + adminToken())
            .retrieve()
            .body(LoginFailureStatus.class);
    return status != null ? status : new LoginFailureStatus(false, 0);
  }

  /**
   * Borra los intentos fallidos de login de un usuario: si la cuenta estaba bloqueada
   * temporalmente, queda habilitada de inmediato y el contador vuelve a cero (PRI-233).
   */
  public void clearLoginFailures(UUID keycloakId) {
    restClient
        .delete()
        .uri(
            "/admin/realms/{realm}/attack-detection/brute-force/users/{id}",
            properties.realm(),
            keycloakId)
        .header("Authorization", "Bearer " + adminToken())
        .retrieve()
        .toBodilessEntity();
  }

  private void setPassword(UUID keycloakId, String newPassword, boolean temporary) {
    CredentialRepresentation credential =
        new CredentialRepresentation("password", newPassword, temporary);
    try {
      restClient
          .put()
          .uri("/admin/realms/{realm}/users/{id}/reset-password", properties.realm(), keycloakId)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(credential)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException.BadRequest e) {
      throw invalidData(e);
    }
  }

  /**
   * Keycloak responde 400 cuando rechaza los DATOS (email mal formado, contraseña que no cumple la
   * política del realm...). Eso es un error del usuario, no del servidor: se traduce a {@link
   * InvalidRequestException} (HTTP 400 con un mensaje legible) en vez de dejarlo escapar como un
   * 500 "Error inesperado". La validación de entrada del DTO debería frenar los casos comunes antes
   * de llegar acá; esto es la red de seguridad para lo que Keycloak valida distinto.
   */
  private static InvalidRequestException invalidData(HttpClientErrorException.BadRequest e) {
    String body = e.getResponseBodyAsString();
    String lower = body.toLowerCase(java.util.Locale.ROOT);
    log.warn("Keycloak rechazó los datos con 400: {}", body);
    if (lower.contains("invalid-email") || lower.contains("\"field\":\"email\"")) {
      return new InvalidRequestException("El email no es válido");
    }
    if (lower.contains("password")) {
      return new InvalidRequestException(
          "La contraseña no cumple la política de seguridad del sistema");
    }
    return new InvalidRequestException(
        "Los datos del usuario no son válidos para el servicio de autenticación");
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
    } catch (HttpClientErrorException.BadRequest e) {
      return false;
    } catch (HttpClientErrorException.Unauthorized e) {
      // Keycloak 26 responde 400 invalid_grant ante credenciales incorrectas, pero según versión o
      // configuración puede responder 401. También es 401 si el client secret de prisma-backend
      // está mal: se loguea para que esa mala configuración no pase por "contraseña incorrecta"
      // sin dejar rastro.
      log.warn(
          "Keycloak respondió 401 al verificar la contraseña: {}", e.getResponseBodyAsString());
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
   * Envía un correo de prueba con la configuración SMTP dada (la misma que devuelve {@link
   * #getSmtpConfig()}, incluido el password enmascarado "**********" si no se cambió -- Keycloak
   * resuelve ese valor al password real ya persistido, igual que hace al guardar la config, así que
   * no hace falta pedirle al admin que lo reingrese sólo para probar). Lanza {@link
   * InvalidRequestException} con el detalle de Keycloak si el envío falla (credenciales inválidas,
   * host inalcanzable, etc.) -- a diferencia del resto de los métodos de esta clase, acá SÍ
   * conviene traducir el error de Keycloak en vez de dejarlo propagar tal cual, porque quien llama
   * necesita mostrárselo al admin para que corrija la configuración.
   */
  public void testSmtpConnection(Map<String, String> smtpServer, String testRecipientEmail) {
    try {
      restClient
          .post()
          .uri(
              "/admin/realms/{realm}/testSMTPConnection?user={user}",
              properties.realm(),
              testRecipientEmail)
          .header("Authorization", "Bearer " + adminToken())
          .contentType(MediaType.APPLICATION_JSON)
          .body(smtpServer)
          .retrieve()
          .toBodilessEntity();
    } catch (HttpClientErrorException e) {
      throw new InvalidRequestException(
          "No se pudo enviar el correo de prueba: verificá la configuración SMTP (host, puerto,"
              + " credenciales). Detalle de Keycloak: "
              + e.getStatusCode());
    }
  }

  /**
   * Intentos de login fallidos desde {@code sinceEpochMillisExclusive} (exclusivo), más recientes
   * primero según Keycloak. Requiere {@code eventsEnabled=true} en el realm (ver realm-prisma.json)
   * -- si no, Keycloak simplemente no guarda estos eventos y esto siempre devuelve vacío, no falla.
   * El client "prisma-backend" necesita además el rol "view-events" de realm-management (ver el
   * mismo archivo) o la Admin REST API responde 403.
   *
   * <p>{@code max=100}: no pagina más allá de eso -- pensado para sondearse cada pocos segundos
   * (ver LoginFailureAuditSyncService), así que un burst que superara 100 intentos fallidos entre
   * dos sondeos ya es en sí mismo un ataque en curso mucho más urgente que completar el historial
   * exacto.
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

  /**
   * Lo que Keycloak informa del bloqueo de un usuario: {@code disabled} es {@code true} mientras la
   * cuenta está bloqueada, y {@code numFailures} los intentos fallidos seguidos que lleva.
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  public record LoginFailureStatus(boolean disabled, int numFailures) {}

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

  /**
   * Credencial tal como la lista Keycloak (solo interesan el id y el tipo: «password», «otp»...).
   */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record CredentialSummary(String id, String type) {}

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
