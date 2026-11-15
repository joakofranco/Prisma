package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uy.edu.prisma.domain.entity.CatalogCategory;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogRequirement;
import uy.edu.prisma.domain.entity.CatalogRequirementSubcategory;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.CatalogVersion;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogFunctionRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.CommunityProfileRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.web.dto.Dto.EvaluationOverviewDto;
import uy.edu.prisma.web.dto.Dto.OverviewControlDto;
import uy.edu.prisma.web.dto.Dto.OverviewSubcategoryDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationOverviewServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private CatalogVersionRepository versionRepo;
  @Mock private CatalogFunctionRepository functionRepo;
  @Mock private CatalogControlRepository controlRepo;
  @Mock private CommunityProfileRepository profileRepo;
  @Mock private CurrentUserService currentUser;

  private EvaluationOverviewService service;
  private Organization org;
  private CatalogVersion version;
  private CommunityProfile customProfile;
  private CommunityProfile basicProfile;
  private CommunityProfile standardProfile;
  private CommunityProfile advancedProfile;

  @BeforeEach
  void setUp() {
    service =
        new EvaluationOverviewService(
            evalRepo, versionRepo, functionRepo, controlRepo, profileRepo, currentUser);

    org = Organization.builder().id(UUID.randomUUID()).name("Acme").identifier("N1").build();
    version =
        CatalogVersion.builder().id(UUID.randomUUID()).version("5.0").label("MCU 5.0").build();

    CatalogFunction function =
        CatalogFunction.builder().id(UUID.randomUUID()).code("PR").name("Proteger").build();
    CatalogCategory category =
        CatalogCategory.builder()
            .id(UUID.randomUUID())
            .code("PR.PS")
            .name("Seguridad de los datos")
            .function_(function)
            .build();
    CatalogSubcategory s1 = subcategory("PR.PS-01", "Primera", "Descripcion primera", category);
    CatalogSubcategory s2 = subcategory("PR.PS-02", "Segunda", null, category);
    CatalogSubcategory s3 = subcategory("PR.PS-03", "Tercera", null, category);

    CatalogRequirement r1 = requirement("AD.1", "Requisito uno");
    CatalogRequirement r2 = requirement("AD.2", "Requisito dos");
    CatalogRequirement r3 = requirement("AD.3", "Requisito tres");
    CatalogControl c1 = control("AD.1-1", 1, r1);
    CatalogControl c2 = control("AD.1-2", 2, r1);
    CatalogControl c3 = control("AD.2-1", 3, r2);
    CatalogControl c4 = control("AD.3-1", 1, r3);
    r1.setControls(List.of(c1, c2));
    r2.setControls(List.of(c3));
    r3.setControls(List.of(c4));
    link(r1, s1);
    link(r2, s2);
    link(r3, s3);
    category.setSubcategories(List.of(s1, s2, s3));
    function.setCategories(List.of(category));

    customProfile = profile("Perfil ULTRABASICO", Set.of(c1));
    basicProfile = profile("Básico", Set.of(c1));
    standardProfile = profile("Estándar", Set.of(c1, c2, c4));
    advancedProfile = profile("Avanzado", Set.of(c1, c2, c3, c4));

    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.of(version));
    when(functionRepo.findByVersionIdOrderBySortOrder(version.getId()))
        .thenReturn(List.of(function));
    when(controlRepo.findByVersionOrdered("5.0")).thenReturn(List.of(c1, c2, c3, c4));
    when(profileRepo.findByCatalogVersionOrderByNameAsc("5.0"))
        .thenReturn(List.of(advancedProfile, basicProfile, customProfile, standardProfile));
  }

  private CatalogSubcategory subcategory(
      String code, String name, String description, CatalogCategory category) {
    return CatalogSubcategory.builder()
        .id(UUID.randomUUID())
        .code(code)
        .name(name)
        .description(description)
        .category(category)
        .build();
  }

  private CatalogRequirement requirement(String code, String description) {
    return CatalogRequirement.builder()
        .id(UUID.randomUUID())
        .code(code)
        .description(description)
        .version(version)
        .build();
  }

  private CatalogControl control(String code, int level, CatalogRequirement requirement) {
    return CatalogControl.builder()
        .id(UUID.randomUUID())
        .code(code)
        .description("Desc " + code)
        .targetLevel(level)
        .requirement(requirement)
        .build();
  }

  private void link(CatalogRequirement requirement, CatalogSubcategory subcategory) {
    CatalogRequirementSubcategory link =
        CatalogRequirementSubcategory.builder()
            .id(UUID.randomUUID())
            .requirement(requirement)
            .subcategory(subcategory)
            .build();
    requirement.setSubcategoryLinks(List.of(link));
    subcategory.setRequirementLinks(List.of(link));
  }

  private CommunityProfile profile(String name, Set<CatalogControl> controls) {
    return CommunityProfile.builder()
        .id(UUID.randomUUID())
        .name(name)
        .catalogVersion("5.0")
        .controls(new HashSet<>(controls))
        .build();
  }

  private Evaluation evaluation(CommunityProfile profile) {
    Evaluation eval =
        Evaluation.builder()
            .id(UUID.randomUUID())
            .name("Evaluacion 2026")
            .organization(org)
            .catalogVersion("5.0")
            .communityProfile(profile)
            .status(Evaluation.Status.DRAFT)
            .build();
    when(evalRepo.findById(eval.getId())).thenReturn(Optional.of(eval));
    return eval;
  }

  private List<OverviewSubcategoryDto> subcategories(EvaluationOverviewDto overview) {
    assertEquals(1, overview.functions().size());
    assertEquals(1, overview.functions().get(0).categories().size());
    return overview.functions().get(0).categories().get(0).subcategories();
  }

  @Test
  void prunesTheTreeToTheEvaluationProfile() {
    Evaluation eval = evaluation(customProfile);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    assertEquals("Perfil ULTRABASICO", overview.communityProfileName());
    assertEquals("MCU 5.0", overview.catalogLabel());
    assertEquals(1, overview.subcategoryCount());
    assertEquals(1, overview.requirementCount());
    assertEquals(1, overview.controlCount());
    List<OverviewSubcategoryDto> subs = subcategories(overview);
    assertEquals(1, subs.size());
    assertEquals("PR.PS-01", subs.get(0).code());
    assertEquals(1, subs.get(0).controls().size());
    assertEquals("AD.1-1", subs.get(0).controls().get(0).code());
  }

  @Test
  void withoutProfileShowsTheWholeCatalog() {
    Evaluation eval = evaluation(null);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    assertNull(overview.communityProfileId());
    assertEquals(3, overview.subcategoryCount());
    assertEquals(3, overview.requirementCount());
    assertEquals(4, overview.controlCount());
  }

  @Test
  void returnsRequirementsAndControlsOrderedByLevel() {
    Evaluation eval = evaluation(advancedProfile);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    OverviewSubcategoryDto first = subcategories(overview).get(0);
    assertEquals("Descripcion primera", first.description());
    assertEquals(1, first.requirements().size());
    assertEquals("AD.1", first.requirements().get(0).code());
    List<Integer> levels = first.controls().stream().map(OverviewControlDto::targetLevel).toList();
    assertEquals(List.of(1, 2), levels);
    assertEquals("AD.1", first.controls().get(0).requirementCode());
  }

  @Test
  void derivesPriorityFromTheOfficialProfiles() {
    Evaluation eval = evaluation(advancedProfile);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    List<OverviewSubcategoryDto> subs = subcategories(overview);
    assertEquals("ALTA", subs.get(0).priority());
    assertEquals("BAJA", subs.get(1).priority());
    assertEquals("MEDIA", subs.get(2).priority());
    assertEquals(1, overview.priorityCounts().alta());
    assertEquals(1, overview.priorityCounts().media());
    assertEquals(1, overview.priorityCounts().baja());
    assertEquals(0, overview.priorityCounts().sinPrioridad());
  }

  @Test
  void priorityIsTheSameWhateverProfileTheEvaluationUses() {
    Evaluation eval = evaluation(customProfile);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    assertEquals("ALTA", subcategories(overview).get(0).priority());
  }

  @Test
  void priorityIsMissingWhenTheOfficialProfilesDoNotExist() {
    when(profileRepo.findByCatalogVersionOrderByNameAsc("5.0")).thenReturn(List.of(customProfile));
    Evaluation eval = evaluation(null);

    EvaluationOverviewDto overview = service.getOverview(eval.getId());

    assertNull(subcategories(overview).get(0).priority());
    assertEquals(3, overview.priorityCounts().sinPrioridad());
    assertEquals(0, overview.priorityCounts().alta());
  }

  @Test
  void checksOrganizationAccess() {
    Evaluation eval = evaluation(customProfile);

    service.getOverview(eval.getId());

    verify(currentUser).assertOrganizationAccess(org.getId());
  }

  @Test
  void failsWhenEvaluationDoesNotExist() {
    UUID missing = UUID.randomUUID();
    when(evalRepo.findById(missing)).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.getOverview(missing));
  }

  @Test
  void failsWhenCatalogVersionDoesNotExist() {
    Evaluation eval = evaluation(customProfile);
    when(versionRepo.findByVersion("5.0")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.getOverview(eval.getId()));
  }

  @Test
  void priorityRule() {
    assertEquals("ALTA", EvaluationOverviewService.priorityFor(true, true, true));
    assertEquals("ALTA", EvaluationOverviewService.priorityFor(true, false, false));
    assertEquals("MEDIA", EvaluationOverviewService.priorityFor(false, true, true));
    assertEquals("BAJA", EvaluationOverviewService.priorityFor(false, false, true));
    assertNull(EvaluationOverviewService.priorityFor(false, false, false));
  }
}
