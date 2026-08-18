package uy.edu.prisma.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.web.dto.Dto.CatalogCategoryDto;
import uy.edu.prisma.web.dto.Dto.CatalogControlDto;
import uy.edu.prisma.web.dto.Dto.CatalogFunctionDto;
import uy.edu.prisma.web.dto.Dto.CatalogImportDto;
import uy.edu.prisma.web.dto.Dto.CatalogRequirementDto;
import uy.edu.prisma.web.dto.Dto.CatalogSubcategoryDto;
import uy.edu.prisma.web.dto.Dto.CatalogVersionDto;

@Service
@Transactional
public class CatalogService {

  private final CatalogVersionRepository versionRepo;
  private final CatalogFunctionRepository functionRepo;
  private final CommunityProfileRepository profileRepo;
  private final EvaluationRepository evalRepo;
  private final AuditLogService auditLog;

  public CatalogService(
      CatalogVersionRepository versionRepo,
      CatalogFunctionRepository functionRepo,
      CommunityProfileRepository profileRepo,
      EvaluationRepository evalRepo,
      AuditLogService auditLog) {
    this.versionRepo = versionRepo;
    this.functionRepo = functionRepo;
    this.profileRepo = profileRepo;
    this.evalRepo = evalRepo;
    this.auditLog = auditLog;
  }

  @Transactional(readOnly = true)
  public List<CatalogVersionDto> listVersions() {
    return versionRepo.findAll().stream()
        .map(v -> new CatalogVersionDto(v.getVersion(), v.getLabel()))
        .toList();
  }

