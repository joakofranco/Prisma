package uy.edu.prisma.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Dto {

  // ---- Organization ----
  public record OrganizationDto(
      UUID id,
      String name,
      // Uruguay usa "RUT" (Colombia y otros usan "NIT") -- opcional, ver
      // V17__organization_rut_optional.sql.
      String rut,
      String sector,
      String size,
      UUID responsibleId,
      Boolean enabled,
      OffsetDateTime createdAt) {}

  public record CreateOrganizationDto(
      @NotBlank(message = "El nombre es obligatorio") String name,
      String rut,
      String sector,
      String size,
      UUID responsibleId) {}

  // ---- User ----
  public record UserDto(
      UUID id,
      String email,
      String firstName,
      String lastName,
      UUID tenantId,
      // Nombre de la organización (null si no tiene tenant, p.ej. PRISMA_ADMIN) -- se resuelve acá
      // para que la tabla de usuarios del admin pueda mostrarlo sin un lookup aparte por fila.
      String organizationName,
      Set<String> roles,
      // Organizaciones que puede auditar (solo tiene sentido si roles contiene AUDITOR; ver
      // CurrentUserService.assertOrganizationAccess). Vacío para el resto de los roles.
      Set<UUID> auditedOrganizationIds,
      Boolean enabled,
      OffsetDateTime createdAt,
      // false = no tiene cuenta en Keycloak todavía y por lo tanto NO puede iniciar sesión
      // (p.ej. usuarios creados antes de que esto se corrigiera). El admin lo resuelve editando
      // el usuario y fijándole una contraseña.
      boolean canLogin) {
    public UserDto {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
      auditedOrganizationIds =
          auditedOrganizationIds == null ? Set.of() : Set.copyOf(auditedOrganizationIds);
    }

    // Compat: firma previa sin organizationName (usada en tests existentes) -- delega al
    // canónico con null (mismo criterio que "sin tenant" en general).
    public UserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        UUID tenantId,
        Set<String> roles,
        Set<UUID> auditedOrganizationIds,
        Boolean enabled,
        OffsetDateTime createdAt,
        boolean canLogin) {
      this(
          id,
          email,
          firstName,
          lastName,
          tenantId,
          null,
          roles,
          auditedOrganizationIds,
          enabled,
          createdAt,
          canLogin);
    }

    // Compat: firma previa sin organizationName NI auditedOrganizationIds (usada en tests
    // existentes) -- delega al canónico con ambos por defecto.
    public UserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        UUID tenantId,
        Set<String> roles,
        Boolean enabled,
        OffsetDateTime createdAt,
        boolean canLogin) {
      this(id, email, firstName, lastName, tenantId, null, roles, Set.of(), enabled, createdAt, canLogin);
    }

    @Override
    public Set<String> roles() {
      return Collections.unmodifiableSet(roles);
    }

    @Override
    public Set<UUID> auditedOrganizationIds() {
      return Collections.unmodifiableSet(auditedOrganizationIds);
    }
  }

  // Autoservicio: cualquier usuario autenticado corrige SU PROPIO nombre y apellido (distinto de
  // que un PRISMA_ADMIN/ORG_RESPONSIBLE edite a OTRO usuario vía CreateUserDto/UserService.update).
  // No incluye email: cambiarlo tocaría el username en Keycloak (la identidad de login), que
  // amerita su propio flujo -- acá el alcance es solo corregir un nombre/apellido mal cargado.
  public record UpdateProfileDto(
      @NotBlank(message = "El nombre es obligatorio") String firstName,
      @NotBlank(message = "El apellido es obligatorio") String lastName) {}

  // Autoservicio: cualquier usuario autenticado cambia SU PROPIA contraseña (distinto del reseteo
  // que hace un PRISMA_ADMIN sobre otro usuario vía UpdateUserDto/UserService.update, que no
  // necesita conocer la actual). Ver AccountService.changePassword -- currentPassword se valida
  // contra Keycloak antes de aceptar la nueva.
  public record ChangePasswordDto(
      @NotBlank(message = "La contraseña actual es obligatoria") String currentPassword,
      @NotBlank(message = "La contraseña nueva es obligatoria")
          @Size(min = 8, message = "La contraseña nueva debe tener al menos 8 caracteres")
          String newPassword) {}

  // El login/logout en sí los maneja Keycloak directamente (Authorization Code + PKCE, ver
  // services/auth.ts en el frontend) -- backend-core nunca ve esa request, sólo valida el JWT en
  // cada llamada posterior. Este endpoint es lo que el frontend llama a mano justo después de
  // autenticarse (o justo antes de cerrar sesión) para que quede una entrada en la bitácora; ver
  // AccountService.recordSessionEvent.
  public record SessionEventDto(
      @NotBlank(message = "El evento es obligatorio") String event) {}

  public record CreateUserDto(
      @NotBlank(message = "El email es obligatorio") String email,
      @NotBlank(message = "El nombre es obligatorio") String firstName,
      @NotBlank(message = "El apellido es obligatorio") String lastName,
      UUID tenantId,
      Set<String> roles,
      Set<UUID> auditedOrganizationIds,
      String password,
      // CREATE (UserService.create): null equivale a "true" (una cuenta nueva nace habilitada).
      // UPDATE (UserService.update): null equivale a "sin cambios" -- deshabilitar/rehabilitar es
      // opt-in, así que un cliente viejo que todavía no manda este campo (ver los constructores de
      // compat abajo) no puede pisar el estado actual sin querer.
      Boolean enabled) {
    public CreateUserDto {
      roles = roles == null ? Set.of() : Set.copyOf(roles);
      auditedOrganizationIds =
          auditedOrganizationIds == null ? Set.of() : Set.copyOf(auditedOrganizationIds);
    }

    // Compat: firma previa sin "enabled" (usada en tests existentes) -- delega al canónico con
    // null (ver el comentario en el campo "enabled" de arriba sobre qué significa ahí).
    public CreateUserDto(
        String email,
        String firstName,
        String lastName,
        UUID tenantId,
        Set<String> roles,
        Set<UUID> auditedOrganizationIds,
        String password) {
      this(email, firstName, lastName, tenantId, roles, auditedOrganizationIds, password, null);
    }

    // Compat: firma previa sin auditedOrganizationIds NI "enabled" (usada en tests existentes) --
    // delega al canónico con el set vacío y null respectivamente.
    public CreateUserDto(
        String email,
        String firstName,
        String lastName,
        UUID tenantId,
        Set<String> roles,
        String password) {
      this(email, firstName, lastName, tenantId, roles, Set.of(), password, null);
    }

    @Override
    public Set<String> roles() {
      return Collections.unmodifiableSet(roles);
    }

    @Override
    public Set<UUID> auditedOrganizationIds() {
      return Collections.unmodifiableSet(auditedOrganizationIds);
    }
  }

  // ---- Catalog ----
  public record CatalogVersionDto(String version, String label) {}

  // Los records de acá abajo (Catalog*Dto) no se usaban en ningún lado hasta que se agregó la
  // importación de catálogo (ver CatalogService.importCatalog): getByVersion() arma la respuesta
  // como Map<String,Object> a mano en vez de con estos DTOs. Se reaprovechan tal cual para el
  // body de POST /api/catalog/import -- `id` queda sin validar (se ignora al importar, un
  // catálogo nuevo todavía no tiene ids) y las anotaciones de validación no afectan ninguna otra
  // lectura existente, solo aplican cuando el record es el argumento @Valid de un @RequestBody.
  public record CatalogFunctionDto(
      UUID id,
      @NotBlank(message = "El código de la función es obligatorio") String code,
      @NotBlank(message = "El nombre de la función es obligatorio") String name,
      String description,
      @NotEmpty(message = "La función necesita al menos una categoría")
          List<@Valid CatalogCategoryDto> categories) {
    public CatalogFunctionDto {
      categories = categories == null ? List.of() : List.copyOf(categories);
    }

    @Override
    public List<CatalogCategoryDto> categories() {
      return Collections.unmodifiableList(categories);
    }
  }

  public record CatalogCategoryDto(
      UUID id,
      @NotBlank(message = "El código de la categoría es obligatorio") String code,
      @NotBlank(message = "El nombre de la categoría es obligatorio") String name,
      String description,
      @NotEmpty(message = "La categoría necesita al menos una subcategoría")
          List<@Valid CatalogSubcategoryDto> subcategories) {
    public CatalogCategoryDto {
      subcategories = subcategories == null ? List.of() : List.copyOf(subcategories);
    }

    @Override
    public List<CatalogSubcategoryDto> subcategories() {
      return Collections.unmodifiableList(subcategories);
    }
  }

  public record CatalogSubcategoryDto(
      UUID id,
      @NotBlank(message = "El código de la subcategoría es obligatorio") String code,
      @NotBlank(message = "El nombre de la subcategoría es obligatorio") String name,
      String description,
      @NotEmpty(message = "La subcategoría necesita al menos un requisito")
          List<@Valid CatalogRequirementDto> requirements) {
    public CatalogSubcategoryDto {
      requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    @Override
    public List<CatalogRequirementDto> requirements() {
      return Collections.unmodifiableList(requirements);
    }
  }

  public record CatalogRequirementDto(
      UUID id,
      @NotBlank(message = "El código del requisito es obligatorio") String code,
      @NotBlank(message = "La descripción del requisito es obligatoria") String description,
      @NotEmpty(message = "El requisito necesita al menos un control")
          List<@Valid CatalogControlDto> controls) {
    public CatalogRequirementDto {
      controls = controls == null ? List.of() : List.copyOf(controls);
    }

    @Override
    public List<CatalogControlDto> controls() {
      return Collections.unmodifiableList(controls);
    }
  }

  public record CatalogControlDto(
      UUID id,
      @NotBlank(message = "El código del control es obligatorio") String code,
      @NotBlank(message = "La descripción del control es obligatoria") String description,
      @NotNull(message = "El nivel objetivo es obligatorio")
          @Min(value = 1, message = "El nivel objetivo debe ser entre 1 y 4")
          @Max(value = 4, message = "El nivel objetivo debe ser entre 1 y 4")
          Integer targetLevel) {}

  public record CatalogImportDto(
      @NotBlank(message = "La versión es obligatoria")
          @Size(max = 20, message = "La versión no puede superar los 20 caracteres")
          String version,
      @NotBlank(message = "La etiqueta es obligatoria")
          @Size(max = 200, message = "La etiqueta no puede superar los 200 caracteres")
          String label,
      @NotEmpty(message = "El catálogo necesita al menos una función")
          List<@Valid CatalogFunctionDto> functions) {
    public CatalogImportDto {
      functions = functions == null ? List.of() : List.copyOf(functions);
    }

    @Override
    public List<CatalogFunctionDto> functions() {
      return Collections.unmodifiableList(functions);
    }
  }

  // ---- Community Profiles ----
  // Subconjunto curado de controles del catálogo para un sector/comunidad (p.ej. "Gobierno",
  // "PYME"). El summary no trae los controlIds (para no pesar en el selector de "Nueva
  // Evaluación"); el detalle sí, para la pantalla de edición.
  public record CommunityProfileSummaryDto(
      UUID id, String name, String description, String catalogVersion, int controlCount) {}

  public record CommunityProfileDto(
      UUID id,
      String name,
      String description,
      String catalogVersion,
      Set<UUID> controlIds,
      OffsetDateTime createdAt) {
    public CommunityProfileDto {
      controlIds = controlIds == null ? Set.of() : Set.copyOf(controlIds);
    }

    @Override
    public Set<UUID> controlIds() {
      return Collections.unmodifiableSet(controlIds);
    }
  }

  public record CreateCommunityProfileDto(
      @NotBlank(message = "El nombre es obligatorio") String name,
      String description,
      @NotBlank(message = "La versión del catálogo es obligatoria") String catalogVersion,
      Set<UUID> controlIds) {
    public CreateCommunityProfileDto {
      controlIds = controlIds == null ? Set.of() : Set.copyOf(controlIds);
    }

    @Override
    public Set<UUID> controlIds() {
      return Collections.unmodifiableSet(controlIds);
    }
  }

  // ---- Evaluation ----
  public record EvaluationDto(
      UUID id,
      String name,
      UUID organizationId,
      String organizationName,
      String catalogVersion,
      UUID communityProfileId,
      String communityProfileName,
      String status,
      Integer globalMaturity,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      // Nombre de quien la creó/evaluó (null si createdBy es null -- p.ej. datos históricos de
      // antes de que se empezara a registrar). Se resuelve acá para que la grilla de evaluaciones
      // del admin no necesite un lookup aparte por fila.
      String createdByName,
      // Auditores con esta organización entre sus auditedOrganizationIds (ver User.java) -- no es
      // "quién auditó esta evaluación puntual" (eso queda en AuditObservation.createdBy una vez que
      // hay observaciones), sino "quién está habilitado para auditar cualquier evaluación de esta
      // organización". Vacío si ningún AUDITOR tiene la organización asignada todavía.
      List<String> assignedAuditorNames) {
    public EvaluationDto {
      assignedAuditorNames = assignedAuditorNames == null ? List.of() : List.copyOf(assignedAuditorNames);
    }

    // Compat: firma previa sin createdByName ni assignedAuditorNames (usada en tests existentes).
    public EvaluationDto(
        UUID id,
        String name,
        UUID organizationId,
        String organizationName,
        String catalogVersion,
        UUID communityProfileId,
        String communityProfileName,
        String status,
        Integer globalMaturity,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
      this(
          id,
          name,
          organizationId,
          organizationName,
          catalogVersion,
          communityProfileId,
          communityProfileName,
          status,
          globalMaturity,
          createdBy,
          createdAt,
          updatedAt,
          null,
          List.of());
    }

    @Override
    public List<String> assignedAuditorNames() {
      return Collections.unmodifiableList(assignedAuditorNames);
    }
  }

  public record CreateEvaluationDto(
      @NotBlank(message = "El nombre es obligatorio") String name,
      UUID organizationId,
      String catalogVersion,
      UUID communityProfileId) {}

  // ---- Evaluation Response ----
  // El catalogo real modela cada control como un item de checklist (cumple / no cumple) dentro
  // de un Requisito graduado por nivel 1-4, no como una autoevaluacion de madurez 1-5 por
  // control -- ver EvaluationService.calculateMaturity para el calculo acumulativo por nivel.
  public record EvaluationResponseDto(
      UUID id,
      UUID evaluationId,
      UUID controlId,
      Boolean compliant,
      String observations,
      UUID respondedBy,
      OffsetDateTime respondedAt) {}

  public record SaveResponseDto(UUID controlId, Boolean compliant, String observations) {}

  // ---- Maturity Result ----
  public record MaturityResultDto(
      UUID functionId,
      String functionName,
      UUID categoryId,
      String categoryName,
      UUID subcategoryId,
      String subcategoryName,
      Integer currentLevel,
      Integer targetLevel,
      Integer gap) {}

  // ---- Dashboard ----
  public record DashboardStatsDto(
      long totalEvaluations,
      long activeOrganizations,
      double avgMaturityLevel,
      long pendingImprovements,
      Map<String, Long> evaluationsByStatus,
      List<MaturityByFunctionDto> maturityByFunction) {
    public DashboardStatsDto {
      evaluationsByStatus =
          evaluationsByStatus == null ? Map.of() : Map.copyOf(evaluationsByStatus);
      maturityByFunction = maturityByFunction == null ? List.of() : List.copyOf(maturityByFunction);
    }

    @Override
    public Map<String, Long> evaluationsByStatus() {
      return Collections.unmodifiableMap(evaluationsByStatus);
    }

    @Override
    public List<MaturityByFunctionDto> maturityByFunction() {
      return Collections.unmodifiableList(maturityByFunction);
    }
  }

  public record MaturityByFunctionDto(String name, int level) {}

  // ---- Evidence ----
  public record EvidenceDto(
      UUID id,
      UUID evaluationId,
      UUID controlId,
      String fileName,
      Long fileSize,
      String fileType,
      String description,
      UUID uploadedBy,
      OffsetDateTime uploadedAt,
      String url,
      boolean aiIndexed) {}

  public record UploadEvidenceResponseDto(UUID id, String fileName, Long fileSize) {}

  public record DownloadUrlDto(String url) {}

  // ---- Evidence RAG (citas para el auditor) ----
  // Recuperación pura: ningún campo acá representa un veredicto. location es "Página N"
  // (PDF) o "Fragmento N" (DOCX/TXT) según lo que haya podido extraer backend-ai.
  public record EvidenceCitationDto(
      UUID evidenceId, String fileName, String location, String snippet, double score) {}

  // ---- Audit Observations ----
  public record AuditObservationDto(
      UUID id,
      UUID evaluationId,
      UUID controlId,
      String type,
      String description,
      String status,
      UUID createdBy,
      OffsetDateTime createdAt,
      // Nombre del auditor que la registró -- es la "estampa" de quién trabajó esta auditoría:
      // null si createdBy es null (dato histórico) o no se pudo resolver.
      String createdByName) {
    // Compat: firma previa sin createdByName (usada en tests existentes).
    public AuditObservationDto(
        UUID id,
        UUID evaluationId,
        UUID controlId,
        String type,
        String description,
        String status,
        UUID createdBy,
        OffsetDateTime createdAt) {
      this(id, evaluationId, controlId, type, description, status, createdBy, createdAt, null);
    }
  }

  public record CreateAuditObservationDto(
      UUID evaluationId,
      UUID controlId,
      String type,
      @NotBlank(message = "La descripción es obligatoria") String description,
      String status) {}

  public record UpdateAuditObservationDto(
      UUID controlId, String type, String description, String status) {}

  // ---- Improvement Plans ----
  public record ImprovementPlanDto(
      UUID id,
      UUID evaluationId,
      UUID controlId,
      String action,
      String responsible,
      String priority,
      String status,
      String dueDate,
      Long dueInDays,
      OffsetDateTime createdAt) {}

  public record CreateImprovementPlanDto(
      UUID evaluationId,
      UUID controlId,
      @NotBlank(message = "La acción es obligatoria") String action,
      String responsible,
      String priority,
      String dueDate) {}

  public record UpdateImprovementPlanDto(
      String action, String responsible, String priority, String dueDate, String status) {}

  // Sugerencia generada por reglas (RF-PLN-01), todavia no persistida como ImprovementPlan:
  // el usuario la revisa/edita en el frontend y recien ahi se crea via POST /api/improvement.
  public record SuggestedImprovementDto(
      UUID controlId,
      UUID subcategoryId,
      String functionName,
      String categoryName,
      String subcategoryName,
      String controlCode,
      String action,
      int currentLevel,
      int targetLevel,
      String priority,
      String suggestedDueDate) {}

  // Pedido on-demand (un click por brecha, no se genera para toda la lista de sugerencias de
  // una) de una guía práctica para cerrarla -- ver ImprovementPlanController/AiEvidenceClient.
  // No lleva evaluationId/organizationId: es puramente contexto de catálogo (control + ubicación
  // + niveles), que el frontend ya tiene de la sugerencia sin necesitar volver a resolverlo acá.
  public record RemediationTipsRequestDto(
      @NotBlank(message = "El código del control es obligatorio") String controlCode,
      @NotBlank(message = "La descripción del control es obligatoria") String description,
      String functionName,
      String categoryName,
      String subcategoryName,
      @Min(0) @Max(4) int currentLevel,
      @Min(0) @Max(4) int targetLevel) {}

  public record RemediationTipsDto(String summary, List<String> tips) {}

  // ---- Configuración de email (SMTP del realm de Keycloak) ----
  // Sin esta configuración, "¿Olvidaste tu contraseña?" en el login de Keycloak no envía nada
  // (falla en silencio del lado de Keycloak) -- ver EmailSettingsService.
  public record EmailSettingsDto(
      String host,
      Integer port,
      String from,
      String fromDisplayName,
      boolean authEnabled,
      String username,
      boolean starttls,
      boolean ssl,
      // true si ya hay al menos host+from guardados -- la pantalla de administración lo usa para
      // distinguir "todavía no se configuró nada" de "hay una configuración guardada".
      boolean configured) {}

  public record UpdateEmailSettingsDto(
      @NotBlank(message = "El servidor SMTP es obligatorio") String host,
      @NotNull(message = "El puerto es obligatorio")
          @Min(value = 1, message = "Puerto inválido")
          @Max(value = 65535, message = "Puerto inválido")
          Integer port,
      @NotBlank(message = "La dirección de remitente es obligatoria") String from,
      String fromDisplayName,
      boolean authEnabled,
      String username,
      // Keycloak enmascara el password guardado como "**********" al leerlo (no hay forma de
      // recuperar el valor real) -- por eso SIEMPRE hay que reingresarlo acá para guardar
      // cualquier cambio, incluso si sólo se está tocando otro campo. Obligatorio si
      // authEnabled=true (validado en EmailSettingsService, no acá: depende de otro campo del
      // mismo DTO).
      String password,
      boolean starttls,
      boolean ssl) {}

  // ---- Audit log (bitácora de acciones del sistema, distinta de AuditObservation/"Auditoría"
  // MCU) ----
  public record AuditLogDto(
      UUID id,
      UUID userId,
      // Email/nombre resueltos por AuditLogService desde userId -- null si el usuario ya no
      // existe (borrado después de haber quedado registrado acá; el log es append-only y no se
      // toca) o si la acción no tuvo un usuario autenticado detrás.
      String userEmail,
      String userFullName,
      UUID tenantId,
      // Idem: null si la organización ya no existe, o si la acción no tiene tenant (p.ej. la
      // ejecutó un PRISMA_ADMIN sobre algo global).
      String tenantName,
      String action,
      // "tipo:id" crudo (p.ej. "user:38dab301-..."), tal cual lo graba AuditLogService.record --
      // se mantiene para trazabilidad exacta (tooltip en el frontend).
      String resource,
      // Nombre real de la entidad referenciada por "resource" (p.ej. "Jane Doe" para un
      // "user:<id>"), resuelto por AuditLogService -- null si el tipo no tiene una entidad propia
      // (p.ej. "email-settings") o si ya no existe (borrada después de quedar en la bitácora).
      String resourceName,
      String payload,
      String ipAddress,
      String userAgent,
      OffsetDateTime createdAt) {}

  // ---- API error ----
  public record ApiErrorDto(String message, String code, Map<String, List<String>> details) {
    public ApiErrorDto {
      details = details == null ? Map.of() : Map.copyOf(details);
    }
  }

  // ---- Paginated ----
  public record PaginatedDto<T>(List<T> data, long total, int page, int pageSize) {
    public PaginatedDto {
      data = data == null ? List.of() : List.copyOf(data);
    }

    @Override
    public List<T> data() {
      return Collections.unmodifiableList(data);
    }
  }
}
