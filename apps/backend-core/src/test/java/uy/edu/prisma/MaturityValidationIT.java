package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
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
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EvaluationService;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.CatalogFunctionRepository;
import uy.edu.prisma.domain.repository.CatalogVersionRepository;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.*;

/**
 * Coteja el cálculo de madurez de PRISMA contra la planilla oficial de Agesic (formato 2025,
 * `docs/mcu-5.0/Planilla MCU 5.0 Básico.xlsx`). Usa el catálogo MCU 5.0 y el perfil comunitario
 * `Básico` ya SEMBRADOS (migraciones V9/V10/V15/V18/V19), marca "cumple" los 165 controles
 * requeridos y verifica que `calculateMaturity` reproduce la columna "Nivel de Madurez" de la
 * planilla en las 103 subcategorías.
 *
 * <p>Baseline: src/test/resources/mcu50/expected_basico.json, generado por scripts/mcu50/build.py.
 */
@Testcontainers
@SpringBootTest(classes = PrismaApplication.class)
@Transactional
class MaturityValidationIT {

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

  private static final String VERSION = "5.0";
  private static final UUID BASICO_PROFILE_ID =
      UUID.fromString("e1000001-0000-0000-0000-000000000001");

  @Autowired private EvaluationService evaluationService;
  @Autowired private CatalogControlRepository controlRepo;
  @Autowired private CatalogFunctionRepository functionRepo;
  @Autowired private CatalogVersionRepository versionRepo;
  @Autowired private OrganizationRepository orgRepo;

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  private static final ObjectMapper JSON = new ObjectMapper();

  // Con la planilla nueva de Agesic (formato 2025) y el modelo de madurez alineado
  // (EvaluationService.calculateMaturity puntúa sobre TODOS los controles del marco ubicados en
  // la subcategoría), PRISMA reproduce la planilla en las 103 subcategorías. El test falla ante
  // CUALQUIER diferencia.
  private static final Set<String> KNOWN_DIFFS = Set.of();

  @BeforeEach
  void mockGlobalUser() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.isGlobalRole()).thenReturn(true);
  }

  @Test
  void maturityMatchesAgesicSpreadsheet() throws Exception {
    Map<String, UUID> controlIdByCode =
        controlRepo.findByVersionOrdered(VERSION).stream()
            .collect(Collectors.toMap(CatalogControl::getCode, CatalogControl::getId));
    Map<UUID, String> subcatCodeById = subcategoryCodesByVersion(VERSION);

    Organization org =
        orgRepo.save(
            Organization.builder().name("Validación MCU").rut("NIT-VAL").sector("GOV").build());
    EvaluationDto eval =
        evaluationService.create(
            new CreateEvaluationDto("Validación Básico", org.getId(), VERSION, BASICO_PROFILE_ID));

    JsonNode expected = JSON.readTree(resource("mcu50/expected_basico.json"));
    JsonNode answers = expected.get("answers");
    List<String> missing = new ArrayList<>();
    answers
        .fieldNames()
        .forEachRemaining(
            code -> {
              UUID id = controlIdByCode.get(code);
              if (id == null) {
                missing.add(code);
              } else {
                evaluationService.saveResponse(
                    eval.id(), new SaveResponseDto(id, answers.get(code).asBoolean(), null));
              }
            });
    assertTrue(missing.isEmpty(), "controles del perfil ausentes en el catálogo 5.0: " + missing);

    evaluationService.calculateMaturity(eval.id());

    // ---- nivel por subcategoría ----
    Map<String, Integer> actualBySubcat = new HashMap<>();
    for (MaturityResultDto r : evaluationService.getResults(eval.id())) {
      actualBySubcat.put(subcatCodeById.get(r.subcategoryId()), r.currentLevel());
    }

    JsonNode expBySubcat = expected.get("subcategory");
    List<String> unexpected = new ArrayList<>();
    Map<String, int[]> allDiffs = new TreeMap<>();
    Map<String, List<Integer>> byFunction = new TreeMap<>();
    List<Integer> all = new ArrayList<>();
    expBySubcat
        .fieldNames()
        .forEachRemaining(
            code -> {
              int exp = (int) Math.round(expBySubcat.get(code).asDouble());
              int act = actualBySubcat.getOrDefault(code, 0);
              if (exp != act) {
                allDiffs.put(code, new int[] {act, exp});
                if (!KNOWN_DIFFS.contains(code)) {
                  unexpected.add(code + " (PRISMA=" + act + ", planilla=" + exp + ")");
                }
              }
              byFunction.computeIfAbsent(code.substring(0, 2), k -> new ArrayList<>()).add(act);
              all.add(act);
            });

    JsonNode expFunc = expected.get("function");
    StringBuilder report = new StringBuilder("\nMadurez por función (PRISMA vs planilla):\n");
    for (var e : byFunction.entrySet()) {
      double got = e.getValue().stream().mapToInt(i -> i).average().orElse(0);
      String key =
          e.getKey().equals("RS") ? "RE" : e.getKey(); // la planilla llama "RE" a RESPONDER
      double want = expFunc.has(key) ? expFunc.get(key).asDouble() : Double.NaN;
      report.append(String.format("  %s: %.2f vs %.2f%n", e.getKey(), got, want));
    }
    double generalGot = all.stream().mapToInt(i -> i).average().orElse(0);
    report.append(
        String.format("General: %.3f vs %.3f%n", generalGot, expected.get("general").asDouble()));
    report
        .append("Diferencias por subcategoría (PRISMA, planilla): ")
        .append(
            allDiffs.entrySet().stream()
                .map(x -> x.getKey() + "=" + x.getValue()[0] + "/" + x.getValue()[1])
                .collect(Collectors.joining(", ")));
    System.out.println(report);

    assertTrue(
        unexpected.isEmpty(),
        "subcategorías con diferencia contra la planilla: " + unexpected + report);
    assertEquals(103, expBySubcat.size(), "la planilla tiene 103 subcategorías");
    assertTrue(
        Math.abs(generalGot - expected.get("general").asDouble()) <= 0.02,
        "madurez general fuera de tolerancia" + report);
  }

  private Map<UUID, String> subcategoryCodesByVersion(String version) {
    UUID versionId = versionRepo.findByVersion(version).orElseThrow().getId();
    Map<UUID, String> out = new HashMap<>();
    functionRepo
        .findByVersionIdOrderBySortOrder(versionId)
        .forEach(
            f ->
                f.getCategories()
                    .forEach(
                        c -> c.getSubcategories().forEach(s -> out.put(s.getId(), s.getCode()))));
    return out;
  }

  private InputStream resource(String path) {
    return Objects.requireNonNull(
        getClass().getClassLoader().getResourceAsStream(path), "falta el recurso de test: " + path);
  }
}