  /**
   * Da de alta una versión de catálogo completa (funciones → categorías → subcategorías →
   * requisitos → controles) en una sola operación -- ver POST /api/catalog/import. El origen del
   * árbol (JSON subido, CSV parseado o armado a mano en el formulario) es indistinto acá: el
   * frontend siempre lo normaliza a este mismo {@link CatalogImportDto} antes de enviarlo, así que
   * este método no necesita saber de dónde vino.
   *
   * <p>El {@code sortOrder} de cada nivel es simplemente el orden de llegada en las listas del DTO
   * (no se pide explícito): quien arma el import controla el orden ordenando las listas.
   */
  public CatalogVersionDto importCatalog(CatalogImportDto dto) {
    if (versionRepo.findByVersion(dto.version()).isPresent()) {
      throw new ConflictException("Ya existe una versión de catálogo \"" + dto.version() + "\"");
    }

    CatalogVersion catalogVersion =
        CatalogVersion.builder().version(dto.version()).label(dto.label()).active(true).build();

    // Un mismo codigo de Requisito puede repetirse en el DTO bajo mas de una Subcategoria (el
    // usuario repite la fila del CSV/el bloque del JSON con otro subcategory_code) -- en ese caso
    // NO se crea un CatalogRequirement nuevo, se reusa el ya creado y solo se agrega la asociacion
    // a la Subcategoria actual (ver CatalogRequirementSubcategory). El scope de este mapa es todo
    // el import (una version de catalogo completa), asi que el codigo termina siendo unico por
    // version, no por subcategoria.
    Map<String, CatalogRequirement> requirementsByCode = new HashMap<>();

    int fIdx = 0;
    for (CatalogFunctionDto fDto : dto.functions()) {
      CatalogFunction function =
          CatalogFunction.builder()
              .version(catalogVersion)
              .code(fDto.code())
              .name(fDto.name())
              .description(fDto.description())
              .sortOrder(fIdx++)
              .build();
      catalogVersion.getFunctions().add(function);

      int cIdx = 0;
      for (CatalogCategoryDto cDto : fDto.categories()) {
        CatalogCategory category =
            CatalogCategory.builder()
                .function_(function)
                .code(cDto.code())
                .name(cDto.name())
                .description(cDto.description())
                .sortOrder(cIdx++)
                .build();
        function.getCategories().add(category);

        int sIdx = 0;
        for (CatalogSubcategoryDto sDto : cDto.subcategories()) {
          CatalogSubcategory subcategory =
              CatalogSubcategory.builder()
                  .category(category)
                  .code(sDto.code())
                  .name(sDto.name())
                  .description(sDto.description())
                  .sortOrder(sIdx++)
                  .build();
          category.getSubcategories().add(subcategory);

          int rIdx = 0;
          for (CatalogRequirementDto rDto : sDto.requirements()) {
            CatalogRequirement requirement = requirementsByCode.get(rDto.code());
            if (requirement == null) {
              requirement =
                  CatalogRequirement.builder()
                      .version(catalogVersion)
                      .code(rDto.code())
                      .description(rDto.description())
                      .sortOrder(rIdx)
                      .build();
              requirementsByCode.put(rDto.code(), requirement);

              int ctIdx = 0;
              for (CatalogControlDto ctDto : rDto.controls()) {
                CatalogControl control =
                    CatalogControl.builder()
                        .requirement(requirement)
                        .code(ctDto.code())
                        .description(ctDto.description())
                        .targetLevel(ctDto.targetLevel())
                        .sortOrder(ctIdx++)
                        .build();
                requirement.getControls().add(control);
              }
            } else {
              // Requisito ya creado bajo otra Subcategoria: no se duplica, solo se completan
              // controles nuevos (por codigo) que esta fila traiga y el Requisito no tuviera --
              // los que ya existen se ignoran para no duplicarlos.
              Set<String> existingCodes =
                  requirement.getControls().stream()
                      .map(CatalogControl::getCode)
                      .collect(Collectors.toSet());
              int ctIdx = requirement.getControls().size();
              for (CatalogControlDto ctDto : rDto.controls()) {
                if (existingCodes.contains(ctDto.code())) {
                  continue;
                }
                CatalogControl control =
                    CatalogControl.builder()
                        .requirement(requirement)
                        .code(ctDto.code())
                        .description(ctDto.description())
                        .targetLevel(ctDto.targetLevel())
                        .sortOrder(ctIdx++)
                        .build();
                requirement.getControls().add(control);
              }
            }

            CatalogRequirementSubcategory link =
                CatalogRequirementSubcategory.builder()
                    .requirement(requirement)
                    .subcategory(subcategory)
                    .sortOrder(rIdx++)
                    .build();
            subcategory.getRequirementLinks().add(link);
            requirement.getSubcategoryLinks().add(link);
          }
        }
      }
    }

    // cascade=ALL en CatalogVersion.functions (y en cada nivel siguiente) persiste todo el árbol
    // con este único save().
    CatalogVersion saved = versionRepo.save(catalogVersion);
    auditLog.record(
        "CREATE",
        "catalog_version:" + saved.getId(),
        "{\"version\":\"" + saved.getVersion() + "\"}");
    return new CatalogVersionDto(saved.getVersion(), saved.getLabel());
  }

