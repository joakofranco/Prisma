package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogServiceTest {

  @Mock private CatalogVersionRepository versionRepo;
  @Mock private CatalogFunctionRepository functionRepo;
  @Mock private CommunityProfileRepository profileRepo;
  @Mock private EvaluationRepository evalRepo;
  @Mock private AuditLogService auditLog;

  private CatalogService service;
  private CatalogVersion version;
  private CatalogFunction function;
  private CatalogControl control;

  @BeforeEach
  void setUp() {
    service = new CatalogService(versionRepo, functionRepo, profileRepo, evalRepo, auditLog);

    version =
        CatalogVersion.builder().id(UUID.randomUUID()).version("5.0").label("MCU 5.0").build();

    function = CatalogFunction.builder().id(UUID.randomUUID()).code("F1").name("Riesgos").build();
    CatalogCategory category =
        CatalogCategory.builder()
            .id(UUID.randomUUID())
            .code("F1.C1")
            .name("Identificacion")
            .description("Desc categoria")
            .function_(function)
            .build();
    CatalogSubcategory subcategory =
        CatalogSubcategory.builder()
            .id(UUID.randomUUID())
            .code("F1.C1.SC1")
            .name("Inventario")
            .description("Desc sub")
            .category(category)
            .build();
    CatalogRequirement requirement =
        CatalogRequirement.builder()
            .id(UUID.randomUUID())
            .code("R1")
            .description("Desc req")
            .version(version)
            .build();
    control =
        CatalogControl.builder()
            .id(UUID.randomUUID())
            .code("CT1")
            .description("Desc ctrl")
            .targetLevel(3)
            .requirement(requirement)
            .build();
    requirement.setControls(List.of(control));
    CatalogRequirementSubcategory link =
        CatalogRequirementSubcategory.builder()
            .id(UUID.randomUUID())
            .requirement(requirement)
            .subcategory(subcategory)
            .build();
    requirement.setSubcategoryLinks(List.of(link));
    subcategory.setRequirementLinks(List.of(link));
    category.setSubcategories(List.of(subcategory));
    function.setCategories(List.of(category));
  }

  @Test
  void listVersionsMapsVersions() {
    when(versionRepo.findAll()).thenReturn(List.of(version));

    List<CatalogVersionDto> versions = service.listVersions();

    assertEquals(1, versions.size());
    assertEquals("5.0", versions.get(0).version());
    assertEquals("MCU 5.0", versions.get(0).label());
  }

  @Test
  void getByVersionBuildsFullTree() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(functionRepo.findByVersionIdOrderBySortOrder(version.getId()))
        .thenReturn(List.of(function));

    Map<String, Object> result = service.getByVersion("5.0");

    assertEquals("5.0", result.get("version"));
    assertEquals("MCU 5.0", result.get("label"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> functions = (List<Map<String, Object>>) result.get("functions");
    assertEquals(1, functions.size());

    Map<String, Object> fMap = functions.get(0);
    assertEquals("F1", fMap.get("code"));
    assertEquals("Riesgos", fMap.get("name"));
    assertEquals(function.getId().toString(), fMap.get("id"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> categories = (List<Map<String, Object>>) fMap.get("categories");
    Map<String, Object> cMap = categories.get(0);
    assertEquals("F1.C1", cMap.get("code"));
    assertEquals("Desc categoria", cMap.get("description"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> subs = (List<Map<String, Object>>) cMap.get("subcategories");
    Map<String, Object> sMap = subs.get(0);
    assertEquals("Inventario", sMap.get("name"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> reqs = (List<Map<String, Object>>) sMap.get("requirements");
    Map<String, Object> rMap = reqs.get(0);
    assertEquals("R1", rMap.get("code"));
    assertEquals("Desc req", rMap.get("description"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> ctrls = (List<Map<String, Object>>) rMap.get("controls");
    Map<String, Object> ctMap = ctrls.get(0);
    assertEquals("CT1", ctMap.get("code"));
    assertEquals("Desc ctrl", ctMap.get("description"));
    assertEquals(3, ctMap.get("targetLevel"));
  }

  @Test
  void getByVersionThrowsWhenMissing() {
    when(versionRepo.findByVersion("9.9")).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getByVersion("9.9"));
  }

  @Test
  void getByVersionWithProfilePrunesControlsOutsideTheProfile() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(functionRepo.findByVersionIdOrderBySortOrder(version.getId()))
        .thenReturn(List.of(function));
    UUID profileId = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder().id(profileId).controls(Set.of()).build(); // sin este control
    when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));

    Map<String, Object> result = service.getByVersion("5.0", profileId);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> functions = (List<Map<String, Object>>) result.get("functions");
    // La función entera se poda: su único control no está en el perfil.
    assertTrue(functions.isEmpty());
  }

  @Test
  void getByVersionWithProfileKeepsControlsInsideTheProfile() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(functionRepo.findByVersionIdOrderBySortOrder(version.getId()))
        .thenReturn(List.of(function));
    UUID profileId = UUID.randomUUID();
    CommunityProfile profile =
        CommunityProfile.builder().id(profileId).controls(Set.of(control)).build();
    when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));

    Map<String, Object> result = service.getByVersion("5.0", profileId);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> functions = (List<Map<String, Object>>) result.get("functions");
    assertEquals(1, functions.size());
  }

  @Test
  void getByVersionThrowsWhenProfileMissing() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    UUID profileId = UUID.randomUUID();
    when(profileRepo.findById(profileId)).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.getByVersion("5.0", profileId));
  }

  // ---- importCatalog / deleteVersion ----

  private CatalogImportDto buildImportDto(String versionCode) {
    CatalogControlDto control = new CatalogControlDto(null, "CT1", "Desc control", 1);
    CatalogRequirementDto requirement =
        new CatalogRequirementDto(null, "R1", "Desc requisito", List.of(control));
    CatalogSubcategoryDto subcategory =
        new CatalogSubcategoryDto(null, "SC1", "Subcategoria", "Desc sub", List.of(requirement));
    CatalogCategoryDto category =
        new CatalogCategoryDto(null, "C1", "Categoria", "Desc cat", List.of(subcategory));
    CatalogFunctionDto function =
        new CatalogFunctionDto(null, "F1", "Funcion", "Desc func", List.of(category));
    return new CatalogImportDto(versionCode, "Catálogo de prueba", List.of(function));
  }

  @Test
  void importCatalogPersistsFullTreeWithSortOrder() {
    when(versionRepo.findByVersion("6.0")).thenReturn(Optional.empty());
    when(versionRepo.save(any(CatalogVersion.class))).thenAnswer(inv -> inv.getArgument(0));

    CatalogVersionDto result = service.importCatalog(buildImportDto("6.0"));

    assertEquals("6.0", result.version());
    assertEquals("Catálogo de prueba", result.label());

    ArgumentCaptor<CatalogVersion> captor = ArgumentCaptor.forClass(CatalogVersion.class);
    verify(versionRepo, times(1)).save(captor.capture());
    CatalogVersion saved = captor.getValue();
    assertEquals(1, saved.getFunctions().size());
    CatalogFunction savedFunction = saved.getFunctions().get(0);
    assertEquals("F1", savedFunction.getCode());
    assertEquals(0, savedFunction.getSortOrder());
    assertSame(saved, savedFunction.getVersion());

    CatalogCategory savedCategory = savedFunction.getCategories().get(0);
    assertEquals("C1", savedCategory.getCode());
    assertSame(savedFunction, savedCategory.getFunction_());

    CatalogControl savedControl =
        savedCategory.getSubcategories().get(0).getRequirements().get(0).getControls().get(0);
    assertEquals("CT1", savedControl.getCode());
    assertEquals(1, savedControl.getTargetLevel());

    verify(auditLog, times(1)).record(eq("CREATE"), contains("catalog_version:"), any());
  }

  @Test
  void importCatalogLinksRepeatedRequirementCodeToBothSubcategoriesWithoutDuplicating() {
    when(versionRepo.findByVersion("6.0")).thenReturn(Optional.empty());
    when(versionRepo.save(any(CatalogVersion.class))).thenAnswer(inv -> inv.getArgument(0));

    // Mismo codigo de requisito ("R1") repetido bajo una segunda subcategoria ("SC2"), con un
    // control nuevo ("CT2") que la primera ocurrencia no traia.
    CatalogControlDto control1 = new CatalogControlDto(null, "CT1", "Desc control", 1);
    CatalogControlDto control2 = new CatalogControlDto(null, "CT2", "Desc control 2", 2);
    CatalogRequirementDto requirement1 =
        new CatalogRequirementDto(null, "R1", "Desc requisito", List.of(control1));
    CatalogRequirementDto requirement2 =
        new CatalogRequirementDto(null, "R1", "Desc requisito", List.of(control1, control2));
    CatalogSubcategoryDto subcategory1 =
        new CatalogSubcategoryDto(null, "SC1", "Subcategoria 1", "Desc sub 1", List.of(requirement1));
    CatalogSubcategoryDto subcategory2 =
        new CatalogSubcategoryDto(null, "SC2", "Subcategoria 2", "Desc sub 2", List.of(requirement2));
    CatalogCategoryDto category =
        new CatalogCategoryDto(null, "C1", "Categoria", "Desc cat", List.of(subcategory1, subcategory2));
    CatalogFunctionDto function =
        new CatalogFunctionDto(null, "F1", "Funcion", "Desc func", List.of(category));
    CatalogImportDto dto = new CatalogImportDto("6.0", "Catálogo de prueba", List.of(function));

    service.importCatalog(dto);

    ArgumentCaptor<CatalogVersion> captor = ArgumentCaptor.forClass(CatalogVersion.class);
    verify(versionRepo, times(1)).save(captor.capture());
    List<CatalogSubcategory> subcategories =
        captor.getValue().getFunctions().get(0).getCategories().get(0).getSubcategories();

    CatalogRequirement req1 = subcategories.get(0).getRequirements().get(0);
    CatalogRequirement req2 = subcategories.get(1).getRequirements().get(0);
    assertSame(req1, req2, "el mismo codigo de requisito debe reusar la misma entidad");
    assertEquals(2, req1.getControls().size(), "CT2 se agrega, CT1 no se duplica");
    assertEquals(
        Set.of("CT1", "CT2"),
        req1.getControls().stream().map(CatalogControl::getCode).collect(java.util.stream.Collectors.toSet()));
    assertEquals(2, req1.getSubcategories().size(), "el requisito queda enlazado a ambas subcategorias");
  }

  @Test
  void importCatalogThrowsWhenVersionAlreadyExists() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));

    assertThrows(RuntimeException.class, () -> service.importCatalog(buildImportDto("5.0")));
    verify(versionRepo, never()).save(any());
  }

  @Test
  void deleteVersionRemovesVersionWhenUnused() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(evalRepo.existsByCatalogVersion("5.0")).thenReturn(false);

    service.deleteVersion("5.0");

    verify(versionRepo, times(1)).delete(version);
    verify(auditLog, times(1)).record(eq("DELETE"), contains("catalog_version:"), any());
  }

  @Test
  void deleteVersionThrowsWhenInUseByEvaluations() {
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(evalRepo.existsByCatalogVersion("5.0")).thenReturn(true);

    assertThrows(RuntimeException.class, () -> service.deleteVersion("5.0"));
    verify(versionRepo, never()).delete(any());
  }

  @Test
  void deleteVersionThrowsWhenMissing() {
    when(versionRepo.findByVersion("9.9")).thenReturn(Optional.empty());

    assertThrows(RuntimeException.class, () -> service.deleteVersion("9.9"));
  }
}
