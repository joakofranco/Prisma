package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.*;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.*;

@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
// Sin esto, findByVersionOrdered() (y cualquier otro repo.find*) devuelve entidades cuya sesion
// de Hibernate ya se cerro al volver del metodo @Transactional del repositorio; acceder despues a
// una relacion LAZY (p.ej. CatalogControl.getRequirement()) revienta con
// LazyInitializationException
// "no session". @Transactional en la clase mantiene abierta la misma sesion durante todo el test.
@Transactional
class PrismaApplicationIT {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("prisma_test")
          .withUsername("test")
          .withPassword("test")
          .withInitScript("db/init-test.sql");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "");
  }

  @Autowired private CatalogControlRepository controlRepo;
  @Autowired private CatalogVersionRepository versionRepo;
  @Autowired private OrganizationRepository orgRepo;
  @Autowired private EvaluationService evaluationService;
  @Autowired private DashboardService dashboardService;
  @Autowired private OrganizationService organizationService;
  @Autowired private UserService userService;
  @Autowired private CatalogService catalogService;

  // Este test no pasa por el filtro de seguridad HTTP, asi que no hay un JWT autenticado en el
  // SecurityContext. Sin este mock, assertOrganizationAccess (aislamiento multi-tenant) rechaza
  // toda llamada de EvaluationService/DashboardService con 403 al no poder resolver un usuario.
  @MockitoBean private CurrentUserService currentUser;

  // UserService.create() provisiona el usuario en Keycloak via HTTP; no hay un Keycloak real
  // levantado en este test (solo Postgres via Testcontainers), asi que sin este mock revienta
  // con "Connection refused" contra localhost:8180. El mock devuelve null en createUser() (sin
  // stub explicito), que es exactamente lo que hacia la app antes de provisionar en Keycloak.
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  @BeforeEach
  void mockGlobalUser() {
    // PRISMA_ADMIN es el unico rol verdaderamente global desde que AUDITOR quedo acotado a
    // organizaciones asignadas (ver CurrentUserService) -- isGlobalRole() sigue existiendo pero ya
    // no es lo que EvaluationService/DashboardService consultan para la rama "sin restriccion".
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.isGlobalRole()).thenReturn(true);
  }

  @Test
  void mainStartsWithContainerDatabaseAndReturns() {
    PrismaApplication.main(
        new String[] {
          "--spring.main.web-application-type=none",
          "--spring.datasource.url=" + postgres.getJdbcUrl(),
          "--spring.datasource.username=" + postgres.getUsername(),
          "--spring.datasource.password=" + postgres.getPassword(),
          "--spring.security.oauth2.resourceserver.jwt.issuer-uri=",
          "--logging.level.root=OFF"
        });
  }

  @Test
  void servicesExposeAllDataTransferRecords() {
    OrganizationDto org =
        organizationService.create(
            new CreateOrganizationDto("Acme S.A.", "NIT-ACME-SERV", "TECH", "MEDIUM", null));
    assertNotNull(org.id());
    assertEquals("Acme S.A.", org.name());

    PaginatedDto<OrganizationDto> organizations = organizationService.list("Acme", 0, 10);
    assertTrue(organizations.data().stream().anyMatch(dto -> dto.id().equals(org.id())));

    UserDto user =
        userService.create(
            new CreateUserDto(
                "it.services@prisma.uy",
                "IT",
                "Services",
                org.id(),
                Set.of("AUDITOR"),
                Set.of(org.id()),
                "x"));
    assertNotNull(user.id());
    assertEquals(Set.of("AUDITOR"), user.roles());
    assertEquals(Set.of(org.id()), user.auditedOrganizationIds());

    PaginatedDto<UserDto> users = userService.list("it.services", 0, 10);
    assertTrue(users.data().stream().anyMatch(dto -> dto.id().equals(user.id())));

    boolean hasMcu5 =
        catalogService.listVersions().stream().anyMatch(v -> v.version().equals("5.0"));
    assertTrue(hasMcu5);

    CatalogFunctionDto function =
        new CatalogFunctionDto(
            UUID.randomUUID(),
            "F1",
            "Funcion 1",
            "desc",
            List.of(
                new CatalogCategoryDto(
                    UUID.randomUUID(),
                    "C1",
                    "Categoria 1",
                    "desc",
                    List.of(
                        new CatalogSubcategoryDto(
                            UUID.randomUUID(),
                            "S1",
                            "Subcategoria 1",
                            "desc",
                            List.of(
                                new CatalogRequirementDto(
                                    UUID.randomUUID(),
                                    "R1",
                                    "desc",
                                    List.of(
                                        new CatalogControlDto(
                                            UUID.randomUUID(), "CT1", "desc", 3)))))))));
    assertEquals("F1", function.code());
    assertEquals(
        3,
        function
            .categories()
            .get(0)
            .subcategories()
            .get(0)
            .requirements()
            .get(0)
            .controls()
            .get(0)
            .targetLevel());
  }

  // Catalogo real MCU 5.0 (Agesic, V9/V10/V15/V18/V19): 6 funciones, 103 subcategorias con al
  // menos un Requisito asociado, 671 controles (checklist graduado por nivel 1-4, ver
  // EvaluationService.calculateMaturity para el calculo acumulativo). Bajó de 1012 a 670 con V15
  // (requisito <-> subcategoria pasa de 1:N a N:M) y subió a 671 con V19 (control nuevo OR.5-10 de
  // la planilla Agesic 2025). V19 tambien puebla catalog_control_subcategories con el mapeo curado
  // (1013 pares), asi que effectiveSubcategories() usa ese mapeo (no el fallback
  // requisito->subcat).
  private static final int TOTAL_CONTROLS = 671;
  private static final int TOTAL_SUBCATEGORIES = 103;

  @Test
  void contextLoadsWithMigrationsApplied() {
    assertTrue(versionRepo.findByVersion("5.0").isPresent());
    assertEquals(TOTAL_CONTROLS, controlRepo.findByVersionOrdered("5.0").size());
  }

  @Test
  void findByVersionOrderedReturnsSeedControlsInOrder() {
    List<CatalogControl> controls = controlRepo.findByVersionOrdered("5.0");

    assertEquals(TOTAL_CONTROLS, controls.size());
    // Orden real desde V15: CatalogControlRepository.findByVersionOrdered ordena por
    // Requisito/Control (ya no hay un único camino Función->Categoría->Subcategoría->Requisito
    // por el que ordenar, ver el comentario ahí), y el sortOrder de cada Requisito quedó fijado
    // por V15 al consolidar los duplicados -- no coincide con el orden alfabético de código que
    // tenía antes de esa migración.
    assertEquals("CO.6-1", controls.get(0).getCode());
    assertEquals("SF.1-1", controls.get(1).getCode());
    assertEquals("PD.8-10", controls.get(controls.size() - 1).getCode());

    Organization org =
        orgRepo.save(Organization.builder().name("Orden").rut("NIT-ORDER").sector("TECH").build());
    EvaluationDto eval =
        evaluationService.create(new CreateEvaluationDto("E", org.getId(), "5.0", null));
    List<MaturityResultDto> results = evaluationService.getResults(eval.id());
    assertNotNull(results);
  }

  @Test
  void fullEvaluationLifecyclePersistsMaturityResults() {
    Organization org =
        orgRepo.save(
            Organization.builder()
                .name("Lifecycle")
                .rut("NIT-LIFE")
                .sector("FIN")
                .size("SMALL")
                .enabled(true)
                .build());

    EvaluationDto created =
        evaluationService.create(new CreateEvaluationDto("Eval 2026", org.getId(), "5.0", null));
    assertEquals("DRAFT", created.status());

    List<CatalogControl> controls = controlRepo.findByVersionOrdered("5.0");
    CatalogControl first = controls.get(0);
    // calculateMaturity agrupa por effectiveSubcategories() (mapeo curado de V19), no por
    // requirement.getSubcategories(): elegimos la subcategoria y sus controles con ese criterio.
    UUID subcategoryId = first.effectiveSubcategories().iterator().next().getId();
    List<CatalogControl> subcatControls =
        controls.stream()
            .filter(
                c ->
                    c.effectiveSubcategories().stream()
                        .anyMatch(s -> s.getId().equals(subcategoryId)))
            .toList();

    EvaluationResponseDto response =
        evaluationService.saveResponse(
            created.id(), new SaveResponseDto(first.getId(), false, "evidencia parcial"));
    assertEquals(false, response.compliant());

    // Cumplir TODOS los controles de esta subcategoria -> debe alcanzar su nivel maximo.
    subcatControls.forEach(
        control ->
            evaluationService.saveResponse(
                created.id(), new SaveResponseDto(control.getId(), true, "implementado")));

    evaluationService.calculateMaturity(created.id());

    List<MaturityResultDto> results = evaluationService.getResults(created.id());
    assertEquals(TOTAL_SUBCATEGORIES, results.size());
    MaturityResultDto ours =
        results.stream()
            .filter(r -> r.subcategoryId().equals(subcategoryId))
            .findFirst()
            .orElseThrow();
    assertEquals(ours.targetLevel(), ours.currentLevel());
    assertEquals(0, ours.gap());

    EvaluationDto after = evaluationService.getById(created.id());
    // globalMaturity se promedia sobre TODAS las subcategorias de la version (103), con 0 en las
    // no evaluadas -- igual que la planilla de Agesic. Con una sola subcategoria completada de 103
    // el promedio redondea a 0; lo relevante es que NO sea null (hay respuestas cargadas).
    assertNotNull(after.globalMaturity());
    assertTrue(after.globalMaturity() >= 0 && after.globalMaturity() <= 4);

    DashboardStatsDto stats = dashboardService.getStats();
    assertTrue(stats.totalEvaluations() >= 1);
    assertEquals(TOTAL_CONTROLS, controlRepo.findByVersionOrdered("5.0").size());
  }

  @Test
  void calculateMaturityRecomputesOverPreviousResults() {
    Organization org =
        orgRepo.save(
            Organization.builder().name("Recompute").rut("NIT-RECOMPUTE").sector("TECH").build());
    EvaluationDto created =
        evaluationService.create(new CreateEvaluationDto("Re", org.getId(), "5.0", null));

    List<CatalogControl> controls = controlRepo.findByVersionOrdered("5.0");
    CatalogControl first = controls.get(0);
    // calculateMaturity agrupa por effectiveSubcategories() (mapeo curado de V19), no por
    // requirement.getSubcategories(): elegimos la subcategoria y sus controles con ese criterio.
    UUID subcategoryId = first.effectiveSubcategories().iterator().next().getId();
    List<CatalogControl> subcatControls =
        controls.stream()
            .filter(
                c ->
                    c.effectiveSubcategories().stream()
                        .anyMatch(s -> s.getId().equals(subcategoryId)))
            .toList();
    subcatControls.forEach(
        c ->
            evaluationService.saveResponse(
                created.id(), new SaveResponseDto(c.getId(), true, null)));

    evaluationService.calculateMaturity(created.id());
    List<MaturityResultDto> firstRun = evaluationService.getResults(created.id());
    assertEquals(TOTAL_SUBCATEGORIES, firstRun.size());
    MaturityResultDto ourFirst =
        firstRun.stream()
            .filter(r -> r.subcategoryId().equals(subcategoryId))
            .findFirst()
            .orElseThrow();
    assertEquals(ourFirst.targetLevel(), ourFirst.currentLevel());

    // "Rompo" el control mas exigente de la subcategoria -> el nivel acumulativo tiene que caer
    // al nivel anterior, no quedarse igual (recompute real, no resultados obsoletos).
    CatalogControl highest =
        subcatControls.stream()
            .max(java.util.Comparator.comparingInt(CatalogControl::getTargetLevel))
            .orElseThrow();
    evaluationService.saveResponse(created.id(), new SaveResponseDto(highest.getId(), false, null));
    evaluationService.calculateMaturity(created.id());
    List<MaturityResultDto> secondRun = evaluationService.getResults(created.id());
    MaturityResultDto ourSecond =
        secondRun.stream()
            .filter(r -> r.subcategoryId().equals(subcategoryId))
            .findFirst()
            .orElseThrow();

    assertEquals(firstRun.size(), secondRun.size());
    assertEquals(highest.getTargetLevel() - 1, ourSecond.currentLevel());
    assertTrue(ourSecond.currentLevel() < ourFirst.currentLevel());
  }
}
