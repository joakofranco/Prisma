package uy.edu.prisma.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.entity.UserRole;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.domain.repository.UserRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.CreateOrganizationDto;
import uy.edu.prisma.web.dto.Dto.OrganizationDto;
import uy.edu.prisma.web.dto.Dto.OrganizationReactivationDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

@Service
@Transactional
public class OrganizationService {

  private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

  private final OrganizationRepository repo;
  private final AuditLogService auditLog;
  private final CurrentUserService currentUser;
  private final UserRepository userRepo;
  private final KeycloakAdminClient keycloakAdmin;

  public OrganizationService(
      OrganizationRepository repo,
      AuditLogService auditLog,
      CurrentUserService currentUser,
      UserRepository userRepo,
      KeycloakAdminClient keycloakAdmin) {
    this.repo = repo;
    this.auditLog = auditLog;
    this.currentUser = currentUser;
    this.userRepo = userRepo;
    this.keycloakAdmin = keycloakAdmin;
  }

  // Aislamiento multi-tenant (ver CurrentUserService.assertOrganizationAccess, que sigue el mismo
  // criterio para operaciones puntuales): PRISMA_ADMIN y el evaluador general ven todas; el resto
  // solo las suyas (una o varias). Antes esto devolvía TODAS
  // las organizaciones sin importar el rol -- cualquier usuario autenticado podía enumerar la
  // plataforma entera (nombre, identificador, sector) de organizaciones ajenas.
  @Transactional(readOnly = true)
  public PaginatedDto<OrganizationDto> list(String search, int page, int pageSize) {
    PageRequest pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    // PRI-368: sin tildes, mayúsculas ni puntos; el identificador además sin guiones ni espacios.
    String pattern = SearchText.pattern(search);
    String idPattern = SearchText.idPattern(search);
    Page<Organization> result;
    if (currentUser.hasAllOrganizationsAccess()) {
      result = repo.search(pattern, idPattern, pageable);
    } else {
      Set<UUID> allowedIds = currentUser.organizationIds();
      result =
          allowedIds.isEmpty()
              ? Page.empty(pageable)
              : repo.searchByIdIn(allowedIds, pattern, idPattern, pageable);
    }
    return new PaginatedDto<>(
        result.getContent().stream().map(this::toDto).toList(),
        result.getTotalElements(),
        page,
        pageSize);
  }

  @Transactional(readOnly = true)
  public OrganizationDto getById(UUID id) {
    currentUser.assertOrganizationAccess(id);
    Organization org =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organización", id));
    return toDto(org);
  }

  public OrganizationDto create(CreateOrganizationDto dto) {
    ResolvedIdentifier identifier = resolveIdentifier(dto);
    assertIdentifierAvailable(identifier.value(), null);
    Organization org =
        Organization.builder()
            .name(dto.name())
            .identifier(identifier.value())
            .identifierIsRut(identifier.isRut())
            .sector(dto.sector())
            .size(dto.size())
            .enabled(true)
            .build();
    OrganizationDto result = toDto(repo.save(org));
    auditLog.record(
        "CREATE", "organization:" + result.id(), "{\"name\":\"" + result.name() + "\"}");
    return result;
  }

  public OrganizationDto update(UUID id, CreateOrganizationDto dto) {
    Organization org =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organización", id));
    ResolvedIdentifier identifier = resolveIdentifier(dto);
    assertIdentifierAvailable(identifier.value(), id);
    org.setName(dto.name());
    org.setIdentifier(identifier.value());
    org.setIdentifierIsRut(identifier.isRut());
    org.setSector(dto.sector());
    org.setSize(dto.size());
    OrganizationDto result = toDto(repo.save(org));
    auditLog.record("UPDATE", "organization:" + id, "{\"name\":\"" + result.name() + "\"}");
    return result;
  }

  /** Identificador ya validado, tal como se guarda, y si es un RUT. */
  private record ResolvedIdentifier(String value, boolean isRut) {}

