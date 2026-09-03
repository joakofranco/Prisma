package uy.edu.prisma.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.infrastructure.KeycloakAdminClient.LoginFailureEvent;

/**
 * Trae a la bitácora propia ({@link AuditLogService#recordLoginFailure}) los intentos de login
 * fallidos que Keycloak ya audita solo (eventsEnabled=true, ver realm-prisma.json) -- backend-core
 * nunca ve el POST del login en sí (lo maneja Keycloak directamente, ver
 * services/auth.ts del frontend), así que no hay forma de enterarse de un intento fallido más que
 * yendo a buscarlo del lado de Keycloak. El BLOQUEO en sí (cuántos intentos antes de bloquear la
 * cuenta) lo hace Keycloak (Brute Force Detection, "failureFactor" en el realm) -- esto sólo le da
 * visibilidad a PRISMA_ADMIN/ORG_RESPONSIBLE desde "Actividad del Sistema" sin que tengan que
 * entrar a la consola de Keycloak.
 *
 * <p>{@code lastPolledEpochMillis} es en memoria, no persistido: arranca en "ahora" en cada boot
 * de backend-core, así que un restart no reproduce el historial completo de fallos viejos (sólo
 * los que ocurran de ahí en adelante). Aceptable para este propósito -- es una vista de actividad
 * reciente, no el registro forense definitivo, que sigue existiendo íntegro del lado de Keycloak
 * (Admin Console > Realm Settings > Sessions/Events) mientras dure "eventsExpiration".
 */
@Service
@ConditionalOnProperty(
    prefix = "prisma.security.login-failure-sync",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LoginFailureAuditSyncService {

  private static final Logger log = LoggerFactory.getLogger(LoginFailureAuditSyncService.class);

  private final KeycloakAdminClient keycloakAdminClient;
  private final AuditLogService auditLogService;

  private volatile long lastPolledEpochMillis = System.currentTimeMillis();

  public LoginFailureAuditSyncService(
      KeycloakAdminClient keycloakAdminClient, AuditLogService auditLogService) {
    this.keycloakAdminClient = keycloakAdminClient;
    this.auditLogService = auditLogService;
  }

  @Scheduled(
      fixedDelayString = "${prisma.security.login-failure-sync.interval-ms:15000}",
      initialDelayString = "${prisma.security.login-failure-sync.interval-ms:15000}")
  public void syncLoginFailures() {
    List<LoginFailureEvent> events;
    try {
      events = keycloakAdminClient.fetchLoginFailures(lastPolledEpochMillis);
    } catch (Exception e) {
      // Best-effort: Keycloak momentáneamente inalcanzable, o el client "prisma-backend" todavía
      // sin el rol "view-events" en un ambiente ya desplegado antes de este cambio -- no debe
      // tumbar el scheduler. Al no avanzar lastPolledEpochMillis, la próxima corrida reintenta
      // desde el mismo punto en vez de perder los eventos de este intervalo.
      log.warn("No se pudo sincronizar intentos de login fallidos desde Keycloak: {}", e.getMessage());
      return;
    }
    if (events.isEmpty()) {
      return;
    }
    long maxTime = lastPolledEpochMillis;
    for (LoginFailureEvent event : events) {
      auditLogService.recordLoginFailure(
          event.username(),
          event.ipAddress(),
          OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.time()), ZoneOffset.UTC));
      maxTime = Math.max(maxTime, event.time());
    }
    lastPolledEpochMillis = maxTime;
  }
}
