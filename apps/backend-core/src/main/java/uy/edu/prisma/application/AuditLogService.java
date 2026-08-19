package uy.edu.prisma.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.CatalogVersion;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.web.dto.Dto.AuditLogDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

/** Registro de auditoría (append-only) de las acciones sensibles. */
@Service
@Transactional
public class AuditLogService {

  private final AuditLogRepository auditRepo;
  private final UserRepository userRepo;
  private final OrganizationRepository orgRepo;
  private final EvaluationRepository evaluationRepo;
  private final CatalogVersionRepository catalogVersionRepo;
  private final CurrentUserService currentUser;

  public AuditLogService(
      AuditLogRepository auditRepo,
      UserRepository userRepo,
      OrganizationRepository orgRepo,
      EvaluationRepository evaluationRepo,
      CatalogVersionRepository catalogVersionRepo,
      CurrentUserService currentUser) {
    this.auditRepo = auditRepo;
    this.userRepo = userRepo;
    this.orgRepo = orgRepo;
    this.evaluationRepo = evaluationRepo;
    this.catalogVersionRepo = catalogVersionRepo;
    this.currentUser = currentUser;
  }

  /**
   * Lista paginada de la bitácora. PRISMA_ADMIN ve las acciones de TODOS los tenants (sección
   * "administradores globales"); cualquier otro rol con acceso a este endpoint (hoy sólo
   * ORG_RESPONSIBLE, ver AuditLogController) sólo ve las de su propio tenant -- mismo criterio de
   * aislamiento multi-tenant que UserService.list. Sin tenant asignado (no debería pasar para
   * ORG_RESPONSIBLE, pero por las dudas) devuelve una página vacía en vez de fallar.
   */
  @Transactional(readOnly = true)
  public PaginatedDto<AuditLogDto> list(String search, String action, int page, int pageSize) {
    UUID tenantId = null;
    if (!currentUser.isPrismaAdmin()) {
      tenantId = currentUser.currentTenantId();
      if (tenantId == null) {
        return new PaginatedDto<>(List.of(), 0, page, pageSize);
      }
    }
    PageRequest pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    Page<AuditLog> result =
        auditRepo.search(tenantId, blankToNull(action), blankToNull(search), pageable);

    List<AuditLog> content = result.getContent();
    List<ResourceRef> resourceRefs =
        content.stream().map(log -> parseResource(log.getResource())).toList();

    // El actor (userId/tenantId) y el "resource" afectado (p.ej. "user:<id>") son cosas
    // DISTINTAS -- un PRISMA_ADMIN editando a otro usuario tiene su propio id como actor y el id
    // de ESE OTRO usuario como resource. Se unen ambos conjuntos para resolver los dos con una
    // sola consulta por tipo en vez de una por columna.
    Set<UUID> userIds =
        content.stream().map(AuditLog::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
    resourceRefs.stream()
        .filter(r -> "user".equals(r.type()) && r.id() != null)
        .forEach(r -> userIds.add(r.id()));

    Set<UUID> orgIds =
        content.stream()
            .map(AuditLog::getTenantId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    resourceRefs.stream()
        .filter(r -> "organization".equals(r.type()) && r.id() != null)
        .forEach(r -> orgIds.add(r.id()));

    Set<UUID> evaluationIds =
        resourceRefs.stream()
            .filter(r -> "evaluation".equals(r.type()) && r.id() != null)
            .map(ResourceRef::id)
            .collect(Collectors.toSet());
    Set<UUID> catalogVersionIds =
        resourceRefs.stream()
            .filter(r -> "catalog_version".equals(r.type()) && r.id() != null)
            .map(ResourceRef::id)
            .collect(Collectors.toSet());

    Map<UUID, User> usersById =
        userIds.isEmpty()
            ? Map.of()
            : userRepo.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u));
    Map<UUID, Organization> orgsById =
        orgIds.isEmpty()
            ? Map.of()
            : orgRepo.findAllById(orgIds).stream().collect(Collectors.toMap(Organization::getId, o -> o));
    Map<UUID, Evaluation> evaluationsById =
        evaluationIds.isEmpty()
            ? Map.of()
            : evaluationRepo.findAllById(evaluationIds).stream()
                .collect(Collectors.toMap(Evaluation::getId, e -> e));
    Map<UUID, CatalogVersion> catalogVersionsById =
        catalogVersionIds.isEmpty()
            ? Map.of()
            : catalogVersionRepo.findAllById(catalogVersionIds).stream()
                .collect(Collectors.toMap(CatalogVersion::getId, cv -> cv));

    List<AuditLogDto> dtos = new ArrayList<>(content.size());
    for (int i = 0; i < content.size(); i++) {
      dtos.add(
          toDto(
              content.get(i),
              resourceRefs.get(i),
              usersById,
              orgsById,
              evaluationsById,
              catalogVersionsById));
    }

    return new PaginatedDto<>(dtos, result.getTotalElements(), page, pageSize);
  }

