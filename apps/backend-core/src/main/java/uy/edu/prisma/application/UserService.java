package uy.edu.prisma.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.config.PasswordHasher;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.CreateUserDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;
import uy.edu.prisma.web.dto.Dto.UserDto;

@Service
@Transactional
public class UserService {

  private final UserRepository userRepo;
  private final OrganizationRepository orgRepo;
  private final PasswordHasher passwordHasher;
  private final AuditLogService auditLog;
  private final KeycloakAdminClient keycloakAdmin;
  private final CurrentUserService currentUser;

  public UserService(
      UserRepository userRepo,
      OrganizationRepository orgRepo,
      PasswordHasher passwordHasher,
      AuditLogService auditLog,
      KeycloakAdminClient keycloakAdmin,
      CurrentUserService currentUser) {
    this.userRepo = userRepo;
    this.orgRepo = orgRepo;
    this.passwordHasher = passwordHasher;
    this.auditLog = auditLog;
    this.keycloakAdmin = keycloakAdmin;
    this.currentUser = currentUser;
  }

  // Aislamiento multi-tenant, mismo criterio que OrganizationService.list: PRISMA_ADMIN ve todos
  // los usuarios; AUDITOR ve los de las organizaciones que audita; el resto solo los de su propia
  // organización. Antes devolvía TODOS los usuarios de la plataforma sin importar el rol --
  // cualquier usuario autenticado podía enumerar emails, roles y organizaciones ajenas.
  @Transactional(readOnly = true)
  public PaginatedDto<UserDto> list(String search, int page, int pageSize) {
    PageRequest pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    Page<User> result;
    if (currentUser.isPrismaAdmin()) {
      result = userRepo.search(search, pageable);
    } else {
      Set<UUID> allowedOrgIds = currentUser.organizationIds();
      result =
          allowedOrgIds.isEmpty()
              ? Page.empty(pageable)
              : userRepo.searchByOrganizationIdIn(allowedOrgIds, search, pageable);
    }
    return new PaginatedDto<>(
        result.getContent().stream().map(this::toDto).toList(),
        result.getTotalElements(),
        page,
        pageSize);
  }

  @Transactional(readOnly = true)
  public UserDto getById(UUID id) {
    User user =
        userRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
    assertUserAccess(user);
    return toDto(user);
  }

