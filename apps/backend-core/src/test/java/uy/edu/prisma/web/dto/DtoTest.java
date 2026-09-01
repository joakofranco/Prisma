package uy.edu.prisma.web.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uy.edu.prisma.web.dto.Dto.*;

class DtoTest {

  private final UUID id = UUID.randomUUID();
  private final OffsetDateTime now = OffsetDateTime.now();

  @Test
  void organizationRecords() {
    OrganizationDto dto = new OrganizationDto(id, "Acme", "NIT", "TECH", "SMALL", null, true, now);
    assertEquals(id, dto.id());
    assertEquals("Acme", dto.name());
    assertEquals("NIT", dto.rut());
    assertEquals("TECH", dto.sector());
    assertEquals("SMALL", dto.size());
    assertNull(dto.responsibleId());
    assertTrue(dto.enabled());
    assertEquals(now, dto.createdAt());

    CreateOrganizationDto create = new CreateOrganizationDto("Acme", "NIT", null, null, id);
    assertEquals("Acme", create.name());
    assertEquals("NIT", create.rut());
    assertEquals(id, create.responsibleId());
  }

  @Test
  void userRecords() {
    UserDto dto = new UserDto(id, "a@b.c", "F", "L", null, Set.of("VIEWER"), true, now, true);
    assertEquals(id, dto.id());
    assertEquals("a@b.c", dto.email());
    assertEquals("F", dto.firstName());
    assertEquals("L", dto.lastName());
    assertNull(dto.tenantId());
    assertEquals(Set.of("VIEWER"), dto.roles());
    assertTrue(dto.enabled());
    assertEquals(now, dto.createdAt());
    assertTrue(dto.canLogin());

    CreateUserDto create = new CreateUserDto("a@b.c", "F", "L", id, Set.of("AUDITOR"), "pass");
    assertEquals("F", create.firstName());
    assertEquals(id, create.tenantId());
    assertEquals("pass", create.password());
  }

  @Test
  void catalogRecords() {
    CatalogVersionDto version = new CatalogVersionDto("5.0", "MCU 5.0");
    assertEquals("5.0", version.version());
    assertEquals("MCU 5.0", version.label());

    CatalogControlDto control = new CatalogControlDto(id, "CT1", "desc", 3);
    assertEquals("CT1", control.code());
    assertEquals(3, control.targetLevel());

    CatalogRequirementDto requirement =
        new CatalogRequirementDto(id, "R1", "desc", List.of(control));
    assertEquals("R1", requirement.code());
    assertEquals(1, requirement.controls().size());
    assertEquals(control, requirement.controls().get(0));

    CatalogSubcategoryDto subcategory =
        new CatalogSubcategoryDto(id, "SC1", "name", "desc", List.of(requirement));
    assertEquals("SC1", subcategory.code());
    assertEquals(1, subcategory.requirements().size());

    CatalogCategoryDto category =
        new CatalogCategoryDto(id, "C1", "name", "desc", List.of(subcategory));
    assertEquals("C1", category.code());
    assertEquals(1, category.subcategories().size());

    CatalogFunctionDto function =
        new CatalogFunctionDto(id, "F1", "name", "desc", List.of(category));
    assertEquals("F1", function.code());
    assertEquals(1, function.categories().size());
    assertEquals(category, function.categories().get(0));
  }

  @Test
  void evaluationRecords() {
    EvaluationDto dto =
        new EvaluationDto(id, "E", id, "Acme", "5.0", null, null, "DRAFT", 3, id, now, now);
    assertEquals(id, dto.id());
    assertEquals("E", dto.name());
    assertEquals(id, dto.organizationId());
    assertEquals("Acme", dto.organizationName());
    assertEquals("5.0", dto.catalogVersion());
    assertNull(dto.communityProfileId());
    assertEquals("DRAFT", dto.status());
    assertEquals(3, dto.globalMaturity());
    assertEquals(id, dto.createdBy());
    assertEquals(now, dto.createdAt());

    CreateEvaluationDto create = new CreateEvaluationDto("E", id, "5.0", null);
    assertEquals("E", create.name());
    assertEquals(id, create.organizationId());
    assertEquals("5.0", create.catalogVersion());
  }

  @Test
  void responseRecords() {
    EvaluationResponseDto dto = new EvaluationResponseDto(id, id, id, true, "obs", id, now);
    assertEquals(id, dto.id());
    assertEquals(id, dto.evaluationId());
    assertEquals(id, dto.controlId());
    assertEquals(true, dto.compliant());
    assertEquals("obs", dto.observations());
    assertEquals(id, dto.respondedBy());
    assertEquals(now, dto.respondedAt());

    SaveResponseDto save = new SaveResponseDto(id, false, "nota");
    assertEquals(id, save.controlId());
    assertEquals(false, save.compliant());
    assertEquals("nota", save.observations());
  }

  @Test
  void maturityAndDashboardRecords() {
    MaturityResultDto result = new MaturityResultDto(id, "F", id, "C", id, "S", 1, 5, 4);
    assertEquals("F", result.functionName());
    assertEquals(1, result.currentLevel());
    assertEquals(4, result.gap());

    MaturityByFunctionDto mf = new MaturityByFunctionDto("F2", 3);
    assertEquals("F2", mf.name());
    assertEquals(3, mf.level());

    DashboardStatsDto stats =
        new DashboardStatsDto(10L, 4L, 3.5, 2L, Map.of("DRAFT", 1L), List.of(mf));
    assertEquals(10L, stats.totalEvaluations());
    assertEquals(4L, stats.activeOrganizations());
    assertEquals(3.5, stats.avgMaturityLevel());
    assertEquals(2L, stats.pendingImprovements());
    assertEquals(1L, stats.evaluationsByStatus().get("DRAFT"));
    assertEquals(1, stats.maturityByFunction().size());
    assertEquals(mf, stats.maturityByFunction().get(0));
  }

  @Test
  void paginatedRecordEqualsAndHash() {
    PaginatedDto<String> p1 = new PaginatedDto<>(List.of("a"), 1L, 0, 10);
    PaginatedDto<String> p2 = new PaginatedDto<>(List.of("a"), 1L, 0, 10);
    PaginatedDto<String> p3 = new PaginatedDto<>(List.of("b"), 1L, 0, 10);

    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
    assertNotEquals(p1, p3);
    assertNotEquals(p1, null);
    assertEquals(List.of("a"), p1.data());
    assertEquals(1L, p1.total());
    assertEquals(0, p1.page());
    assertEquals(10, p1.pageSize());
  }
}
