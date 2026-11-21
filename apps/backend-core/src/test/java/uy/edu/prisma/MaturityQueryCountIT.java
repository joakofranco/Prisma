package uy.edu.prisma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uy.edu.prisma.application.CurrentUserService;
import uy.edu.prisma.application.EvaluationOverviewService;
import uy.edu.prisma.application.EvaluationService;
import uy.edu.prisma.domain.entity.Organization;
import uy.edu.prisma.domain.repository.OrganizationRepository;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;
import uy.edu.prisma.web.dto.Dto.CreateEvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationDto;
import uy.edu.prisma.web.dto.Dto.MaturityResultDto;
import uy.edu.prisma.web.dto.Dto.SaveResponseDto;

@Testcontainers
@SpringBootTest(
    classes = PrismaApplication.class,
    properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class MaturityQueryCountIT {

  private static final long MAX_STATEMENTS_CALCULATE = 30;
  private static final long MAX_STATEMENTS_RESULTS = 20;
  private static final long MAX_STATEMENTS_OVERVIEW = 25;

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

  @Autowired private EvaluationService evaluationService;
  @Autowired private EvaluationOverviewService overviewService;
  @Autowired private OrganizationRepository orgRepo;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @MockitoBean private CurrentUserService currentUser;
  @MockitoBean private KeycloakAdminClient keycloakAdmin;

  @BeforeEach
  void mockGlobalUser() {
    when(currentUser.isPrismaAdmin()).thenReturn(true);
    when(currentUser.isGlobalRole()).thenReturn(true);
    when(currentUser.hasAllOrganizationsAccess()).thenReturn(true);
  }

  @Test
  void maturityEndpointsDoNotIssueOneQueryPerControl() {
    Organization org =
        orgRepo.save(
            Organization.builder()
                .name("QueryCount")
                .identifier("NIT-QUERYCOUNT")
                .sector("TECH")
                .build());
    EvaluationDto created =
        evaluationService.create(new CreateEvaluationDto("Conteo", org.getId(), "5.0", null));
    var controlId =
        overviewService
            .getOverview(created.id())
            .functions()
            .get(0)
            .categories()
            .get(0)
            .subcategories()
            .get(0)
            .controls()
            .get(0)
            .id();
    evaluationService.saveResponse(created.id(), new SaveResponseDto(controlId, true, "ok"));

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

    stats.clear();
    evaluationService.calculateMaturity(created.id());
    long calculate = stats.getPrepareStatementCount();

    stats.clear();
    List<MaturityResultDto> results = evaluationService.getResults(created.id());
    long resultsCount = stats.getPrepareStatementCount();

    stats.clear();
    overviewService.getOverview(created.id());
    long overview = stats.getPrepareStatementCount();

    System.out.println(
        "QUERYCOUNT calculate=" + calculate + " results=" + resultsCount + " overview=" + overview);
    assertEquals(103, results.size());
    assertTrue(calculate <= MAX_STATEMENTS_CALCULATE, "calculateMaturity: " + calculate);
    assertTrue(resultsCount <= MAX_STATEMENTS_RESULTS, "getResults: " + resultsCount);
    assertTrue(overview <= MAX_STATEMENTS_OVERVIEW, "getOverview: " + overview);
  }
}