  // Un usuario siempre puede ver su propia ficha (p.ej. "Mi Cuenta"); fuera de eso, mismo criterio
  // que CurrentUserService.assertOrganizationAccess pero contra la organización del usuario
  // consultado en vez de una organización pasada como parámetro.
  private void assertUserAccess(User target) {
    if (currentUser.isPrismaAdmin()) {
      return;
    }
    UUID myId = currentUser.currentUserId();
    if (myId != null && myId.equals(target.getId())) {
      return;
    }
    // Alcanza con que compartan alguna organización.
    Set<UUID> mine = currentUser.organizationIds();
    if (target.organizationIds().stream().noneMatch(mine::contains)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "No tiene acceso a los datos de este usuario");
    }
  }

  // Para MODIFICAR o borrar a otro usuario (no solo verlo) un ORG_RESPONSIBLE necesita que TODAS las
  // organizaciones del objetivo sean suyas: si no, podría pisar o eliminar a alguien que también
  // trabaja en otra organización (p.ej. un evaluador de varias). Esos los gestiona PRISMA_ADMIN.
  private void assertCanManage(User target) {
    Set<UUID> theirs = target.organizationIds();
    if (theirs.isEmpty() || !currentUser.organizationIds().containsAll(theirs)) {
      throw new org.springframework.security.access.AccessDeniedException(
          "No tiene acceso a los datos de esta organizacion");
    }
  }

  public UserDto create(CreateUserDto dto) {
    if (userRepo.existsByEmail(dto.email())) {
      throw new ConflictException("Ya existe un usuario con ese email: " + dto.email());
    }
    if (dto.password() == null || dto.password().isBlank()) {
      // Sin contraseña no hay forma de provisionar la cuenta en Keycloak, y sin cuenta en
      // Keycloak el usuario nunca va a poder loguearse -- exactamente el bug que esto corrige.
      throw new InvalidRequestException(
          "La contraseña es obligatoria: sin ella el usuario no puede iniciar sesión");
    }
    // Un ORG_RESPONSIBLE (administrador de su propia organización) también puede dar de alta
    // usuarios desde acá, pero solo dentro de SU organización: se fuerza la propia y se rechaza
    // cualquier otra que venga en el DTO -- nunca se confía en el cliente para un campo con
    // implicancias de privilegio. Por lo mismo no puede crear un evaluador "general" (sin
    // organizaciones): ese alcance global lo asigna solo PRISMA_ADMIN, que puede elegir cualquier
    // conjunto de organizaciones (o ninguna, p.ej. para otro PRISMA_ADMIN).
    Set<UUID> organizationIds;
    if (currentUser.isPrismaAdmin()) {
      organizationIds = dto.organizationIds() == null ? Set.of() : dto.organizationIds();
    } else {
      UUID myOrganizationId = currentUser.currentTenantId();
      currentUser.assertOrganizationAccess(myOrganizationId);
      assertRolesAssignableByOrgAdmin(dto.roles());
      assertOrganizationsWithinOwn(dto.organizationIds(), myOrganizationId);
      organizationIds = Set.of(myOrganizationId);
    }
    User user =
        User.builder()
            .email(dto.email())
            .firstName(dto.firstName())
            .lastName(dto.lastName())
            .enabled(dto.enabled() != null ? dto.enabled() : true)
            .roles(parseRoles(dto.roles()))
            .organizations(resolveOrganizations(organizationIds))
            .build();
    user.setPasswordHash(passwordHasher.hash(dto.password()));
    assertOrganizationsActive(user.getOrganizations());
    validateOrganizationsForRoles(user);

    User saved = userRepo.save(user);
    // Se provisiona en Keycloak DESPUÉS del insert local: si esto lanza, la anotación
    // @Transactional de la clase hace rollback del insert también -- nunca debe quedar una fila
    // en prisma.users sin la cuenta de Keycloak que le permite loguearse. `saved` ya es la
    // instancia administrada por esta transacción: alcanza con mutarla (sin volver a llamar a
    // save(), ver el mismo problema ya resuelto en EvidenceService) para que el dirty-checking
    // de JPA persista el keycloakId al hacer commit.
    UUID keycloakId =
        keycloakAdmin.createUser(
            dto.email(),
            dto.firstName(),
            dto.lastName(),
            dto.password(),
            toRoleNames(saved.getRoles()));
    saved.setKeycloakId(keycloakId);
    // createUser() en Keycloak siempre nace habilitado (no tiene forma de pedirlo deshabilitado
    // de entrada) -- si se pidió dado de alta ya deshabilitado (caso raro, pero posible desde el
    // checkbox del formulario), un segundo llamado lo sincroniza antes de devolver la respuesta.
    if (Boolean.FALSE.equals(dto.enabled())) {
      keycloakAdmin.updateProfile(keycloakId, dto.email(), dto.firstName(), dto.lastName(), false);
    }

    UserDto result = toDto(saved);
    auditLog.record("CREATE", "user:" + result.id(), "{\"email\":\"" + result.email() + "\"}");
    return result;
  }

  public UserDto update(UUID id, CreateUserDto dto) {
    User user =
        userRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
    // Mismo criterio que delete(): deshabilitarse a uno mismo deja fuera del sistema en el acto,
    // sin nadie que pueda revertirlo desde la app (ni siquiera volviendo a loguearse); y
    // deshabilitar al administrador global sembrado podría dejar la plataforma sin ningún
    // PRISMA_ADMIN utilizable si era el único habilitado.
    if (Boolean.FALSE.equals(dto.enabled())) {
      if (id.equals(currentUser.currentUserId())) {
        throw new InvalidRequestException("No podés deshabilitar tu propia cuenta");
      }
      if (SYSTEM_ADMIN_ID.equals(id)) {
        throw new InvalidRequestException(
            "No se puede deshabilitar al administrador global del sistema");
      }
    }
    // Mismo criterio que create(): un ORG_RESPONSIBLE solo puede editar usuarios cuyas
    // organizaciones sean todas suyas, no puede reubicarlos en otra, y no puede otorgar (ni quitar
    // de sí mismo, aunque no debería tenerlo) el rol global PRISMA_ADMIN.
    if (!currentUser.isPrismaAdmin()) {
      assertCanManage(user);
      assertRolesAssignableByOrgAdmin(dto.roles());
      assertOrganizationsWithinOwn(dto.organizationIds(), currentUser.currentTenantId());
    }
    // El email también es el username en Keycloak (ver KeycloakAdminClient.createUser): antes
    // acá nunca se aplicaba dto.email() al usuario, así que el campo Email del panel de edición
    // era puramente cosmético -- el PUT devolvía 200 pero el email nunca cambiaba ni en Postgres
    // ni en Keycloak (keycloakAdmin.updateProfile() más abajo terminaba reenviando el email
    // VIEJO, porque user.getEmail() todavía no se había tocado).
    if (dto.email() != null && !dto.email().equalsIgnoreCase(user.getEmail())) {
      if (userRepo.existsByEmail(dto.email())) {
        throw new ConflictException("Ya existe un usuario con ese email: " + dto.email());
      }
      user.setEmail(dto.email());
    }
    user.setFirstName(dto.firstName());
    user.setLastName(dto.lastName());
    if (dto.enabled() != null) {
      user.setEnabled(dto.enabled());
    }
    if (dto.roles() != null) {
      user.setRoles(parseRoles(dto.roles()));
    }
    // Solo PRISMA_ADMIN cambia las organizaciones de un usuario (un ORG_RESPONSIBLE no puede
    // moverlo ni quitarle las que tiene en otras organizaciones). null = sin cambios; un set vacío
    // = quitarle todas ("sin organización").
    if (currentUser.isPrismaAdmin() && dto.organizationIds() != null) {
      Set<Organization> requested = resolveOrganizations(dto.organizationIds());
      // Las que el usuario YA tenía no se vuelven a chequear (puede seguir en una organización que
      // después quedó inactiva); solo se rechaza SUMARLE una inactiva.
      Set<UUID> alreadyThere = user.organizationIds();
      assertOrganizationsActive(
          requested.stream().filter(o -> !alreadyThere.contains(o.getId())).toList());
      user.setOrganizations(requested);
    }
    validateOrganizationsForRoles(user);
    boolean passwordProvided = dto.password() != null && !dto.password().isBlank();
    if (passwordProvided) {
      user.setPasswordHash(passwordHasher.hash(dto.password()));
    }

    if (user.getKeycloakId() != null) {
      keycloakAdmin.updateProfile(
          user.getKeycloakId(),
          user.getEmail(),
          user.getFirstName(),
          user.getLastName(),
          user.getEnabled());
      keycloakAdmin.assignRealmRoles(user.getKeycloakId(), toRoleNames(user.getRoles()));
      if (passwordProvided) {
        // Permanente, no resetPassword() (que deja temporary=true): esto es un admin
        // restableciéndole la clave a un usuario YA activo desde el panel, no el alta inicial de
        // una cuenta nueva. Con temporary=true, Keycloak ignora la clave que el admin acaba de
        // fijar en el próximo login e igual lo obliga a elegir otra en una pantalla de Keycloak
        // aparte -- para quien prueba el restablecimiento (loguearse con la clave que el admin
        // fijó) esto se ve exactamente como "no funciona", porque esa clave nunca llega a ser la
        // que de verdad habilita el acceso.
        keycloakAdmin.setPermanentPassword(user.getKeycloakId(), dto.password());
      }
    } else if (passwordProvided) {
      // Auto-sanación: usuarios creados antes de este fix (o cuya creación en Keycloak falló en
      // su momento) quedan con keycloakId null -- si el admin ahora les fija una contraseña acá,
      // se aprovecha para provisionarlos en Keycloak recién en este momento.
      UUID keycloakId =
          keycloakAdmin.createUser(
              user.getEmail(),
              user.getFirstName(),
              user.getLastName(),
              dto.password(),
              toRoleNames(user.getRoles()));
      user.setKeycloakId(keycloakId);
    }
    // Si no tiene keycloakId y no vino contraseña, no hay nada para provisionar todavía: el
    // usuario sigue sin poder loguearse hasta que se le fije una contraseña.

    // `user` ya es la instancia administrada de este findById(): no hace falta un save()
    // explícito, el dirty-checking de JPA persiste los cambios al hacer commit.
    UserDto result = toDto(user);
    auditLog.record("UPDATE", "user:" + id, null);
    return result;
  }

  // Id fija del usuario semilla (ver V4__seed_admin_user.sql) -- es el administrador global del
  // sistema, no un PRISMA_ADMIN cualquiera: sin esta cuenta no queda nadie que pueda dar de alta
  // organizaciones ni el resto de los usuarios desde cero, así que no se permite borrarla sin
  // importar quién lo pida.
  private static final UUID SYSTEM_ADMIN_ID =
      UUID.fromString("b0000000-0000-0000-0000-000000000001");

  public void delete(UUID id) {
    User user =
        userRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario", id));
    // Antes no se chequeaba ninguna de las dos cosas: un PRISMA_ADMIN podía borrarse a sí mismo
    // (quedando deslogueado en el acto, sin forma de deshacerlo desde la app) o borrar al
    // administrador global sembrado, dejando la plataforma sin ningún PRISMA_ADMIN utilizable si
    // era el único que quedaba.
    if (id.equals(currentUser.currentUserId())) {
      throw new InvalidRequestException("No podés eliminar tu propia cuenta");
    }
    if (SYSTEM_ADMIN_ID.equals(id)) {
      throw new InvalidRequestException("No se puede eliminar al administrador global del sistema");
    }
    // Un ORG_RESPONSIBLE solo puede borrar usuarios de su propia organización -- sin este chequeo
    // alcanzaría con conocer/adivinar el id de un usuario de OTRA organización para borrarlo,
    // aunque nunca hubiera podido verlo ni gestionarlo por ningún otro camino de la app.
    if (!currentUser.isPrismaAdmin()) {
      assertCanManage(user);
    }
    // Se chequea ANTES de tocar Keycloak: si la fila tiene datos que dependen de ella (FK sin ON
    // DELETE) la base rechaza el borrado, pero para entonces la cuenta de Keycloak ya estaría
    // eliminada (y el rollback de la transacción no la restaura) -- el usuario quedaría visible en
    // la lista pero sin poder iniciar sesión nunca más. Además, borrarlo perdería el historial de
    // quién hizo qué: lo correcto en ese caso es deshabilitarlo.
    List<String> dependents = dependentData(id);
    if (!dependents.isEmpty()) {
      throw new ConflictException(
          "No se puede eliminar el usuario porque tiene datos asociados ("
              + String.join(", ", dependents)
              + "). Deshabilítelo en su lugar para conservar el historial.");
    }
    if (user.getKeycloakId() != null) {
      keycloakAdmin.deleteUser(user.getKeycloakId()); // best-effort, ver KeycloakAdminClient
    }
    userRepo.delete(user);
    auditLog.record("DELETE", "user:" + id, null);
  }

  private UserDto toDto(User u) {
    return new UserDto(
        u.getId(),
        u.getEmail(),
        u.getFirstName(),
        u.getLastName(),
        u.organizationIds(),
        u.getOrganizations().stream().map(Organization::getName).sorted().toList(),
        u.getRoles().stream().map(UserRole::name).collect(Collectors.toSet()),
        u.getEnabled(),
        u.getCreatedAt(),
        u.getKeycloakId() != null);
  }

  private Set<String> toRoleNames(Set<UserRole> roles) {
    return roles.stream().map(UserRole::name).collect(Collectors.toSet());
  }

  private Set<UserRole> parseRoles(Set<String> roles) {
    if (roles == null) return new HashSet<>();
    return roles.stream().map(r -> UserRole.valueOf(r)).collect(Collectors.toSet());
  }

  private Set<Organization> resolveOrganizations(Set<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return new HashSet<>();
    }
    // Igual que con tenantId más arriba: un id que no resuelve a ninguna organización real se
    // ignora en silencio en vez de romper la operación completa (findAllById simplemente no lo
    // trae).
    return new HashSet<>(orgRepo.findAllById(ids));
  }

  // Ver el comentario en create(): un administrador de organización (ORG_RESPONSIBLE) no puede
  // otorgarle el rol global PRISMA_ADMIN a nadie -- ese rol se reserva para quien ya lo tiene y
  // solo se asigna desde una cuenta PRISMA_ADMIN existente, nunca de forma auto-otorgada.
  private void assertRolesAssignableByOrgAdmin(Set<String> roles) {
    if (roles != null && roles.contains(UserRole.PRISMA_ADMIN.name())) {
      throw new org.springframework.security.access.AccessDeniedException(
          "No podés asignar el rol de administrador de la plataforma");
    }
  }

  // Un administrador de organización solo puede asignar SU PROPIA organización -- de lo contrario
  // podría dar acceso a una organización ajena, la misma fuga que
  // CurrentUserService.assertOrganizationAccess evita en el resto de la app. null / vacío = no pide
  // ninguna distinta (se usa la propia).
  private void assertOrganizationsWithinOwn(Set<UUID> requested, UUID ownOrganizationId) {
    if (requested == null || requested.isEmpty()) {
      return;
    }
    if (!requested.stream().allMatch(id -> id.equals(ownOrganizationId))) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Solo podés asignar tu propia organización");
    }
  }

  // No se puede asignar a un usuario una organización inactiva: ahí no hay actividad nueva (ver
  // OrganizationService.delete, que además deshabilita a sus usuarios).
  private void assertOrganizationsActive(java.util.Collection<Organization> organizations) {
    for (Organization o : organizations) {
      if (!Boolean.TRUE.equals(o.getEnabled())) {
        throw new InvalidRequestException(
            "No se puede asignar la organización «" + o.getName() + "» porque está inactiva");
      }
    }
  }

  // Cuántas organizaciones corresponden según los roles (la membresía es la misma M:N para todos,
  // lo que cambia es cuántas se exigen):
  //   * ORG_RESPONSIBLE / VIEWER: exactamente una.
  //   * AUDITOR: al menos una -- sin ninguna auditaría "nada" (ver CurrentUserService).
  //   * INTERNAL_EVALUATOR: cualquier cantidad; SIN ninguna es un evaluador "general" que accede a
  //     todas las organizaciones (CurrentUserService.isGeneralEvaluator).
  // PRISMA_ADMIN es global: sus otros roles son irrelevantes y no se le exige nada.
  private void validateOrganizationsForRoles(User user) {
    Set<UserRole> roles = user.getRoles();
    if (roles.contains(UserRole.PRISMA_ADMIN)) {
      return;
    }
    int count = user.getOrganizations().size();
    if ((roles.contains(UserRole.ORG_RESPONSIBLE) || roles.contains(UserRole.VIEWER))
        && count != 1) {
      throw new InvalidRequestException(
          "El responsable de organización y el visualizador deben pertenecer a exactamente una organización");
    }
    if (roles.contains(UserRole.AUDITOR) && count == 0) {
      throw new InvalidRequestException(
          "El auditor debe tener asignada al menos una organización para auditar");
    }
  }
}
