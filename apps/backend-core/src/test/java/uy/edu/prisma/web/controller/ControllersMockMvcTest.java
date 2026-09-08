package uy.edu.prisma.web.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uy.edu.prisma.application.*;
import uy.edu.prisma.web.dto.Dto.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControllersMockMvcTest {

  @Mock private CatalogService catalogService;
  @Mock private CommunityProfileService communityProfileService;
  @Mock private DashboardService dashboardService;
  @Mock private EvaluationService evaluationService;
  @Mock private OrganizationService organizationService;
  @Mock private UserService userService;
  @Mock private EvidenceService evidenceService;
  @Mock private AuditObservationService auditObservationService;
  @Mock private ImprovementPlanService improvementPlanService;
  @Mock private ReportService reportService;
  @Mock private EmailSettingsService emailSettingsService;
  @Mock private AccountService accountService;

  private MockMvc catalog;
  private MockMvc communityProfiles;
  private MockMvc dashboard;
  private MockMvc evaluations;
  private MockMvc organizations;
  private MockMvc users;
  private MockMvc evidence;
  private MockMvc audit;
  private MockMvc improvement;
  private MockMvc reports;
  private MockMvc emailSettings;
  private MockMvc account;

  @BeforeEach
  void setUp() {
    catalog = MockMvcBuilders.standaloneSetup(new CatalogController(catalogService)).build();
    communityProfiles =
        MockMvcBuilders.standaloneSetup(new CommunityProfileController(communityProfileService))
            .build();
    dashboard = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService)).build();
    evaluations =
        MockMvcBuilders.standaloneSetup(new EvaluationController(evaluationService)).build();
    organizations =
        MockMvcBuilders.standaloneSetup(new OrganizationController(organizationService)).build();
    users = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
    evidence = MockMvcBuilders.standaloneSetup(new EvidenceController(evidenceService)).build();
    audit =
        MockMvcBuilders.standaloneSetup(new AuditObservationController(auditObservationService))
            .build();
    improvement =
        MockMvcBuilders.standaloneSetup(new ImprovementPlanController(improvementPlanService))
            .build();
    reports = MockMvcBuilders.standaloneSetup(new ReportsController(reportService)).build();
    emailSettings =
        MockMvcBuilders.standaloneSetup(new EmailSettingsController(emailSettingsService)).build();
    account = MockMvcBuilders.standaloneSetup(new AccountController(accountService)).build();
  }

  // ---------------- Catalog ----------------

  @Test
  void catalogVersions() throws Exception {
    when(catalogService.listVersions())
        .thenReturn(List.of(new CatalogVersionDto("5.0", "MCU 5.0")));

    catalog
        .perform(get("/api/catalog/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versions[0]").value("5.0"));
  }

  @Test
  void catalogByVersion() throws Exception {
    when(catalogService.getByVersion("5.0", null))
        .thenReturn(Map.of("version", "5.0", "functions", List.of()));

    catalog
        .perform(get("/api/catalog/5.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("5.0"));
  }

  @Test
  void catalogByVersionWithProfile() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(catalogService.getByVersion("5.0", profileId))
        .thenReturn(Map.of("version", "5.0", "functions", List.of()));

    catalog
        .perform(get("/api/catalog/5.0").param("profileId", profileId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("5.0"));
  }

  @Test
  void catalogControlsFlat() throws Exception {
    when(catalogService.listControlsFlat("5.0"))
        .thenReturn(
            List.of(
                new CatalogControlFlatDto(
                    UUID.randomUUID(), "AD.1-1", "Desc", 2, "AD.1", "Req AD.1", "AD")));

    catalog
        .perform(get("/api/catalog/5.0/controls"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("AD.1-1"))
        .andExpect(jsonPath("$[0].domain").value("AD"))
        .andExpect(jsonPath("$[0].requirementCode").value("AD.1"));
  }

  @Test
  void catalogImport() throws Exception {
    when(catalogService.importCatalog(any())).thenReturn(new CatalogVersionDto("6.0", "MCU 6.0"));

    catalog
        .perform(
            post("/api/catalog/import")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"version\":\"6.0\",\"label\":\"MCU 6.0\",\"functions\":[{\"code\":\"F1\","
                        + "\"name\":\"Funcion\",\"categories\":[{\"code\":\"C1\",\"name\":\"Cat\","
                        + "\"subcategories\":[{\"code\":\"SC1\",\"name\":\"Sub\",\"requirements\":"
                        + "[{\"code\":\"R1\",\"description\":\"Desc\",\"controls\":[{\"code\":"
                        + "\"CT1\",\"description\":\"Desc\",\"targetLevel\":1}]}]}]}]}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("6.0"));
  }

  @Test
  void catalogDeleteVersion() throws Exception {
    catalog.perform(delete("/api/catalog/6.0")).andExpect(status().isNoContent());
    verify(catalogService, times(1)).deleteVersion("6.0");
  }

  // ---------------- Community Profiles ----------------

  @Test
  void communityProfilesList() throws Exception {
    when(communityProfileService.listByVersion("5.0"))
        .thenReturn(
            List.of(new CommunityProfileSummaryDto(UUID.randomUUID(), "PYME", "desc", "5.0", 6)));

    communityProfiles
        .perform(get("/api/community-profiles").param("catalogVersion", "5.0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("PYME"))
        .andExpect(jsonPath("$[0].controlCount").value(6));
  }

  @Test
  void communityProfileGetById() throws Exception {
    UUID id = UUID.randomUUID();
    when(communityProfileService.getById(id))
        .thenReturn(
            new CommunityProfileDto(
                id, "PYME", "desc", "5.0", java.util.Set.of(UUID.randomUUID()), null));

    communityProfiles
        .perform(get("/api/community-profiles/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("PYME"));
  }

  @Test
  void communityProfileCreate() throws Exception {
    UUID id = UUID.randomUUID();
    UUID controlId = UUID.randomUUID();
    when(communityProfileService.create(any(CreateCommunityProfileDto.class)))
        .thenReturn(
            new CommunityProfileDto(id, "PYME", "desc", "5.0", java.util.Set.of(controlId), null));

    communityProfiles
        .perform(
            post("/api/community-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"PYME\",\"description\":\"desc\",\"catalogVersion\":\"5.0\",\"controlIds\":[\""
                        + controlId
                        + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("PYME"));
  }

  @Test
  void communityProfileDelete() throws Exception {
    UUID id = UUID.randomUUID();

    communityProfiles
        .perform(delete("/api/community-profiles/{id}", id))
        .andExpect(status().isNoContent());
    verify(communityProfileService, times(1)).delete(id);
  }

  // ---------------- Dashboard ----------------

  @Test
  void dashboardStats() throws Exception {
    DashboardStatsDto stats =
        new DashboardStatsDto(
            1L, 1L, 3.0, 0L, Map.of("DRAFT", 1L), List.of(new MaturityByFunctionDto("R", 3)));
    when(dashboardService.getStats()).thenReturn(stats);

    dashboard
        .perform(get("/api/dashboard/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalEvaluations").value(1))
        .andExpect(jsonPath("$.avgMaturityLevel").value(3.0));
  }

  // ---------------- Evaluations ----------------

  @Test
  void evaluationList() throws Exception {
    when(evaluationService.list(any(), any(), anyInt(), anyInt()))
        .thenReturn(new PaginatedDto<>(List.of(), 0L, 0, 10));

    evaluations
        .perform(get("/api/evaluations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void evaluationGetById() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationService.getById(id))
        .thenReturn(
            new EvaluationDto(
                id, "E", null, null, "5.0", null, null, "DRAFT", null, null, null, null));

    evaluations
        .perform(get("/api/evaluations/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("E"));
  }

  @Test
  void evaluationCreate() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationService.create(any(CreateEvaluationDto.class)))
        .thenReturn(
            new EvaluationDto(
                id,
                "Nueva",
                UUID.randomUUID(),
                "Acme",
                "5.0",
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                null));

    evaluations
        .perform(
            post("/api/evaluations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Nueva\",\"organizationId\":\"00000000-0000-0000-0000-000000000001\",\"catalogVersion\":\"5.0\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Nueva"));
  }

  @Test
  void evaluationUpdateStatus() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationService.updateStatus(eq(id), eq("APPROVED")))
        .thenReturn(
            new EvaluationDto(
                id, "E", null, null, "5.0", null, null, "APPROVED", null, null, null, null));

    evaluations
        .perform(
            patch("/api/evaluations/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void evaluationDelete() throws Exception {
    UUID id = UUID.randomUUID();

    evaluations.perform(delete("/api/evaluations/{id}", id)).andExpect(status().isNoContent());
    verify(evaluationService, times(1)).delete(id);
  }

  @Test
  void evaluationGetResponses() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationService.getResponses(id)).thenReturn(List.of());

    evaluations.perform(get("/api/evaluations/{id}/responses", id)).andExpect(status().isOk());
  }

  @Test
  void evaluationSaveResponse() throws Exception {
    UUID id = UUID.randomUUID();
    UUID controlId = UUID.randomUUID();
    when(evaluationService.saveResponse(eq(id), any(SaveResponseDto.class)))
        .thenReturn(
            new EvaluationResponseDto(UUID.randomUUID(), id, controlId, true, "obs", null, null));

    evaluations
        .perform(
            post("/api/evaluations/{id}/responses", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"controlId\":\""
                        + controlId
                        + "\",\"compliant\":true,\"observations\":\"obs\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.compliant").value(true));
  }

  @Test
  void evaluationGetResults() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationService.getResults(id))
        .thenReturn(
            List.of(
                new MaturityResultDto(
                    UUID.randomUUID(),
                    "F",
                    UUID.randomUUID(),
                    "C",
                    UUID.randomUUID(),
                    "S",
                    2,
                    4,
                    2)));

    evaluations
        .perform(get("/api/evaluations/{id}/results", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gap").value(2));
  }

  @Test
  void evaluationCalculate() throws Exception {
    UUID id = UUID.randomUUID();

    evaluations.perform(post("/api/evaluations/{id}/calculate", id)).andExpect(status().isOk());
    verify(evaluationService, times(1)).calculateMaturity(id);
  }

  // ---------------- Organizations ----------------

  @Test
  void organizationList() throws Exception {
    when(organizationService.list(any(), anyInt(), anyInt()))
        .thenReturn(new PaginatedDto<>(List.of(), 0L, 0, 10));

    organizations
        .perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void organizationGetById() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.getById(id))
        .thenReturn(new OrganizationDto(id, "Acme", "NIT-1", "TECH", "SMALL", null, true, null));

    organizations
        .perform(get("/api/organizations/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  void organizationCreate() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.create(any(CreateOrganizationDto.class)))
        .thenReturn(new OrganizationDto(id, "NewCo", "NIT-9", "FIN", "LARGE", null, true, null));

    organizations
        .perform(
            post("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"NewCo\",\"rut\":\"NIT-9\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("NewCo"));
  }

  @Test
  void organizationUpdate() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.update(eq(id), any(CreateOrganizationDto.class)))
        .thenReturn(
            new OrganizationDto(id, "Renamed", "NIT-X", "HEALTH", "MEDIUM", null, true, null));

    organizations
        .perform(
            put("/api/organizations/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"rut\":\"NIT-X\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void organizationDelete() throws Exception {
    UUID id = UUID.randomUUID();

    organizations.perform(delete("/api/organizations/{id}", id)).andExpect(status().isNoContent());
    verify(organizationService, times(1)).delete(id);
  }

  // ---------------- Users ----------------

  @Test
  void userList() throws Exception {
    when(userService.list(any(), anyInt(), anyInt()))
        .thenReturn(new PaginatedDto<>(List.of(), 0L, 0, 10));

    users
        .perform(get("/api/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void userGetById() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.getById(id))
        .thenReturn(
            new UserDto(
                id,
                "u@test.com",
                "Jane",
                "Doe",
                null,
                java.util.Set.of("VIEWER"),
                true,
                null,
                true));

    users
        .perform(get("/api/users/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("u@test.com"));
  }

  @Test
  void userCreate() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.create(any(CreateUserDto.class)))
        .thenReturn(
            new UserDto(
                id, "n@test.com", "N", "L", null, java.util.Set.of("AUDITOR"), true, null, true));

    users
        .perform(
            post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"n@test.com\",\"firstName\":\"N\",\"lastName\":\"L\",\"roles\":[\"AUDITOR\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("n@test.com"));
  }

  @Test
  void userUpdate() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.update(eq(id), any(CreateUserDto.class)))
        .thenReturn(
            new UserDto(
                id, "up@test.com", "Nuevo", "L", null, java.util.Set.of(), true, null, true));

    users
        .perform(
            put("/api/users/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"up@test.com\",\"firstName\":\"Nuevo\",\"lastName\":\"L\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstName").value("Nuevo"));
  }

  @Test
  void userDelete() throws Exception {
    UUID id = UUID.randomUUID();

    users.perform(delete("/api/users/{id}", id)).andExpect(status().isNoContent());
    verify(userService, times(1)).delete(id);
  }

  // ---------------- Evidence ----------------

  @Test
  void evidenceListByEvaluation() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(evidenceService.listByEvaluation(evalId)).thenReturn(List.of());

    evidence
        .perform(get("/api/evidence/evaluation/{evaluationId}", evalId))
        .andExpect(status().isOk());
  }

  @Test
  void evidenceUpload() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(evidenceService.upload(eq(evalId), any(), any(), any()))
        .thenReturn(new UploadEvidenceResponseDto(UUID.randomUUID(), "doc.pdf", 10L));

    evidence
        .perform(
            multipart("/api/evidence")
                .file(new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[] {1}))
                .param("evaluationId", evalId.toString())
                .param("description", "evidencia"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileName").value("doc.pdf"));
  }

  @Test
  void evidenceDelete() throws Exception {
    UUID id = UUID.randomUUID();

    evidence.perform(delete("/api/evidence/{id}", id)).andExpect(status().isNoContent());
    verify(evidenceService, times(1)).delete(id);
  }

  @Test
  void evidenceDownload() throws Exception {
    UUID id = UUID.randomUUID();
    when(evidenceService.generateDownloadUrl(id)).thenReturn(new DownloadUrlDto("http://signed"));

    evidence
        .perform(get("/api/evidence/{id}/download", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("http://signed"));
  }

  @Test
  void evidenceReindex() throws Exception {
    UUID id = UUID.randomUUID();
    when(evidenceService.reindex(id))
        .thenReturn(
            new EvidenceDto(
                id,
                UUID.randomUUID(),
                null,
                "doc.pdf",
                10L,
                "application/pdf",
                null,
                null,
                null,
                null,
                true));

    evidence
        .perform(post("/api/evidence/{id}/index", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiIndexed").value(true));
  }

  @Test
  void evidenceCitations() throws Exception {
    UUID evalId = UUID.randomUUID();
    UUID controlId = UUID.randomUUID();
    when(evidenceService.getCitations(evalId, controlId))
        .thenReturn(
            List.of(
                new EvidenceCitationDto(
                    UUID.randomUUID(), "politica.pdf", "Página 2", "texto relevante", 0.87)));

    evidence
        .perform(
            get(
                "/api/evidence/evaluation/{evaluationId}/control/{controlId}/citations",
                evalId,
                controlId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].fileName").value("politica.pdf"))
        .andExpect(jsonPath("$[0].location").value("Página 2"));
  }

  // ---------------- Audit observations ----------------

  @Test
  void auditListByEvaluation() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(auditObservationService.listByEvaluation(evalId)).thenReturn(List.of());

    audit.perform(get("/api/audit/observations/{evaluationId}", evalId)).andExpect(status().isOk());
  }

  @Test
  void auditCreate() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(auditObservationService.create(any()))
        .thenReturn(
            new AuditObservationDto(
                UUID.randomUUID(), evalId, null, "OBSERVATION", "obs", "OPEN", null, null));

    audit
        .perform(
            post("/api/audit/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"evaluationId\":\"" + evalId + "\",\"description\":\"obs\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void auditUpdate() throws Exception {
    UUID id = UUID.randomUUID();
    when(auditObservationService.update(eq(id), any()))
        .thenReturn(
            new AuditObservationDto(
                id, UUID.randomUUID(), null, "OBSERVATION", "nueva", "CLOSED", null, null));

    audit
        .perform(
            put("/api/audit/observations/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CLOSED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("nueva"));
  }

  // ---------------- Improvement plans ----------------

  @Test
  void improvementListByEvaluation() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(improvementPlanService.listByEvaluation(evalId)).thenReturn(List.of());

    improvement.perform(get("/api/improvement/{evaluationId}", evalId)).andExpect(status().isOk());
  }

  @Test
  void improvementSuggestionTips() throws Exception {
    when(improvementPlanService.tips(any()))
        .thenReturn(new RemediationTipsDto("Resumen", List.of("Paso 1", "Paso 2")));

    improvement
        .perform(
            post("/api/improvement/suggestions/tips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"controlCode\":\"PR.AC-1\",\"description\":\"desc\","
                        + "\"currentLevel\":0,\"targetLevel\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary").value("Resumen"))
        .andExpect(jsonPath("$.tips[0]").value("Paso 1"));
  }

  @Test
  void improvementCreate() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(improvementPlanService.create(any()))
        .thenReturn(
            new ImprovementPlanDto(
                UUID.randomUUID(),
                evalId,
                null,
                "Action",
                "R",
                "HIGH",
                "PENDING",
                "2026-12-01",
                10L,
                null));

    improvement
        .perform(
            post("/api/improvement")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"evaluationId\":\"" + evalId + "\",\"action\":\"Action\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.action").value("Action"));
  }

  @Test
  void improvementUpdate() throws Exception {
    UUID id = UUID.randomUUID();
    when(improvementPlanService.update(eq(id), any()))
        .thenReturn(
            new ImprovementPlanDto(
                id,
                UUID.randomUUID(),
                null,
                "Nueva",
                null,
                "MEDIUM",
                "IN_PROGRESS",
                null,
                null,
                null));

    improvement
        .perform(
            put("/api/improvement/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
  }

  // ---------------- Reports ----------------

  @Test
  void reportsPdf() throws Exception {
    UUID id = UUID.randomUUID();
    when(reportService.generatePdf(id)).thenReturn("%PDF-1.4".getBytes());

    reports
        .perform(get("/api/reports/{id}/pdf", id))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF));
  }

  @Test
  void reportsExcel() throws Exception {
    UUID id = UUID.randomUUID();
    when(reportService.generateExcel(id)).thenReturn("PK".getBytes());

    reports
        .perform(get("/api/reports/{id}/excel", id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  // ---------------- Email settings (SMTP de Keycloak) ----------------

  @Test
  void emailSettingsGet() throws Exception {
    when(emailSettingsService.get())
        .thenReturn(
            new EmailSettingsDto(
                "smtp.example.com",
                587,
                "no-reply@prisma.local",
                "PRISMA",
                true,
                "user",
                true,
                false,
                true));

    emailSettings
        .perform(get("/api/admin/email-settings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.host").value("smtp.example.com"))
        .andExpect(jsonPath("$.configured").value(true));
  }

  @Test
  void emailSettingsUpdate() throws Exception {
    when(emailSettingsService.get())
        .thenReturn(
            new EmailSettingsDto(
                "mailhog", 1025, "no-reply@prisma.local", null, false, null, false, false, true));

    emailSettings
        .perform(
            put("/api/admin/email-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"host\":\"mailhog\",\"port\":1025,\"from\":\"no-reply@prisma.local\","
                        + "\"authEnabled\":false,\"starttls\":false,\"ssl\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.host").value("mailhog"));

    verify(emailSettingsService, times(1)).update(any(UpdateEmailSettingsDto.class));
  }

  // ---------------- Account (autoservicio) ----------------

  @Test
  void accountChangePassword() throws Exception {
    account
        .perform(
            put("/api/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-password-123\"}"))
        .andExpect(status().isNoContent());

    verify(accountService, times(1)).changePassword(any());
  }
}