  /**
   * Borra una versión de catálogo completa (cascade=ALL se encarga de funciones/categorías/
   * subcategorías/requisitos/controles). Rechazada si alguna evaluación ya la referencia -- ver el
   * comentario en EvaluationRepository.existsByCatalogVersion: no hay FK que lo impida solo, así
   * que se valida a mano para no dejar evaluaciones sin catálogo detrás.
   */
  public void deleteVersion(String version) {
    CatalogVersion cv =
        versionRepo
            .findByVersion(version)
            .orElseThrow(() -> new ResourceNotFoundException("Versión de catálogo", version));
    if (evalRepo.existsByCatalogVersion(version)) {
      throw new InvalidRequestException(
          "No se puede eliminar: hay evaluaciones creadas sobre la versión \"" + version + "\"");
    }
    versionRepo.delete(cv);
    auditLog.record("DELETE", "catalog_version:" + cv.getId(), "{\"version\":\"" + version + "\"}");
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getByVersion(String version) {
    return getByVersion(version, null);
  }

  /**
   * Igual que {@link #getByVersion(String)}, pero si se pasa {@code profileId} el árbol queda
   * podado a solo los controles de ese perfil comunitario (y las funciones/categorías/
   * subcategorías/requisitos que se quedan sin ningún control tras filtrar se eliminan enteras, no
   * aparecen "vacías"). {@code profileId} null = catálogo completo, sin filtrar.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> getByVersion(String version, UUID profileId) {
    CatalogVersion cv =
        versionRepo
            .findByVersion(version)
            .orElseThrow(() -> new ResourceNotFoundException("Versión de catálogo", version));

    Set<UUID> allowedControlIds = null;
    if (profileId != null) {
      CommunityProfile profile =
          profileRepo
              .findById(profileId)
              .orElseThrow(() -> new ResourceNotFoundException("Perfil comunitario", profileId));
      allowedControlIds =
          profile.getControls().stream().map(CatalogControl::getId).collect(Collectors.toSet());
    }

    List<CatalogFunction> functions = functionRepo.findByVersionIdOrderBySortOrder(cv.getId());
    List<Map<String, Object>> functionsList = new ArrayList<>();

    for (CatalogFunction f : functions) {
      List<Map<String, Object>> catsList = new ArrayList<>();
      for (CatalogCategory c : f.getCategories()) {
        List<Map<String, Object>> subsList = new ArrayList<>();
        for (CatalogSubcategory s : c.getSubcategories()) {
          List<Map<String, Object>> reqsList = new ArrayList<>();
          for (CatalogRequirement r : s.getRequirements()) {
            List<Map<String, Object>> ctrlsList = new ArrayList<>();
            for (CatalogControl ct : r.getControls()) {
              if (allowedControlIds != null && !allowedControlIds.contains(ct.getId())) {
                continue;
              }
              Map<String, Object> ctMap = new LinkedHashMap<>();
              ctMap.put("id", ct.getId().toString());
              ctMap.put("code", ct.getCode());
              ctMap.put("description", ct.getDescription());
              ctMap.put("targetLevel", ct.getTargetLevel());
              ctrlsList.add(ctMap);
            }
            if (ctrlsList.isEmpty()) {
              continue; // requisito sin controles en el perfil: se poda entero
            }
            Map<String, Object> rMap = new LinkedHashMap<>();
            rMap.put("id", r.getId().toString());
            rMap.put("code", r.getCode());
            rMap.put("description", r.getDescription());
            rMap.put("controls", ctrlsList);
            reqsList.add(rMap);
          }
          if (reqsList.isEmpty()) {
            continue;
          }
          Map<String, Object> sMap = new LinkedHashMap<>();
          sMap.put("id", s.getId().toString());
          sMap.put("code", s.getCode());
          sMap.put("name", s.getName());
          sMap.put("description", s.getDescription());
          sMap.put("requirements", reqsList);
          subsList.add(sMap);
        }
        if (subsList.isEmpty()) {
          continue;
        }
        Map<String, Object> cMap = new LinkedHashMap<>();
        cMap.put("id", c.getId().toString());
        cMap.put("code", c.getCode());
        cMap.put("name", c.getName());
        cMap.put("description", c.getDescription());
        cMap.put("subcategories", subsList);
        catsList.add(cMap);
      }
      if (catsList.isEmpty()) {
        continue;
      }
      Map<String, Object> fMap = new LinkedHashMap<>();
      fMap.put("id", f.getId().toString());
      fMap.put("code", f.getCode());
      fMap.put("name", f.getName());
      fMap.put("description", f.getDescription());
      fMap.put("categories", catsList);
      functionsList.add(fMap);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", cv.getVersion());
    result.put("label", cv.getLabel());
    result.put("functions", functionsList);
    return result;
  }
}
