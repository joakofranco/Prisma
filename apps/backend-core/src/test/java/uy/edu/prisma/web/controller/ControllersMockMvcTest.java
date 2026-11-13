package uy.edu.prisma.web.controller;

import static org.junit.jupiter.api.Assertions.*;
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
  @Mock private EvaluationOverviewService evaluationOverviewService;
  @Mock private OrganizationService organizationService;
  @Mock private UserService userService;
  @Mock private EvidenceService evidenceService;
  @Mock private AuditObservationService auditObservationService;
  @Mock private AuditVerdictService auditVerdictService;
  @Mock private EvaluationFlagService evaluationFlagService;
  @Mock private ImprovementPlanService improvementPlanService;
  @Mock private AiCitationFeedbackService aiCitationFeedbackService;
  @Mock private ReportService reportService;
  @Mock private uy.edu.prisma.application.TechnicalReportService technicalReportService;
  @Mock private EmailSettingsService emailSettingsService;
  @Mock private PlanDeadlineSettingsService planDeadlineSettingsService;
  @Mock private AccountService accountService;
  @Mock private AuditLogService auditLogService;

  private MockMvc catalog;
  private MockMvc communityProfiles;
  private MockMvc dashboard;
  private MockMvc evaluations;
  private MockMvc evaluationOverview;
  private MockMvc organizations;
  private MockMvc users;
  private MockMvc evidence;
  private MockMvc citationFeedback;
  private MockMvc audit;
  private MockMvc verdicts;
  private MockMvc flags;
  private MockMvc improvement;
  private MockMvc reports;
  private MockMvc emailSettings;
  private MockMvc planDeadlines;
  private MockMvc account;
  private MockMvc auditLogs;

  @BeforeEach
  void setUp() {
    catalog = MockMvcBuilders.standaloneSetup(new CatalogController(catalogService)).build();
    communityProfiles =
        MockMvcBuilders.standaloneSetup(new CommunityProfileController(communityProfileService))
            .build();
    dashboard = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService)).build();
    evaluations =
        MockMvcBuilders.standaloneSetup(new EvaluationController(evaluationService)).build();
    evaluationOverview =
        MockMvcBuilders.standaloneSetup(new EvaluationOverviewController(evaluationOverviewService))
            .build();
    organizations =
        MockMvcBuilders.standaloneSetup(new OrganizationController(organizationService)).build();
    users = MockMvcBuilders.standaloneSetup(new UserController(userService)).build();
    evidence = MockMvcBuilders.standaloneSetup(new EvidenceController(evidenceService)).build();
    citationFeedback =
        MockMvcBuilders.standaloneSetup(new CitationFeedbackController(aiCitationFeedbackService))
            .build();
    audit =
        MockMvcBuilders.standaloneSetup(new AuditObservationController(auditObservationService))
            .build();
    flags =
        MockMvcBuilders.standaloneSetup(new EvaluationFlagController(evaluationFlagService))
            .build();
    verdicts =
        MockMvcBuilders.standaloneSetup(new AuditVerdictController(auditVerdictService)).build();
    improvement =
        MockMvcBuilders.standaloneSetup(new ImprovementPlanController(improvementPlanService))
            .build();
    reports =
        MockMvcBuilders.standaloneSetup(
                new ReportsController(reportService, technicalReportService))
            .build();
    emailSettings =
        MockMvcBuilders.standaloneSetup(new EmailSettingsController(emailSettingsService)).build();
    planDeadlines =
        MockMvcBuilders.standaloneSetup(
                new PlanDeadlineSettingsController(planDeadlineSettingsService))
            .build();
    account = MockMvcBuilders.standaloneSetup(new AccountController(accountService)).build();
    auditLogs = MockMvcBuilders.standaloneSetup(new AuditLogController(auditLogService)).build();
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
  void evaluationDeletableReturnsTheIds() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    when(evaluationService.deletableIds(anyCollection())).thenReturn(java.util.List.of(a));

    evaluations
        .perform(get("/api/evaluations/deletable").param("ids", a + "," + b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(a.toString()))
        .andExpect(jsonPath("$.length()").value(1));
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
  void evaluationOverview() throws Exception {
    UUID id = UUID.randomUUID();
    when(evaluationOverviewService.getOverview(id))
        .thenReturn(
            new EvaluationOverviewDto(
                id,
                "Eva",
                "5.0",
                "MCU 5.0",
                null,
                null,
                0,
                0,
                0,
                new OverviewPriorityCountsDto(0, 0, 0, 0),
                List.of()));

    evaluationOverview
        .perform(get("/api/evaluations/{id}/overview", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.evaluationName").value("Eva"))
        .andExpect(jsonPath("$.catalogVersion").value("5.0"));
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
        .thenReturn(
            new OrganizationDto(
                id, "Acme", "NIT-1", false, "TECH", "SMALL", null, null, true, null));

    organizations
        .perform(get("/api/organizations/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Acme"));
  }

  @Test
  void organizationCreate() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.create(any(CreateOrganizationDto.class)))
        .thenReturn(
            new OrganizationDto(
                id, "NewCo", "NIT-9", false, "FIN", "LARGE", null, null, true, null));

    organizations
        .perform(
            post("/api/organizations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"NewCo\",\"identifier\":\"NIT-0009\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("NewCo"));
  }

  @Test
  void organizationUpdate() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.update(eq(id), any(CreateOrganizationDto.class)))
        .thenReturn(
            new OrganizationDto(
                id, "Renamed", "NIT-X", false, "HEALTH", "MEDIUM", null, null, true, null));

    organizations
        .perform(
            put("/api/organizations/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"identifier\":\"NIT-XXXX\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"));
  }

  @Test
  void organizationEnable() throws Exception {
    UUID id = UUID.randomUUID();
    when(organizationService.enable(id))
        .thenReturn(
            new OrganizationReactivationDto(
                new OrganizationDto(
                    id, "Acme", "NIT-1", false, "TECH", "SMALL", null, null, true, null),
                3));

    organizations
        .perform(put("/api/organizations/{id}/enable", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.organization.enabled").value(true))
        .andExpect(jsonPath("$.disabledUsers").value(3));
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
                    "{\"email\":\"n@test.com\",\"firstName\":\"Nora\",\"lastName\":\"Lopez\",\"roles\":[\"AUDITOR\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("n@test.com"));
  }

  @Test
  void userInvite() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.invite(any(InviteUserDto.class)))
        .thenReturn(
            new UserDto(
                id,
                "l@test.com",
                "Lucía",
                "Fernández",
                java.util.Set.of(),
                List.of(),
                java.util.Set.of("INTERNAL_EVALUATOR"),
                true,
                null,
                true,
                java.time.OffsetDateTime.now(),
                null));

    users
        .perform(
            post("/api/users/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"l@test.com\",\"firstName\":\"Lucía\",\"lastName\":\"Fernández\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invitedAt").exists())
        .andExpect(jsonPath("$.roles[0]").value("INTERNAL_EVALUATOR"));
  }

  @Test
  void userInviteValidatesTheEmail() throws Exception {
    users
        .perform(
            post("/api/users/invitations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"no-es-email\",\"firstName\":\"Lucía\",\"lastName\":\"F\"}"))
        .andExpect(status().isBadRequest());
    verify(userService, never()).invite(any());
  }

  @Test
  void userResendInvitation() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.resendInvitation(id))
        .thenReturn(
            new UserDto(id, "l@test.com", "L", "F", null, java.util.Set.of(), true, null, true));

    users
        .perform(post("/api/users/{id}/invitation/resend", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("l@test.com"));
  }

  @Test
  void userInvitationAvailability() throws Exception {
    when(userService.canSendInvitations()).thenReturn(false);

    users
        .perform(get("/api/users/invitations/availability"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailConfigured").value(false));
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
                .content(
                    "{\"email\":\"up@test.com\",\"firstName\":\"Nuevo\",\"lastName\":\"Lopez\"}"))
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
        .thenReturn(new UploadEvidenceResponseDto(UUID.randomUUID(), "doc.pdf", 10L, "CLEAN"));

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
  void evidenceDownloadStreamsTheFile() throws Exception {
    UUID id = UUID.randomUUID();
    when(evidenceService.download(id))
        .thenReturn(
            new EvidenceService.EvidenceFile(
                "acta.pdf",
                "application/pdf",
                3L,
                new java.io.ByteArrayInputStream(new byte[] {1, 2, 3})));

    evidence
        .perform(get("/api/evidence/{id}/download", id))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("acta.pdf")))
        .andExpect(content().contentType("application/pdf"))
        .andExpect(content().bytes(new byte[] {1, 2, 3}));
  }

  @Test
  void evidenceDownloadIsRestrictedToAdminOrgResponsibleAndAuditor() throws Exception {
    var method = EvidenceController.class.getMethod("download", UUID.class);
    var annotation =
        method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

    assertNotNull(annotation);
    assertEquals("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')", annotation.value());
  }

  @Test
  void verdictListAndSet() throws Exception {
    UUID evalId = UUID.randomUUID();
    UUID controlId = UUID.randomUUID();
    when(auditVerdictService.listByEvaluation(evalId)).thenReturn(List.of());
    when(auditVerdictService.set(evalId, controlId, "VALIDATED"))
        .thenReturn(new ControlVerdictDto(controlId, "VALIDATED", null, null, null));

    verdicts.perform(get("/api/audit/verdicts/{id}", evalId)).andExpect(status().isOk());
    verdicts
        .perform(
            put("/api/audit/verdicts/{e}/controls/{c}", evalId, controlId)
                .contentType("application/json")
                .content("{\"verdict\":\"VALIDATED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verdict").value("VALIDATED"));
    verdicts
        .perform(
            put("/api/audit/verdicts/{e}/controls/{c}", evalId, controlId)
                .contentType("application/json")
                .content("{\"verdict\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void verdictEndpointsAreRestrictedToAdminAndAuditor() throws Exception {
    for (String name : List.of("list", "set")) {
      var method =
          java.util.Arrays.stream(AuditVerdictController.class.getMethods())
              .filter(m -> m.getName().equals(name))
              .findFirst()
              .orElseThrow();
      var annotation =
          method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, name);
      assertEquals("hasAnyRole('PRISMA_ADMIN','AUDITOR')", annotation.value());
    }
  }

  @Test
  void flagListFlagAndUnflag() throws Exception {
    UUID evalId = UUID.randomUUID();
    UUID controlId = UUID.randomUUID();
    when(evaluationFlagService.list(evalId))
        .thenReturn(List.of(new ControlFlagDto(controlId, null, "Ana", null)));

    flags
        .perform(get("/api/evaluations/{e}/flags", evalId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].controlId").value(controlId.toString()));
    flags
        .perform(put("/api/evaluations/{e}/flags/{c}", evalId, controlId))
        .andExpect(status().isNoContent());
    flags
        .perform(delete("/api/evaluations/{e}/flags/{c}", evalId, controlId))
        .andExpect(status().isNoContent());
    verify(evaluationFlagService).setFlagged(evalId, controlId, true);
    verify(evaluationFlagService).setFlagged(evalId, controlId, false);
  }

  @Test
  void flagEndpointsAreRestrictedToWorkingRoles() throws Exception {
    for (String name : List.of("flag", "unflag")) {
      var method = EvaluationFlagController.class.getMethod(name, UUID.class, UUID.class);
      var annotation =
          method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, name);
      assertEquals(
          "hasAnyRole('PRISMA_ADMIN','INTERNAL_EVALUATOR','ORG_RESPONSIBLE','AUDITOR')",
          annotation.value());
    }
  }

  private static String rolesOf(String method, Class<?>... params) throws Exception {
    return AuditObservationController.class
        .getMethod(method, params)
        .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)
        .value();
  }

  @Test
  void observationsCanBeReadByTheOrganizationButNotByTheViewer() throws Exception {
    String read = rolesOf("listByEvaluation", UUID.class);

    for (String role :
        List.of("PRISMA_ADMIN", "AUDITOR", "ORG_RESPONSIBLE", "INTERNAL_EVALUATOR")) {
      assertTrue(read.contains("'" + role + "'"), role + " debería poder leer");
    }
    assertFalse(read.contains("VIEWER"), "el VIEWER no ve las observaciones");
  }

  @Test
  void observationsCanOnlyBeWrittenByTheAdminAndTheAuditor() throws Exception {
    String create = rolesOf("create", uy.edu.prisma.web.dto.Dto.CreateAuditObservationDto.class);
    String update =
        rolesOf("update", UUID.class, uy.edu.prisma.web.dto.Dto.UpdateAuditObservationDto.class);

    assertEquals("hasAnyRole('PRISMA_ADMIN','AUDITOR')", create);
    assertEquals("hasAnyRole('PRISMA_ADMIN','AUDITOR')", update);
  }

  @Test
  void executiveReportIsServedAsAPdfAttachment() throws Exception {
    UUID id = UUID.randomUUID();
    when(reportService.generateExecutivePdf(id)).thenReturn("%PDF-1.4 ejecutivo".getBytes());

    reports
        .perform(get("/api/reports/{id}/executive/pdf", id))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("reporte-ejecutivo-" + id + ".pdf")));
  }

  @Test
  void executiveReportIsOnlyForAdminAndOrgResponsible() throws Exception {
    // Los mismos roles que el PDF final: el auditor no baja reportes finales.
    var method = ReportsController.class.getMethod("executivePdf", UUID.class);
    var annotation =
        method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

    assertNotNull(annotation);
    assertEquals("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')", annotation.value());
    assertEquals(
        annotation.value(),
        ReportsController.class
            .getMethod("pdf", UUID.class)
            .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class)
            .value());
  }

  @Test
  void improvementPlanExportsAreServedAsAttachments() throws Exception {
    UUID id = UUID.randomUUID();
    when(reportService.generateImprovementPlanPdf(id)).thenReturn("%PDF-1.4 plan".getBytes());
    when(reportService.generateImprovementPlanExcel(id)).thenReturn("PK".getBytes());

    reports
        .perform(get("/api/reports/{id}/improvement-plan/pdf", id))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("plan-mejora-" + id + ".pdf")));
    reports
        .perform(get("/api/reports/{id}/improvement-plan/excel", id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("plan-mejora-" + id + ".xlsx")));
  }

  @Test
  void improvementPlanExportsAllowAdminOrgResponsibleAndAuditor() throws Exception {
    for (String name : List.of("improvementPlanPdf", "improvementPlanExcel")) {
      var annotation =
          ReportsController.class
              .getMethod(name, UUID.class)
              .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, name);
      assertEquals("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE','AUDITOR')", annotation.value());
    }
  }

  @Test
  void improvementAiGuideAndFeedbackAreOnlyForAdminAndOrgResponsible() throws Exception {
    // La guía de IA y su retroalimentación se usan al crear acciones del plan: mismos roles que
    // POST /api/improvement.
    String expected = "hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')";
    for (var name : List.of("suggestionTips", "suggestionFeedback")) {
      var method =
          java.util.Arrays.stream(ImprovementPlanController.class.getMethods())
              .filter(m -> m.getName().equals(name))
              .findFirst()
              .orElseThrow();
      var annotation =
          method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, name);
      assertEquals(expected, annotation.value(), name);
    }
  }

  @Test
  void userUnlockReturnsWhetherTheAccountWasLocked() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.unlock(id)).thenReturn(true);

    users
        .perform(post("/api/users/{id}/unlock", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.wasLocked").value(true));
    verify(userService).unlock(id);
  }

  @Test
  void userUnlockSaysSoWhenThereWasNothingToUnlock() throws Exception {
    UUID id = UUID.randomUUID();
    when(userService.unlock(id)).thenReturn(false);

    users
        .perform(post("/api/users/{id}/unlock", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.wasLocked").value(false));
  }

  @Test
  void userUnlockIsOnlyForThePrismaAdmin() throws Exception {
    // El resto de las acciones sobre usuarios admiten también a ORG_RESPONSIBLE; esta no.
    var method = UserController.class.getMethod("unlock", UUID.class);
    var annotation =
        method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);

    assertNotNull(annotation);
    assertEquals("hasRole('PRISMA_ADMIN')", annotation.value());
  }

  @Test
  void evidenceAllowedFormatsReturnsTheConfiguredList() throws Exception {
    when(evidenceService.allowedExtensions()).thenReturn(List.of("pdf", "zip", "png"));
    when(evidenceService.maxSizeBytes()).thenReturn(52_428_800L);

    evidence
        .perform(get("/api/evidence/allowed-formats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedExtensions[0]").value("pdf"))
        .andExpect(jsonPath("$.allowedExtensions.length()").value(3))
        .andExpect(jsonPath("$.maxSizeBytes").value(52_428_800L));
  }

  @Test
  void evidenceAllowedFormatsIsNotRestrictedToAnyParticularRole() throws Exception {
    // Cualquier usuario autenticado puede ver las reglas (la pantalla las necesita antes de subir):
    // sin @PreAuthorize propio, solo rige el "anyRequest().authenticated()" de SecurityConfig.
    var method = EvidenceController.class.getMethod("allowedFormats");

    assertNull(
        method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class));
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
                true,
                "CLEAN"));

    evidence
        .perform(post("/api/evidence/{id}/index", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiIndexed").value(true));
  }

  @Test
  void citationFeedbackReturnsNoContent() throws Exception {
    citationFeedback
        .perform(
            post("/api/evidence/citations/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"evaluationId\":\""
                        + UUID.randomUUID()
                        + "\",\"items\":[{\"controlId\":\""
                        + UUID.randomUUID()
                        + "\",\"fileName\":\"a.pdf\",\"location\":\"Página 1\","
                        + "\"snippet\":\"texto\",\"score\":0.7,\"decision\":\"ACCEPTED\"}]}"))
        .andExpect(status().isNoContent());
    verify(aiCitationFeedbackService).record(any());
  }

  @Test
  void citationFeedbackRejectsAnInvalidDecisionOrAnEmptyFragment() throws Exception {
    String base =
        "{\"evaluationId\":\""
            + UUID.randomUUID()
            + "\",\"items\":[{\"controlId\":\""
            + UUID.randomUUID()
            + "\",";
    citationFeedback
        .perform(
            post("/api/evidence/citations/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(base + "\"snippet\":\"texto\",\"decision\":\"MAYBE\"}]}"))
        .andExpect(status().isBadRequest());
    citationFeedback
        .perform(
            post("/api/evidence/citations/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(base + "\"snippet\":\"\",\"decision\":\"ACCEPTED\"}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void citationSearchAndFeedbackAreOnlyForAuditorAndAdmin() throws Exception {
    String expected = "hasAnyRole('PRISMA_ADMIN','AUDITOR')";
    var search = EvidenceController.class.getMethod("citations", UUID.class, UUID.class);
    var feedback =
        CitationFeedbackController.class.getMethod(
            "feedback", uy.edu.prisma.web.dto.Dto.CitationFeedbackBatchDto.class);
    for (var method : List.of(search, feedback)) {
      var annotation =
          method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, method.getName());
      assertEquals(expected, annotation.value(), method.getName());
    }
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
  void improvementSuggestionFeedbackReturnsNoContent() throws Exception {
    improvement
        .perform(
            post("/api/improvement/suggestions/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"items\":[{\"controlId\":\""
                        + UUID.randomUUID()
                        + "\",\"currentLevel\":0,\"targetLevel\":1,"
                        + "\"originalSummary\":\"R\",\"originalTips\":[\"a\"],"
                        + "\"outcome\":\"REJECTED\"}]}"))
        .andExpect(status().isNoContent());
    org.mockito.Mockito.verify(improvementPlanService).recordFeedback(any());
  }

  @Test
  void improvementSuggestionFeedbackRejectsInvalidOutcome() throws Exception {
    improvement
        .perform(
            post("/api/improvement/suggestions/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"items\":[{\"controlId\":\""
                        + UUID.randomUUID()
                        + "\",\"currentLevel\":0,\"targetLevel\":1,\"outcome\":\"X\"}]}"))
        .andExpect(status().isBadRequest());
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
                null,
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
  void improvementCreateCarriesTheCommentsToTheService() throws Exception {
    UUID evalId = UUID.randomUUID();
    when(improvementPlanService.create(any()))
        .thenReturn(
            new ImprovementPlanDto(
                UUID.randomUUID(),
                evalId,
                null,
                "A",
                "R",
                "HIGH",
                "PENDING",
                null,
                null,
                "nota",
                null));

    improvement
        .perform(
            post("/api/improvement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"evaluationId\":\"" + evalId + "\",\"action\":\"A\",\"comments\":\"nota\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments").value("nota"));

    var captor =
        org.mockito.ArgumentCaptor.forClass(
            uy.edu.prisma.web.dto.Dto.CreateImprovementPlanDto.class);
    verify(improvementPlanService).create(captor.capture());
    assertEquals("nota", captor.getValue().comments());
  }

  @Test
  void improvementUpdateCarriesTheCommentsToTheService() throws Exception {
    UUID id = UUID.randomUUID();
    when(improvementPlanService.update(eq(id), any()))
        .thenReturn(
            new ImprovementPlanDto(
                id,
                UUID.randomUUID(),
                null,
                "A",
                null,
                "LOW",
                "PENDING",
                null,
                null,
                "nueva",
                null));

    improvement
        .perform(
            put("/api/improvement/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comments\":\"nueva\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.comments").value("nueva"));

    var captor =
        org.mockito.ArgumentCaptor.forClass(
            uy.edu.prisma.web.dto.Dto.UpdateImprovementPlanDto.class);
    verify(improvementPlanService).update(eq(id), captor.capture());
    assertEquals("nueva", captor.getValue().comments());
  }

  @Test
  void improvementRejectsCommentsOver2000Characters() throws Exception {
    String tooLong = "x".repeat(2001);
    UUID id = UUID.randomUUID();

    improvement
        .perform(
            post("/api/improvement")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"evaluationId\":\""
                        + UUID.randomUUID()
                        + "\",\"action\":\"A\",\"comments\":\""
                        + tooLong
                        + "\"}"))
        .andExpect(status().isBadRequest());
    improvement
        .perform(
            put("/api/improvement/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comments\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());

    verify(improvementPlanService, never()).create(any());
    verify(improvementPlanService, never()).update(any(), any());
  }

  @Test
  void improvementAcceptsCommentsOfExactly2000Characters() throws Exception {
    UUID id = UUID.randomUUID();
    when(improvementPlanService.update(eq(id), any()))
        .thenReturn(
            new ImprovementPlanDto(
                id, UUID.randomUUID(), null, "A", null, "LOW", "PENDING", null, null, null, null));

    improvement
        .perform(
            put("/api/improvement/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comments\":\"" + "x".repeat(2000) + "\"}"))
        .andExpect(status().isOk());
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
  void reportDownloadsAreRestrictedToAdminAndOrgResponsible() throws Exception {
    for (String name : List.of("pdf", "excel", "technicalPdf")) {
      var method = ReportsController.class.getMethod(name, UUID.class);
      var annotation =
          method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertNotNull(annotation, name);
      assertEquals("hasAnyRole('PRISMA_ADMIN','ORG_RESPONSIBLE')", annotation.value());
    }
  }

  @Test
  void reportsPreliminaryPdfAndExcel() throws Exception {
    UUID id = UUID.randomUUID();
    when(reportService.generatePreliminaryPdf(id)).thenReturn("%PDF-1.4".getBytes());
    when(reportService.generatePreliminaryExcel(id)).thenReturn("PK".getBytes());

    reports
        .perform(get("/api/reports/{id}/preliminary/pdf", id))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF));
    reports
        .perform(get("/api/reports/{id}/preliminary/excel", id))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  @Test
  void thePreliminaryReportIsForTheAdminAndTheAuditor() throws Exception {
    for (String name : List.of("preliminaryPdf", "preliminaryExcel")) {
      var annotation =
          ReportsController.class
              .getMethod(name, UUID.class)
              .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
      assertEquals("hasAnyRole('PRISMA_ADMIN','AUDITOR')", annotation.value(), name);
    }
  }

  @Test
  void reportsTechnicalPdf() throws Exception {
    UUID id = UUID.randomUUID();
    when(technicalReportService.generate(id)).thenReturn("%PDF-1.4".getBytes());

    reports
        .perform(get("/api/reports/{id}/technical/pdf", id))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.containsString("reporte-tecnico-" + id + ".pdf")));
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

  // ---------------- Plazos por defecto del plan de mejora (RF-PLN-03, PRI-304) ----------------

  @Test
  void planDeadlinesGet() throws Exception {
    when(planDeadlineSettingsService.get()).thenReturn(new PlanDeadlinesDto(3, 6, 12));

    planDeadlines
        .perform(get("/api/settings/plan-deadlines"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.highMonths").value(3))
        .andExpect(jsonPath("$.mediumMonths").value(6))
        .andExpect(jsonPath("$.lowMonths").value(12));
  }

  @Test
  void planDeadlinesUpdate() throws Exception {
    when(planDeadlineSettingsService.update(any(PlanDeadlinesDto.class)))
        .thenReturn(new PlanDeadlinesDto(1, 6, 12));

    planDeadlines
        .perform(
            put("/api/settings/plan-deadlines")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"highMonths\":1,\"mediumMonths\":6,\"lowMonths\":12}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.highMonths").value(1));

    verify(planDeadlineSettingsService).update(new PlanDeadlinesDto(1, 6, 12));
  }

  @Test
  void planDeadlinesAreReadableByAnyoneButOnlyTheAdminChangesThem() throws Exception {
    var get = PlanDeadlineSettingsController.class.getMethod("get");
    var update = PlanDeadlineSettingsController.class.getMethod("update", PlanDeadlinesDto.class);
    var preAuthorize = org.springframework.security.access.prepost.PreAuthorize.class;

    // Leerlos lo necesita el alta de una acción (cualquier usuario autenticado).
    assertNull(get.getAnnotation(preAuthorize));
    assertNull(PlanDeadlineSettingsController.class.getAnnotation(preAuthorize));
    assertEquals("hasRole('PRISMA_ADMIN')", update.getAnnotation(preAuthorize).value());
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

  // ---------------- Audit log (bitácora, RF-TRZ-05) ----------------

  @Test
  void auditLogExportReturnsCsv() throws Exception {
    when(auditLogService.exportCsv(null, null)).thenReturn("Fecha,Usuario,Email\r\n");

    auditLogs
        .perform(get("/api/audit-logs/export"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv")))
        .andExpect(content().string("Fecha,Usuario,Email\r\n"));

    verify(auditLogService, times(1)).exportCsv(null, null);
  }
}
