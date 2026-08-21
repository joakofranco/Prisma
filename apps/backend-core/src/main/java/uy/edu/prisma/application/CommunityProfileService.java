package uy.edu.prisma.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CommunityProfileRepository;
import uy.edu.prisma.web.dto.Dto.CommunityProfileDto;
import uy.edu.prisma.web.dto.Dto.CommunityProfileSummaryDto;
import uy.edu.prisma.web.dto.Dto.CreateCommunityProfileDto;

/**
 * Perfiles comunitarios: subconjuntos curados de controles del catálogo (ver comentario en {@link
 * CommunityProfile}). Es catálogo de referencia compartido entre organizaciones (como el propio
 * catálogo MCU 5.0), no dato con aislamiento por tenant: cualquier usuario autenticado puede
 * listarlos/verlos, pero solo PRISMA_ADMIN puede crearlos/editarlos/borrarlos (aplicado en el
 * controller).
 */
@Service
@Transactional
public class CommunityProfileService {

  private final CommunityProfileRepository repo;
  private final CatalogControlRepository controlRepo;

  public CommunityProfileService(
      CommunityProfileRepository repo, CatalogControlRepository controlRepo) {
    this.repo = repo;
    this.controlRepo = controlRepo;
  }

  @Transactional(readOnly = true)
  public List<CommunityProfileSummaryDto> listByVersion(String catalogVersion) {
    return repo.findByCatalogVersionOrderByNameAsc(catalogVersion).stream()
        .map(this::toSummaryDto)
        .toList();
  }

  @Transactional(readOnly = true)
  public CommunityProfileDto getById(UUID id) {
    return toDto(findOrThrow(id));
  }

  public CommunityProfileDto create(CreateCommunityProfileDto dto) {
    CommunityProfile profile =
        CommunityProfile.builder()
            .name(dto.name())
            .description(dto.description())
            .catalogVersion(dto.catalogVersion())
            .controls(resolveControls(dto.controlIds(), dto.catalogVersion()))
            .build();
    return toDto(repo.save(profile));
  }

  public CommunityProfileDto update(UUID id, CreateCommunityProfileDto dto) {
    CommunityProfile profile = findOrThrow(id);
    profile.setName(dto.name());
    profile.setDescription(dto.description());
    profile.setCatalogVersion(dto.catalogVersion());
    profile.setControls(resolveControls(dto.controlIds(), dto.catalogVersion()));
    // `profile` ya es la instancia administrada de este findById(): el dirty-checking de JPA
    // persiste los cambios al hacer commit, sin necesidad de un save() explícito (ver el mismo
    // patrón resuelto antes en EvidenceService/UserService).
    return toDto(profile);
  }

  public void delete(UUID id) {
    if (!repo.existsById(id)) {
      throw new ResourceNotFoundException("Perfil comunitario", id);
    }
    // Evaluaciones que ya usan este perfil quedan con community_profile_id NULL (ON DELETE SET
    // NULL en la FK) -- pasan a verse como "catálogo completo", no se rompen.
    repo.deleteById(id);
  }

  private CommunityProfile findOrThrow(UUID id) {
    return repo.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Perfil comunitario", id));
  }

  private Set<CatalogControl> resolveControls(Set<UUID> controlIds, String catalogVersion) {
    if (controlIds.isEmpty()) {
      throw new InvalidRequestException("El perfil debe incluir al menos un control");
    }
    List<CatalogControl> found = controlRepo.findAllById(controlIds);
    if (found.size() != controlIds.size()) {
      throw new InvalidRequestException("Uno o más de los controles indicados no existen");
    }
    for (CatalogControl control : found) {
      // El requisito ahora tiene su propia FK directa a la version del catalogo (ya no hace falta
      // pasar por una subcategoria -- puede tener varias, ver CatalogRequirementSubcategory).
      String controlVersion = control.getRequirement().getVersion().getVersion();
      if (!controlVersion.equals(catalogVersion)) {
        throw new InvalidRequestException(
            "El control "
                + control.getCode()
                + " no pertenece a la versión de catálogo "
                + catalogVersion);
      }
    }
    return new HashSet<>(found);
  }

  private CommunityProfileSummaryDto toSummaryDto(CommunityProfile p) {
    return new CommunityProfileSummaryDto(
        p.getId(), p.getName(), p.getDescription(), p.getCatalogVersion(), p.getControls().size());
  }

  private CommunityProfileDto toDto(CommunityProfile p) {
    return new CommunityProfileDto(
        p.getId(),
        p.getName(),
        p.getDescription(),
        p.getCatalogVersion(),
        p.getControls().stream().map(CatalogControl::getId).collect(Collectors.toSet()),
        p.getCreatedAt());
  }
}
