package uy.edu.prisma.application;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.web.dto.Dto.CreateOrganizationDto;
import uy.edu.prisma.web.dto.Dto.OrganizationDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;

@Service
@Transactional
public class OrganizationService {

  private final OrganizationRepository repo;
  private final AuditLogService auditLog;
  private final CurrentUserService currentUser;

  public OrganizationService(
      OrganizationRepository repo, AuditLogService auditLog, CurrentUserService currentUser) {
    this.repo = repo;
    this.auditLog = auditLog;
    this.currentUser = currentUser;
  }

  // Aislamiento multi-tenant (ver CurrentUserService.assertOrganizationAccess, que sigue el mismo
  // criterio para operaciones puntuales): PRISMA_ADMIN ve todas; AUDITOR ve solo las que audita;
  // el resto (ORG_RESPONSIBLE/INTERNAL_EVALUATOR/VIEWER) solo la propia. Antes esto devolvía TODAS
  // las organizaciones sin importar el rol -- cualquier usuario autenticado podía enumerar la
  // plataforma entera (nombre, RUT, sector) de organizaciones ajenas.
  @Transactional(readOnly = true)
  public PaginatedDto<OrganizationDto> list(String search, int page, int pageSize) {
    PageRequest pageable = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    Page<Organization> result;
    if (currentUser.isPrismaAdmin()) {
      result = repo.search(search, pageable);
    } else {
      Set<UUID> allowedIds =
          currentUser.isAuditor()
              ? currentUser.auditedOrganizationIds()
              : optionalSet(currentUser.currentTenantId());
      result =
          allowedIds.isEmpty()
              ? Page.empty(pageable)
              : repo.searchByIdIn(allowedIds, search, pageable);
    }
    return new PaginatedDto<>(
        result.getContent().stream().map(this::toDto).toList(),
        result.getTotalElements(),
        page,
        pageSize);
  }

  private static Set<UUID> optionalSet(UUID id) {
    return id == null ? Set.of() : Set.of(id);
  }

  @Transactional(readOnly = true)
  public OrganizationDto getById(UUID id) {
    currentUser.assertOrganizationAccess(id);
    Organization org =
        repo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organización", id));
    return toDto(org);
  }

  public OrganizationDto create(CreateOrganizationDto dto) {
    Organization org =
        Organization.builder()
            .name(dto.name())
            .rut(blankToNull(dto.rut()))
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
    org.setName(dto.name());
    org.setRut(blankToNull(dto.rut()));
    org.setSector(dto.sector());
    org.setSize(dto.size());
    OrganizationDto result = toDto(repo.save(org));
    auditLog.record("UPDATE", "organization:" + id, "{\"name\":\"" + result.name() + "\"}");
    return result;
  }

  // Un RUT en blanco ("") debe guardarse como NULL, no como cadena vacía: la UNIQUE constraint de
  // Postgres SÍ los distingue -- NULL no cuenta contra ella (varias organizaciones sin RUT
  // conviven bien), pero "" sí es un valor como cualquier otro, así que la SEGUNDA organización
  // creada sin RUT rompería el alta con una violación de unicidad si no se normalizara acá.
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
    auditLog.record("DELETE", "organization:" + id, null);
  }

  private OrganizationDto toDto(Organization o) {
    return new OrganizationDto(
        o.getId(),
        o.getName(),
        o.getRut(),
        o.getSector(),
        o.getSize(),
        o.getResponsible() != null ? o.getResponsible().getId() : null,
        o.getEnabled(),
        o.getCreatedAt());
  }
}