  // Valida el identificador en el servidor (la única validación que cuenta: el formulario solo
  // avisa antes). Sin texto no hay identificador (NULL, ver blankToNull). Con la casilla «es un
  // RUT»
  // se exige un RUT uruguayo válido y se guarda normalizado («AA-BBBBBB-001-D»); sin ella se exige
  // un largo mínimo y se guarda tal cual, sin espacios en los extremos. Los errores se asocian al
  // campo «identifier» para que el formulario lo marque.
  private static ResolvedIdentifier resolveIdentifier(CreateOrganizationDto dto) {
    String raw = blankToNull(dto.identifier());
    if (raw == null) {
      return new ResolvedIdentifier(null, false);
    }
    if (Boolean.TRUE.equals(dto.identifierIsRut())) {
      String normalized = UruguayanRut.normalize(raw);
      if (normalized == null) {
        throw new InvalidRequestException(OrganizationMessages.INVALID_RUT, "identifier");
      }
      return new ResolvedIdentifier(normalized, true);
    }
    String trimmed = raw.trim();
    if (trimmed.length() < OrganizationMessages.MIN_IDENTIFIER_LENGTH) {
      throw new InvalidRequestException(OrganizationMessages.IDENTIFIER_TOO_SHORT, "identifier");
    }
    if (trimmed.length() > OrganizationMessages.MAX_IDENTIFIER_LENGTH) {
      throw new InvalidRequestException(OrganizationMessages.IDENTIFIER_TOO_LONG, "identifier");
    }
    return new ResolvedIdentifier(trimmed, false);
  }

  // Rechaza con un mensaje claro (409, asociado al campo "identifier") un identificador que ya
  // tiene OTRA organización, comparando sin puntos / guiones / espacios, SEA O NO un RUT. Un
  // identificador vacío no se chequea (es opcional y varias organizaciones pueden no tenerlo).
  // `selfId` = la organización que se está editando, para que conservar su propio identificador no
  // cuente como duplicado.
  private void assertIdentifierAvailable(String identifier, UUID selfId) {
    String normalized = normalizeIdentifier(identifier);
    if (normalized.isEmpty()) {
      return;
    }
    repo.findByNormalizedIdentifier(normalized).stream()
        .filter(other -> !other.getId().equals(selfId))
        .findFirst()
        .ifPresent(
            other -> {
              throw new ConflictException(
                  OrganizationMessages.duplicateIdentifier(identifier, other.getName()),
                  "identifier");
            });
  }

  static String normalizeIdentifier(String identifier) {
    return identifier == null
        ? ""
        : identifier.replaceAll("[.\\-\\s]", "").toUpperCase(java.util.Locale.ROOT);
  }

  // Un identificador en blanco ("") debe guardarse como NULL, no como cadena vacía: la UNIQUE
  // constraint de Postgres SÍ los distingue -- NULL no cuenta contra ella (varias organizaciones
  // sin identificador conviven bien), pero "" sí es un valor como cualquier otro, así que la
  // SEGUNDA organización creada sin identificador rompería el alta con una violación de unicidad
  // si no se normalizara acá.
  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /**
   * "Elimina" la organizacion (baja logica, no DELETE de la fila): users.tenant_id y
   * evaluations.organization_id referencian organizations sin ON DELETE CASCADE, asi que un DELETE
   * real revienta con una violacion de integridad referencial en cuanto la organizacion tiene algun
   * usuario o evaluacion asociada -- casi siempre, en la practica. Ademas de evitar ese error, este
   * comportamiento es el pedido: la organizacion deja de estar activa pero todo su historico
   * (evaluaciones, evidencia, plan de mejora, auditoria) se preserva intacto y consultable, en vez
   * de perderse en cascada. queda deshabilitada (enabled=false), igual que countByEnabledTrue() y
   * el resto del modelo ya asumen para distinguir orgs activas.
   */
  public void delete(UUID id) {
    Organization org =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organización", id));
    org.setEnabled(false);
    repo.save(org);
    int usersDisabled = disableUsersOf(id);
    auditLog.record("DELETE", "organization:" + id, "{\"usersDisabled\":" + usersDisabled + "}");
  }

