package uy.edu.prisma.domain.entity;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityTest {

  @Test
  void userBuildersAndAccessors() throws Exception {
    UUID id = UUID.randomUUID();
    Set<UserRole> roles = Set.of(UserRole.PRISMA_ADMIN, UserRole.AUDITOR);

    User all =
        new User(
            id,
            "a@b.c",
            "F",
            "L",
            null,
            null,
            null,
            false,
            roles,
            Set.of(),
            OffsetDateTime.now(),
            OffsetDateTime.now());
    assertEquals(id, all.getId());
    assertEquals("a@b.c", all.getEmail());
    assertEquals("F", all.getFirstName());
    assertEquals("L", all.getLastName());
    assertNull(all.getTenant());
    assertFalse(all.getEnabled());
    assertEquals(roles, all.getRoles());
    assertNotNull(all.getCreatedAt());
    assertNotNull(all.getUpdatedAt());

    User built =
        User.builder()
            .id(UUID.randomUUID())
            .email("x@b.c")
            .firstName("X")
            .lastName("Y")
            .enabled(true)
            .roles(Set.of(UserRole.VIEWER))
            .build();
    built.setEmail("zz@b.c");
    built.setFirstName("ZZ");
    built.setLastName("QQ");
    built.setTenant(Organization.builder().id(UUID.randomUUID()).build());
    built.setEnabled(false);
    built.setRoles(Set.of(UserRole.VIEWER));
    assertEquals("zz@b.c", built.getEmail());
    assertEquals("ZZ", built.getFirstName());
    assertEquals("QQ", built.getLastName());
    assertNotNull(built.getTenant());
    assertFalse(built.getEnabled());

    User empty = new User();
    empty.setEmail("e@b.c");
    assertEquals("e@b.c", empty.getEmail());
    assertTrue(User.builder().build().getEnabled());

    OffsetDateTime before = built.getUpdatedAt();
    invokePreUpdate(built);
    assertTrue(built.getUpdatedAt().isAfter(before) || built.getUpdatedAt().equals(before));
    assertEquals(5, UserRole.values().length);
  }

  @Test
  void organizationBuildersAndAccessors() throws Exception {
    UUID id = UUID.randomUUID();
    Organization all =
        new Organization(
            id, "N", "NIT", "S", "Z", null, true, OffsetDateTime.now(), OffsetDateTime.now());
    assertEquals(id, all.getId());
    assertEquals("N", all.getName());
    assertEquals("NIT", all.getRut());
    assertEquals("S", all.getSector());
    assertEquals("Z", all.getSize());
    assertNull(all.getResponsible());
    assertTrue(all.getEnabled());

    Organization built =
        Organization.builder().id(id).name("N2").rut("N2").sector("S2").size("Z2").build();
    built.setName("N3");
    built.setRut("N3");
    built.setSector("S3");
    built.setSize("Z3");
    built.setResponsible(User.builder().id(UUID.randomUUID()).build());
    built.setEnabled(false);
    assertEquals("N3", built.getName());
    assertFalse(built.getEnabled());
    assertNotNull(built.getResponsible());

    Organization empty = new Organization();
    empty.setName("X");
    assertEquals("X", empty.getName());
    assertTrue(Organization.builder().build().getEnabled());

    OffsetDateTime before = built.getUpdatedAt();
    invokePreUpdate(built);
    assertTrue(built.getUpdatedAt().isAfter(before) || built.getUpdatedAt().equals(before));
  }

  @Test
  void evaluationBuildersAndAccessors() throws Exception {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    Evaluation all =
        new Evaluation(
            id, "N", null, "5.0", null, Evaluation.Status.IN_PROGRESS, 3, null, now, now);
    assertEquals(id, all.getId());
    assertEquals("N", all.getName());
    assertNull(all.getOrganization());
    assertEquals("5.0", all.getCatalogVersion());
    assertEquals(Evaluation.Status.IN_PROGRESS, all.getStatus());
    assertEquals(3, all.getGlobalMaturity());
    assertNull(all.getCreatedBy());

    Evaluation built = Evaluation.builder().id(id).name("N2").organization(null).build();
    built.setName("N3");
    built.setOrganization(null);
    built.setCatalogVersion("5.1");
    built.setStatus(Evaluation.Status.ARCHIVED);
    built.setGlobalMaturity(4);
    built.setCreatedBy(User.builder().id(UUID.randomUUID()).build());
    assertEquals("N3", built.getName());
    assertEquals(4, built.getGlobalMaturity());
    assertNotNull(built.getCreatedBy());
    assertEquals(Evaluation.Status.DRAFT, Evaluation.builder().build().getStatus());

    Evaluation empty = new Evaluation();
    empty.setName("X");
    assertEquals("X", empty.getName());

    OffsetDateTime before = built.getUpdatedAt();
    invokePreUpdate(built);
    assertTrue(built.getUpdatedAt().isAfter(before) || built.getUpdatedAt().equals(before));
  }

  @Test
  void evaluationIsSelfAssessmentEditableOnlyInDraftInProgressOrReturned() {
    Evaluation eval = Evaluation.builder().build();

    eval.setStatus(Evaluation.Status.DRAFT);
    assertTrue(eval.isSelfAssessmentEditable());
    eval.setStatus(Evaluation.Status.IN_PROGRESS);
    assertTrue(eval.isSelfAssessmentEditable());
    eval.setStatus(Evaluation.Status.RETURNED);
    assertTrue(eval.isSelfAssessmentEditable());

    // Desde que se envía a auditoría (READY_FOR_AUDIT en adelante) queda bloqueada para la
    // organización -- ni respuestas (EvaluationService.saveResponse) ni evidencia
    // (EvidenceService) hasta que el auditor la devuelva (RETURNED).
    eval.setStatus(Evaluation.Status.READY_FOR_AUDIT);
    assertFalse(eval.isSelfAssessmentEditable());
    eval.setStatus(Evaluation.Status.IN_AUDIT);
    assertFalse(eval.isSelfAssessmentEditable());
    eval.setStatus(Evaluation.Status.APPROVED);
    assertFalse(eval.isSelfAssessmentEditable());
    eval.setStatus(Evaluation.Status.ARCHIVED);
    assertFalse(eval.isSelfAssessmentEditable());
  }

  @Test
  void evaluationResponseBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    EvaluationResponse all = new EvaluationResponse(id, null, null, true, "obs", null, now);
    assertEquals(id, all.getId());
    assertNull(all.getEvaluation());
    assertNull(all.getControl());
    assertEquals(true, all.getCompliant());
    assertEquals("obs", all.getObservations());
    assertNull(all.getRespondedBy());
    assertEquals(now, all.getRespondedAt());

    EvaluationResponse built =
        EvaluationResponse.builder().id(id).compliant(true).observations("nota").build();
    built.setCompliant(false);
    built.setObservations("x");
    built.setEvaluation(null);
    built.setControl(CatalogControl.builder().id(UUID.randomUUID()).build());
    built.setRespondedBy(null);
    assertEquals(false, built.getCompliant());
    assertNotNull(built.getControl());

    EvaluationResponse empty = new EvaluationResponse();
    empty.setCompliant(false);
    assertEquals(false, empty.getCompliant());
    assertNotNull(EvaluationResponse.builder().build().getRespondedAt());
  }

  @Test
  void catalogVersionBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogVersion all =
        new CatalogVersion(id, "5.0", "MCU", false, OffsetDateTime.now(), List.of());
    assertEquals(id, all.getId());
    assertEquals("5.0", all.getVersion());
    assertEquals("MCU", all.getLabel());
    assertFalse(all.getActive());
    assertNotNull(all.getCreatedAt());
    assertTrue(all.getFunctions().isEmpty());

    CatalogVersion built = CatalogVersion.builder().id(id).version("5.1").label("MCU 5.1").build();
    built.setVersion("5.2");
    built.setLabel("NUEVO");
    built.setActive(false);
    built.setFunctions(List.of());
    assertEquals("5.2", built.getVersion());
    assertFalse(built.getActive());

    CatalogVersion empty = new CatalogVersion();
    empty.setVersion("1.0");
    assertEquals("1.0", empty.getVersion());
    assertTrue(CatalogVersion.builder().build().getActive());
  }

  @Test
  void catalogFunctionBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogFunction all = new CatalogFunction(id, null, "F1", "N", "D", 1, List.of());
    assertEquals(id, all.getId());
    assertEquals("F1", all.getCode());
    assertEquals("N", all.getName());
    assertEquals("D", all.getDescription());
    assertEquals(1, all.getSortOrder());
    assertTrue(all.getCategories().isEmpty());

    CatalogFunction built = CatalogFunction.builder().id(id).code("F2").name("N2").build();
    built.setCode("F3");
    built.setName("N3");
    built.setDescription("D3");
    built.setSortOrder(7);
    built.setVersion(CatalogVersion.builder().id(UUID.randomUUID()).build());
    built.setCategories(List.of());
    assertEquals("F3", built.getCode());
    assertEquals(7, built.getSortOrder());
    assertNotNull(built.getVersion());

    CatalogFunction empty = new CatalogFunction();
    empty.setCode("X");
    assertEquals("X", empty.getCode());
    assertEquals(0, CatalogFunction.builder().build().getSortOrder());
  }

  @Test
  void catalogCategoryBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogFunction function = CatalogFunction.builder().id(UUID.randomUUID()).build();
    CatalogCategory all = new CatalogCategory(id, function, "C1", "N", "D", 2, List.of());
    assertEquals(id, all.getId());
    assertEquals(function, all.getFunction_());
    assertEquals("C1", all.getCode());
    assertEquals("N", all.getName());
    assertEquals("D", all.getDescription());
    assertEquals(2, all.getSortOrder());
    assertTrue(all.getSubcategories().isEmpty());

    CatalogCategory built = CatalogCategory.builder().id(id).code("C2").build();
    built.setCode("C3");
    built.setName("N3");
    built.setDescription("D3");
    built.setSortOrder(5);
    built.setFunction_(null);
    built.setSubcategories(List.of());
    assertEquals("C3", built.getCode());
    assertNull(built.getFunction_());

    CatalogCategory empty = new CatalogCategory();
    empty.setCode("Y");
    assertEquals("Y", empty.getCode());
    assertEquals(0, CatalogCategory.builder().build().getSortOrder());
  }

  @Test
  void catalogSubcategoryBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogSubcategory all = new CatalogSubcategory(id, null, "SC1", "N", "D", 3, List.of());
    assertEquals(id, all.getId());
    assertEquals("SC1", all.getCode());
    assertEquals("N", all.getName());
    assertEquals("D", all.getDescription());
    assertEquals(3, all.getSortOrder());
    assertTrue(all.getRequirements().isEmpty());

    CatalogSubcategory built = CatalogSubcategory.builder().id(id).code("SC2").build();
    built.setCode("SC3");
    built.setName("N3");
    built.setCategory(CatalogCategory.builder().id(UUID.randomUUID()).build());
    built.setRequirementLinks(List.of());
    assertEquals("SC3", built.getCode());
    assertNotNull(built.getCategory());
    assertTrue(built.getRequirements().isEmpty());

    CatalogSubcategory empty = new CatalogSubcategory();
    empty.setCode("Z");
    assertEquals("Z", empty.getCode());
  }

  @Test
  void catalogRequirementBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogRequirement all = new CatalogRequirement(id, null, "R1", "D", 4, List.of(), List.of());
    assertEquals(id, all.getId());
    assertEquals("R1", all.getCode());
    assertEquals("D", all.getDescription());
    assertEquals(4, all.getSortOrder());
    assertTrue(all.getControls().isEmpty());
    assertTrue(all.getSubcategories().isEmpty());

    CatalogRequirement built = CatalogRequirement.builder().id(id).code("R2").build();
    built.setCode("R3");
    built.setDescription("D3");
    built.setSortOrder(9);
    built.setVersion(CatalogVersion.builder().id(UUID.randomUUID()).build());
    built.setControls(List.of());
    assertEquals("R3", built.getCode());
    assertNotNull(built.getVersion());

    CatalogRequirement empty = new CatalogRequirement();
    empty.setCode("T");
    assertEquals("T", empty.getCode());
  }

  @Test
  void catalogRequirementSubcategoryBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogRequirement requirement = CatalogRequirement.builder().id(UUID.randomUUID()).build();
    CatalogSubcategory subcategory = CatalogSubcategory.builder().id(UUID.randomUUID()).build();
    CatalogRequirementSubcategory all =
        new CatalogRequirementSubcategory(id, requirement, subcategory, 2);
    assertEquals(id, all.getId());
    assertEquals(requirement, all.getRequirement());
    assertEquals(subcategory, all.getSubcategory());
    assertEquals(2, all.getSortOrder());

    CatalogRequirementSubcategory built = CatalogRequirementSubcategory.builder().id(id).build();
    built.setRequirement(requirement);
    built.setSubcategory(subcategory);
    built.setSortOrder(5);
    assertEquals(requirement, built.getRequirement());
    assertEquals(5, built.getSortOrder());
    assertEquals(0, CatalogRequirementSubcategory.builder().build().getSortOrder());

    CatalogRequirementSubcategory empty = new CatalogRequirementSubcategory();
    empty.setSortOrder(1);
    assertEquals(1, empty.getSortOrder());
  }

  @Test
  void catalogControlBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    CatalogControl all = new CatalogControl(id, null, "CT1", "D", 3, 1);
    assertEquals(id, all.getId());
    assertEquals("CT1", all.getCode());
    assertEquals("D", all.getDescription());
    assertEquals(3, all.getTargetLevel());
    assertEquals(1, all.getSortOrder());

    CatalogControl built = CatalogControl.builder().id(id).code("CT2").targetLevel(5).build();
    built.setCode("CT3");
    built.setDescription("D3");
    built.setTargetLevel(4);
    built.setSortOrder(6);
    built.setRequirement(CatalogRequirement.builder().id(UUID.randomUUID()).build());
    assertEquals("CT3", built.getCode());
    assertEquals(4, built.getTargetLevel());
    assertNotNull(built.getRequirement());

    CatalogControl empty = new CatalogControl();
    empty.setCode("U");
    assertEquals("U", empty.getCode());
    assertEquals(0, CatalogControl.builder().build().getSortOrder());
  }

  @Test
  void maturityResultBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    MaturityResult all =
        new MaturityResult(
            id,
            null,
            UUID.randomUUID(),
            "F",
            UUID.randomUUID(),
            "C",
            UUID.randomUUID(),
            "S",
            2,
            4,
            1);
    assertEquals(id, all.getId());
    assertEquals("F", all.getFunctionName());
    assertEquals("C", all.getCategoryName());
    assertEquals("S", all.getSubcategoryName());
    assertEquals(2, all.getCurrentLevel());
    assertEquals(4, all.getTargetLevel());
    assertEquals(1, all.getGap());

    MaturityResult built = MaturityResult.builder().id(id).currentLevel(3).targetLevel(5).build();
    built.setFunctionName("F2");
    built.setCategoryName("C2");
    built.setSubcategoryName("S2");
    built.setCurrentLevel(1);
    built.setTargetLevel(2);
    built.setGap(1);
    built.setEvaluation(null);
    assertEquals("F2", built.getFunctionName());
    assertEquals(1, built.getCurrentLevel());
    assertNull(built.getEvaluation());

    MaturityResult empty = new MaturityResult();
    empty.setGap(5);
    assertEquals(5, empty.getGap());
    assertEquals(0, MaturityResult.builder().build().getGap());
  }

  @Test
  void evidenceBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    Evidence all =
        new Evidence(
            id, null, null, "doc.pdf", 1024L, "application/pdf", "k1", null, now, "d", false);
    assertEquals(id, all.getId());
    assertNull(all.getEvaluation());
    assertNull(all.getControl());
    assertEquals("doc.pdf", all.getFileName());
    assertEquals(1024L, all.getFileSize());
    assertEquals("application/pdf", all.getFileType());
    assertEquals("k1", all.getStorageKey());
    assertNull(all.getUploadedBy());
    assertEquals(now, all.getUploadedAt());
    assertEquals("d", all.getDescription());

    Evidence built = Evidence.builder().id(id).fileName("a.bin").storageKey("k2").build();
    built.setFileName("b.bin");
    built.setFileSize(2048L);
    built.setFileType("application/octet-stream");
    built.setStorageKey("k3");
    built.setDescription("x");
    built.setEvaluation(Evaluation.builder().id(UUID.randomUUID()).build());
    built.setUploadedBy(User.builder().id(UUID.randomUUID()).build());
    assertEquals("b.bin", built.getFileName());
    assertEquals(2048L, built.getFileSize());
    assertNotNull(built.getEvaluation());
    assertNotNull(built.getUploadedBy());

    Evidence empty = new Evidence();
    empty.setFileName("z");
    assertEquals("z", empty.getFileName());
    assertEquals(0L, Evidence.builder().build().getFileSize());
  }

  @Test
  void auditObservationBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    AuditObservation all =
        new AuditObservation(
            id,
            null,
            null,
            AuditObservation.ObservationType.NON_CONFORMITY,
            "d",
            AuditObservation.ObservationStatus.CLOSED,
            null,
            now);
    assertEquals(id, all.getId());
    assertNull(all.getEvaluation());
    assertNull(all.getControl());
    assertEquals(AuditObservation.ObservationType.NON_CONFORMITY, all.getType());
    assertEquals("d", all.getDescription());
    assertEquals(AuditObservation.ObservationStatus.CLOSED, all.getStatus());
    assertNull(all.getCreatedBy());
    assertEquals(now, all.getCreatedAt());

    AuditObservation built = AuditObservation.builder().id(id).description("x").build();
    built.setType(AuditObservation.ObservationType.RECOMMENDATION);
    built.setDescription("y");
    built.setStatus(AuditObservation.ObservationStatus.IN_PROGRESS);
    built.setEvaluation(Evaluation.builder().id(UUID.randomUUID()).build());
    built.setCreatedBy(User.builder().id(UUID.randomUUID()).build());
    assertEquals(AuditObservation.ObservationType.RECOMMENDATION, built.getType());
    assertEquals(AuditObservation.ObservationStatus.IN_PROGRESS, built.getStatus());
    assertNotNull(built.getEvaluation());
    assertNotNull(built.getCreatedBy());

    AuditObservation empty = new AuditObservation();
    empty.setDescription("z");
    assertEquals("z", empty.getDescription());
    assertEquals(
        AuditObservation.ObservationType.OBSERVATION, AuditObservation.builder().build().getType());
    assertEquals(
        AuditObservation.ObservationStatus.OPEN, AuditObservation.builder().build().getStatus());
    assertNotNull(AuditObservation.builder().build().getCreatedAt());
  }

  @Test
  void improvementPlanBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    ImprovementPlan all =
        new ImprovementPlan(
            id,
            null,
            null,
            "Act",
            "Resp",
            ImprovementPlan.Priority.HIGH,
            ImprovementPlan.PlanStatus.IN_PROGRESS,
            java.time.LocalDate.of(2026, 12, 31),
            now);
    assertEquals(id, all.getId());
    assertNull(all.getEvaluation());
    assertNull(all.getControl());
    assertEquals("Act", all.getAction());
    assertEquals("Resp", all.getResponsible());
    assertEquals(ImprovementPlan.Priority.HIGH, all.getPriority());
    assertEquals(ImprovementPlan.PlanStatus.IN_PROGRESS, all.getStatus());
    assertEquals(java.time.LocalDate.of(2026, 12, 31), all.getDueDate());
    assertEquals(now, all.getCreatedAt());

    ImprovementPlan built = ImprovementPlan.builder().id(id).action("A").build();
    built.setAction("B");
    built.setResponsible("R2");
    built.setPriority(ImprovementPlan.Priority.LOW);
    built.setStatus(ImprovementPlan.PlanStatus.COMPLETED);
    built.setDueDate(java.time.LocalDate.of(2026, 1, 1));
    built.setEvaluation(Evaluation.builder().id(UUID.randomUUID()).build());
    assertEquals("B", built.getAction());
    assertEquals(ImprovementPlan.Priority.LOW, built.getPriority());
    assertEquals(ImprovementPlan.PlanStatus.COMPLETED, built.getStatus());

    ImprovementPlan empty = new ImprovementPlan();
    empty.setAction("Z");
    assertEquals("Z", empty.getAction());
    assertEquals(ImprovementPlan.Priority.MEDIUM, ImprovementPlan.builder().build().getPriority());
    assertEquals(ImprovementPlan.PlanStatus.PENDING, ImprovementPlan.builder().build().getStatus());
  }

  @Test
  void auditLogBuildersAndAccessors() {
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    AuditLog all =
        new AuditLog(
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "CREATE",
            "user:1",
            "{}",
            "127.0.0.1",
            "agent",
            now);
    assertEquals(id, all.getId());
    assertNotNull(all.getUserId());
    assertNotNull(all.getTenantId());
    assertEquals("CREATE", all.getAction());
    assertEquals("user:1", all.getResource());
    assertEquals("{}", all.getPayload());
    assertEquals("127.0.0.1", all.getIpAddress());
    assertEquals("agent", all.getUserAgent());
    assertEquals(now, all.getCreatedAt());

    AuditLog built = AuditLog.builder().id(id).action("DELETE").build();
    built.setAction("UPDATE");
    built.setResource("org:1");
    built.setPayload(null);
    built.setIpAddress("10.0.0.1");
    built.setUserAgent("curl");
    assertEquals("UPDATE", built.getAction());
    assertEquals("curl", built.getUserAgent());

    AuditLog empty = new AuditLog();
    empty.setAction("X");
    assertEquals("X", empty.getAction());
    assertNotNull(AuditLog.builder().build().getCreatedAt());
  }

  private static void invokePreUpdate(Object target) throws Exception {
    Method method = target.getClass().getDeclaredMethod("onUpdate");
    method.setAccessible(true);
    method.invoke(target);
  }
}