  private AuditLogDto toDto(
      AuditLog log,
      ResourceRef resourceRef,
      Map<UUID, User> usersById,
      Map<UUID, Organization> orgsById,
      Map<UUID, Evaluation> evaluationsById,
      Map<UUID, CatalogVersion> catalogVersionsById) {
    User user = log.getUserId() != null ? usersById.get(log.getUserId()) : null;
    Organization tenant = log.getTenantId() != null ? orgsById.get(log.getTenantId()) : null;
    return new AuditLogDto(
        log.getId(),
        log.getUserId(),
        user != null ? user.getEmail() : null,
        user != null ? user.getFirstName() + " " + user.getLastName() : null,
        log.getTenantId(),
        tenant != null ? tenant.getName() : null,
        log.getAction(),
        log.getResource(),
        resolveResourceName(resourceRef, usersById, orgsById, evaluationsById, catalogVersionsById),
        log.getPayload(),
        log.getIpAddress(),
        log.getUserAgent(),
        log.getCreatedAt());
  }

  // "resource" es siempre "tipo:id" (p.ej. "user:38dab301-...") salvo los singleton sin id propio
  // (p.ej. "email-settings") -- ver los usos de record() en UserService/OrganizationService/etc.
  // Antes el frontend sólo traducía el TIPO y mostraba el id crudo acortado ("Usuario #38dab301"),
  // que seguía sin decir nada útil sobre a quién/qué afectó la acción -- esto resuelve el nombre
  // real de la entidad para que la columna "Recurso" diga, por ejemplo, "Usuario: Jane Doe".
  private record ResourceRef(String type, UUID id) {}

  private static ResourceRef parseResource(String resource) {
    if (resource == null) return new ResourceRef(null, null);
    int idx = resource.indexOf(':');
    if (idx == -1) return new ResourceRef(resource, null);
    try {
      return new ResourceRef(resource.substring(0, idx), UUID.fromString(resource.substring(idx + 1)));
    } catch (IllegalArgumentException e) {
      // Id malformado (no debería pasar, los que graba record() siempre son UUID.toString()) --
      // se sigue mostrando el tipo, simplemente sin nombre resuelto.
      return new ResourceRef(resource.substring(0, idx), null);
    }
  }

