package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import uy.edu.prisma.domain.entity.CatalogFunction;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.CommunityProfile;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogFunctionRepository;
import uy.edu.prisma.domain.repository.CatalogSubcategoryRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.web.dto.Dto.ComparedEvaluationDto;
import uy.edu.prisma.web.dto.Dto.ComparisonCandidateDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;
import uy.edu.prisma.web.dto.Dto.FunctionDeltaDto;
import uy.edu.prisma.web.dto.Dto.MaturityTrendPointDto;
import uy.edu.prisma.web.dto.Dto.SubcategoryDeltaDto;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationComparisonServiceTest {

  @Mock private EvaluationRepository evalRepo;
  @Mock private MaturityResultRepository matRepo;
  @Mock private CatalogSubcategoryRepository subcategoryRepo;
  @Mock private CatalogFunctionRepository functionRepo;
  @Mock private MaturityTrendService trendService;
  @Mock private CurrentUserService currentUser;

  private EvaluationComparisonService service;
  private Organization org;
  private Evaluation a;
  private Evaluation b;
  private CatalogFunction govern;
  private CatalogFunction respond;
  private CatalogSubcategory gv1;
  private CatalogSubcategory gv2;
  private CatalogSubcategory rs1;
  private CatalogSubcategory onlyB;
  private CommunityProfile estandar;

  private static final OffsetDateTime T0 = OffsetDateTime.parse("2025-06-02T10:00:00-03:00");

  @BeforeEach
  void setUp() {
    service =
        new EvaluationComparisonService(
            evalRepo, matRepo, subcategoryRepo, functionRepo, trendService, currentUser);
    org = Organization.builder().id(UUID.randomUUID()).name("Intendencia de Rivera").build();
    estandar = CommunityProfile.builder().id(UUID.randomUUID()).name("Estándar").build();
    a = evaluation("Anual 2025", T0, estandar);
    b = evaluation("Anual 2026", T0.plusYears(1), estandar);
    govern = CatalogFunction.builder().id(UUID.randomUUID()).name("Gobernar").sortOrder(1).build();
    respond =
        CatalogFunction.builder().id(UUID.randomUUID()).name("Responder").sortOrder(5).build();
    gv1 = sub("GV.OC-01");
    gv2 = sub("GV.RM-02");
    rs1 = sub("RS.MA-01");
    onlyB = sub("GV.PO-01");
    when(subcategoryRepo.findAllById(any())).thenReturn(List.of(gv1, gv2, rs1, onlyB));
    when(functionRepo.findAllById(any())).thenReturn(List.of(govern, respond));
    when(matRepo.findByEvaluationId(a.getId()))
        .thenReturn(
            List.of(
                result(a, respond, rs1, 2, 3),
                result(a, govern, gv1, 1, 3),
                result(a, govern, gv2, 2, 3)));
    when(matRepo.findByEvaluationId(b.getId()))
        .thenReturn(
            List.of(
                result(b, govern, gv1, 3, 3),
                result(b, govern, gv2, 2, 3),
                result(b, respond, rs1, 1, 3),
                result(b, govern, onlyB, 1, 2)));
    when(trendService.measure(a)).thenReturn(measure(a, 82, 0.95));
    when(trendService.measure(b)).thenReturn(measure(b, 142, 1.48));
    when(evalRepo.findById(a.getId())).thenReturn(Optional.of(a));
    when(evalRepo.findById(b.getId())).thenReturn(Optional.of(b));
  }

  private Evaluation evaluation(String name, OffsetDateTime approvedAt, CommunityProfile profile) {
    return Evaluation.builder()
        .id(UUID.randomUUID())
        .name(name)
        .organization(org)
        .status(Evaluation.Status.APPROVED)
        .approvedAt(approvedAt)
        .communityProfile(profile)
        .catalogVersion("5.0")
        .globalMaturity(1)
        .build();
  }

  private static CatalogSubcategory sub(String code) {
    return CatalogSubcategory.builder().id(UUID.randomUUID()).code(code).name(code).build();
  }

  private static MaturityResult result(
      Evaluation e, CatalogFunction f, CatalogSubcategory s, int level, int target) {
    return MaturityResult.builder()
        .evaluation(e)
        .functionId(f.getId())
        .functionName(f.getName())
        .categoryId(UUID.randomUUID())
        .categoryName("Categoría")
        .subcategoryId(s.getId())
        .subcategoryName("Nombre " + s.getCode())
        .currentLevel(level)
        .targetLevel(target)
        .build();
  }

  private static MaturityTrendPointDto measure(Evaluation e, int compliant, double maturity) {
    return new MaturityTrendPointDto(
        e.getId(),
        e.getName(),
        "APPROVED",
        false,
        e.getApprovedAt(),
        e.getApprovedAt(),
        null,
        "Estándar",
        compliant,
        234,
        compliant * 100.0 / 234,
        maturity,
        1);
  }

  @Test
  void comparesByFunctionAndSubcategoryInCatalogOrder() {
    EvaluationComparisonDto c = service.compare(a.getId(), b.getId());

    assertEquals("Intendencia de Rivera", c.organizationName());
    assertTrue(c.sameProfile());
    assertEquals(1, c.subcategoriesUp());
    assertEquals(1, c.subcategoriesDown());
    assertEquals(1, c.subcategoriesSame());
    assertEquals(0, c.onlyInA());
    assertEquals(1, c.onlyInB());
    assertEquals(
        List.of("GV.OC-01", "GV.PO-01", "GV.RM-02", "RS.MA-01"),
        c.subcategories().stream().map(SubcategoryDeltaDto::code).toList());
    SubcategoryDeltaDto up = c.subcategories().get(0);
    assertEquals(1, up.levelA());
    assertEquals(3, up.levelB());
    assertEquals(2, up.delta());
    SubcategoryDeltaDto newOne = c.subcategories().get(1);
    assertNull(newOne.levelA());
    assertNull(newOne.delta());

    List<FunctionDeltaDto> functions = c.functions();
    assertEquals(
        List.of("Gobernar", "Responder"),
        functions.stream().map(FunctionDeltaDto::functionName).toList());
    assertEquals(1.5, functions.get(0).levelA());
    assertEquals(2.0, functions.get(0).levelB());
    assertEquals(0.5, functions.get(0).delta());
    assertEquals(-1.0, functions.get(1).delta());

    ComparedEvaluationDto ca = c.evaluationA();
    assertEquals(82, ca.compliantControls());
    assertEquals(1.48, c.evaluationB().maturityAverage());
    assertEquals(
        "Entre ambas evaluaciones la madurez global subió 0,53 (de 0,95 a 1,48). La mayor mejora"
            + " fue en Gobernar (+0,50). Responder bajó 1,00 y es la función a revisar.",
        c.reading());
    verify(currentUser).assertOrganizationAccess(org.getId());
  }

  @Test
  void differentProfilesAreFlagged() {
    b.setCommunityProfile(CommunityProfile.builder().id(UUID.randomUUID()).name("Básico").build());

    assertFalse(service.compare(a.getId(), b.getId()).sameProfile());
  }

  @Test
  void theReadingSaysWhenNothingChanged() {
    ComparedEvaluationDto x =
        new ComparedEvaluationDto(UUID.randomUUID(), "X", "Estándar", T0, 1, 1.2, 10, 20, 50);

    assertEquals(
        "Entre ambas evaluaciones la madurez global se mantuvo (de 1,20 a 1,20).",
        EvaluationComparisonService.reading(x, x, List.of()));
    ComparedEvaluationDto lower =
        new ComparedEvaluationDto(UUID.randomUUID(), "Y", "Estándar", T0, 1, 1.0, 10, 20, 50);
    assertTrue(EvaluationComparisonService.reading(x, lower, List.of()).contains("bajó 0,20"));
  }

  @Test
  void evaluationsOfDifferentOrganizationsAreNotCompared() {
    b.setOrganization(Organization.builder().id(UUID.randomUUID()).name("Otra").build());

    assertThrows(InvalidRequestException.class, () -> service.compare(a.getId(), b.getId()));
  }

  @Test
  void anEvaluationIsNotComparedWithItself() {
    assertThrows(InvalidRequestException.class, () -> service.compare(a.getId(), a.getId()));
    assertThrows(InvalidRequestException.class, () -> service.compare(null, a.getId()));
  }

  @Test
  void onlyFinalizedEvaluationsAreCompared() {
    b.setStatus(Evaluation.Status.IN_PROGRESS);

    assertThrows(ConflictException.class, () -> service.compare(a.getId(), b.getId()));
  }

  @Test
  void withoutAccessToTheOrganizationItIsDenied() {
    doThrow(new AccessDeniedException("no"))
        .when(currentUser)
        .assertOrganizationAccess(org.getId());

    assertThrows(AccessDeniedException.class, () -> service.compare(a.getId(), b.getId()));
    verify(matRepo, never()).findByEvaluationId(any());
  }

  @Test
  void anEvaluationThatDoesNotExistIsNotFound() {
    assertThrows(
        ResourceNotFoundException.class, () -> service.compare(a.getId(), UUID.randomUUID()));
  }

  @Test
  void candidatesAreTheFinalizedEvaluationsNewestFirst() {
    Evaluation discarded = evaluation("Descartada", null, null);
    discarded.setStatus(Evaluation.Status.ARCHIVED);
    when(currentUser.currentTenantId()).thenReturn(org.getId());
    when(evalRepo.findByOrganizationIdAndStatusIn(eq(org.getId()), any()))
        .thenReturn(List.of(a, discarded, b));

    List<ComparisonCandidateDto> candidates = service.candidates(null);

    assertEquals(
        List.of("Anual 2026", "Anual 2025"),
        candidates.stream().map(ComparisonCandidateDto::name).toList());
    assertEquals("Estándar", candidates.get(0).profileName());
    verify(currentUser).assertOrganizationAccess(org.getId());
  }

  @Test
  void candidatesNeedAnOrganization() {
    assertThrows(InvalidRequestException.class, () -> service.candidates(null));
  }

  @Test
  void candidatesOfAnotherOrganizationAreChecked() {
    UUID other = UUID.randomUUID();
    doThrow(new AccessDeniedException("no")).when(currentUser).assertOrganizationAccess(other);

    assertThrows(AccessDeniedException.class, () -> service.candidates(other));
  }
}