  /**
   * Deshabilita (en la base y en Keycloak, para que no puedan iniciar sesión) a los usuarios cuyo
   * acceso depende solo de organizaciones inactivas: los de esta organización a quienes no les
   * queda ninguna otra activa. Quedan fuera:
   *
   * <ul>
   *   <li>los AUDITOR: auditan varias organizaciones a la vez, así que aunque hoy solo tengan esta
   *       asociada su acceso no depende de ella;
   *   <li>los PRISMA_ADMIN: rol global, no atado a ninguna organización (y evita que quien
   *       deshabilita se bloquee a sí mismo).
   * </ul>
   *
   * Todo o nada: si Keycloak falla con alguno, se re-habilitan en Keycloak los que ya se habían
   * deshabilitado (la base se revierte sola con la transacción) y se propaga el error -- la
   * organización no queda deshabilitada a medias. Reactivar la organización NO vuelve a habilitar a
   * sus usuarios: no hay forma de distinguir a quienes se deshabilitaron por esto de quienes ya lo
   * estaban a mano, así que el administrador los rehabilita uno por uno.
   */
  private int disableUsersOf(UUID organizationId) {
    List<User> toDisable =
        cascadeScope(organizationId).stream()
            .filter(u -> Boolean.TRUE.equals(u.getEnabled()))
            .filter(u -> allOrganizationsInactive(u, organizationId))
            .toList();
    List<User> disabledInKeycloak = new ArrayList<>();
    try {
      for (User u : toDisable) {
        u.setEnabled(false);
        if (u.getKeycloakId() != null) {
          keycloakAdmin.updateProfile(
              u.getKeycloakId(), u.getEmail(), u.getFirstName(), u.getLastName(), false);
          disabledInKeycloak.add(u);
        }
      }
    } catch (RuntimeException e) {
      for (User u : disabledInKeycloak) {
        try {
          keycloakAdmin.updateProfile(
              u.getKeycloakId(), u.getEmail(), u.getFirstName(), u.getLastName(), true);
        } catch (RuntimeException undoError) {
          log.error(
              "No se pudo revertir la deshabilitación en Keycloak del usuario {}",
              u.getId(),
              undoError);
        }
      }
      throw e;
    }
    return toDisable.size();
  }

  // Usuarios que la deshabilitación / reactivación de la organización considera suyos: los que
  // pertenecen a ella, sin AUDITOR (auditan varias organizaciones) ni PRISMA_ADMIN (rol global).
  // El evaluador general (sin organizaciones) nunca aparece acá: no pertenece a ninguna.
  private List<User> cascadeScope(UUID organizationId) {
    return userRepo.findByOrganizationId(organizationId).stream()
        .filter(
            u ->
                !u.getRoles().contains(UserRole.AUDITOR)
                    && !u.getRoles().contains(UserRole.PRISMA_ADMIN))
        .toList();
  }

  // Un usuario con varias organizaciones solo se deshabilita cuando TODAS las suyas están
  // inactivas; mientras le quede alguna activa, conserva el acceso. `organizationId` es la que se
  // está deshabilitando ahora (se la cuenta como inactiva aunque el estado aún no esté flusheado).
  private static boolean allOrganizationsInactive(User user, UUID organizationId) {
    return user.getOrganizations().stream()
        .allMatch(o -> o.getId().equals(organizationId) || !Boolean.TRUE.equals(o.getEnabled()));
  }

  /**
   * Reactiva una organización inactiva. NO rehabilita a sus usuarios (no hay forma de distinguir a
   * los que se deshabilitaron por la cascada de los que ya lo estaban a mano): devuelve cuántos
   * siguen deshabilitados para que el administrador los habilite uno por uno desde Usuarios.
   * Idempotente: reactivar una organización que ya está activa no cambia nada.
   */
  public OrganizationReactivationDto enable(UUID id) {
    Organization org =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organización", id));
    boolean wasDisabled = !Boolean.TRUE.equals(org.getEnabled());
    org.setEnabled(true);
    OrganizationDto result = toDto(repo.save(org));
    int stillDisabled =
        (int) cascadeScope(id).stream().filter(u -> !Boolean.TRUE.equals(u.getEnabled())).count();
    if (wasDisabled) {
      auditLog.record(
          "REACTIVATE", "organization:" + id, "{\"usersStillDisabled\":" + stillDisabled + "}");
    }
    return new OrganizationReactivationDto(result, stillDisabled);
  }

  private OrganizationDto toDto(Organization o) {
    return new OrganizationDto(
        o.getId(),
        o.getName(),
        o.getIdentifier(),
        Boolean.TRUE.equals(o.getIdentifierIsRut()),
        o.getSector(),
        o.getSize(),
        o.getResponsible() != null ? o.getResponsible().getId() : null,
        responsibleName(o.getResponsible()),
        o.getEnabled(),
        o.getCreatedAt());
  }

  private static String responsibleName(User responsible) {
    if (responsible == null) {
      return null;
    }
    String first = responsible.getFirstName() != null ? responsible.getFirstName() : "";
    String last = responsible.getLastName() != null ? responsible.getLastName() : "";
    String name = (first + " " + last).trim();
    return name.isEmpty() ? responsible.getEmail() : name;
  }
}