  // null = tipo no resuelto acá (p.ej. "email-settings", que no tiene entidad propia) o la
  // entidad referenciada ya no existe (fue borrada después de quedar registrada en la bitácora,
  // que es append-only y no se toca) -- el frontend cae al id acortado en ese caso.
  private String resolveResourceName(
      ResourceRef ref,
      Map<UUID, User> usersById,
      Map<UUID, Organization> orgsById,
      Map<UUID, Evaluation> evaluationsById,
      Map<UUID, CatalogVersion> catalogVersionsById) {
    if (ref.id() == null || ref.type() == null) return null;
    return switch (ref.type()) {
      case "user" -> {
        User u = usersById.get(ref.id());
        yield u != null ? (u.getFirstName() + " " + u.getLastName()).trim() : null;
      }
      case "organization" -> {
        Organization o = orgsById.get(ref.id());
        yield o != null ? o.getName() : null;
      }
      case "evaluation" -> {
        Evaluation e = evaluationsById.get(ref.id());
        yield e != null ? e.getName() : null;
      }
      case "catalog_version" -> {
        CatalogVersion cv = catalogVersionsById.get(ref.id());
        yield cv != null ? cv.getLabel() : null;
      }
      default -> null;
    };
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /**
   * Registra un intento de login FALLIDO -- llamada por LoginFailureAuditSyncService, nunca desde
   * un request HTTP interactivo (Keycloak maneja el login en sí, backend-core nunca ve ese POST;
   * esto sólo refleja en la bitácora propia lo que Keycloak ya audita internamente). Por eso, a
   * diferencia de {@link #record}, recibe el IP y el momento explícitos (los del evento de
   * Keycloak) en vez de leerlos del request actual -- no hay ningún request actual, esto corre en
   * un hilo del scheduler.
   *
   * <p>{@code resource} es directamente el email/username intentado, SIN el prefijo "tipo:" que
   * usa el resto de las acciones: si no matchea ningún tipo conocido, AuditLogController lo deja
   * pasar tal cual (ver AuditLogService.list -> parseResource), y el frontend termina mostrando
   * el email intentado tal cual en la columna "Recurso Afectado" -- que es exactamente el dato
   * útil acá. Si el email SÍ pertenece a un usuario real de PRISMA, además se lo linkea como
   * actor/tenant: así un ORG_RESPONSIBLE ve los intentos fallidos contra SUS propios usuarios
   * (mismo aislamiento multi-tenant que el resto de list()), no sólo PRISMA_ADMIN.
   */
  public void recordLoginFailure(String attemptedUsername, String ip, OffsetDateTime occurredAt) {
    User user = attemptedUsername == null ? null : userRepo.findByEmail(attemptedUsername).orElse(null);
    UUID tenantId = user != null && user.getTenant() != null ? user.getTenant().getId() : null;
    AuditLog log =
        AuditLog.builder()
            .userId(user != null ? user.getId() : null)
            .tenantId(tenantId)
            .action("LOGIN_FAILED")
            .resource(attemptedUsername != null ? attemptedUsername : "desconocido")
            .ipAddress(ip)
            .createdAt(occurredAt != null ? occurredAt : OffsetDateTime.now())
            .build();
    auditRepo.save(log);
  }

  public void record(String action, String resource, String payloadJson) {
    User user = currentUser.currentUser().orElse(null);
    UUID tenantId = user != null && user.getTenant() != null ? user.getTenant().getId() : null;
    AuditLog log =
        AuditLog.builder()
            .userId(user != null ? user.getId() : null)
            .tenantId(tenantId)
            .action(action)
            .resource(resource)
            .payload(payloadJson)
            .ipAddress(currentIp())
            .userAgent(currentUserAgent())
            .build();
    auditRepo.save(log);
  }

  // getRemoteAddr(), NO leer "X-Forwarded-For" a mano: "server.forward-headers-strategy: framework"
  // (application.yml) ya hace que Spring reescriba getRemoteAddr() a partir de ese header --
  // leerlo de nuevo acá era redundante y, peor, confiaba en el valor tal cual venía. nginx (único
  // proxy real frente a este servicio, ver infra/nginx/conf.d/prisma.conf y
  // apps/frontend/nginx.conf) SIEMPRE fija ese header con el IP que él mismo observó
  // ($remote_addr, no $proxy_add_x_forwarded_for) en vez de agregarle valor a lo que traiga el
  // cliente -- de lo contrario un cliente que manda su propio "X-Forwarded-For: 1.2.3.4" quedaba
  // registrado en la bitácora como si ese fuera el IP real (spoofing).
  private String currentIp() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest().getRemoteAddr();
    }
    return null;
  }

  private String currentUserAgent() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servlet) {
      return servlet.getRequest().getHeader("User-Agent");
    }
    return null;
  }
}
