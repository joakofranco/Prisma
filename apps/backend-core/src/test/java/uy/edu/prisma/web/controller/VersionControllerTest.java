package uy.edu.prisma.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uy.edu.prisma.application.AppVersionService;
import uy.edu.prisma.web.dto.Dto.AppVersionDto;

class VersionControllerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AppVersionService service = mock(AppVersionService.class);
    when(service.publicInfo())
        .thenReturn(
            new AppVersionDto("prisma-backend-core", "0.6.0", "development", true, null, null));
    when(service.fullInfo())
        .thenReturn(
            new AppVersionDto(
                "prisma-backend-core",
                "0.6.0",
                "development",
                true,
                "abc1234",
                "2026-11-13T10:00:00Z"));
    mockMvc = MockMvcBuilders.standaloneSetup(new VersionController(service)).build();
  }

  @Test
  void withoutASessionItReturnsOnlyTheVersionAndTheEnvironment() throws Exception {
    mockMvc
        .perform(get("/api/version"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("0.6.0"))
        .andExpect(jsonPath("$.environment").value("development"))
        .andExpect(jsonPath("$.development").value(true))
        .andExpect(jsonPath("$.commit").doesNotExist())
        .andExpect(jsonPath("$.buildTime").doesNotExist());
  }

  @Test
  void anAnonymousTokenCountsAsNoSession() throws Exception {
    var anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    mockMvc
        .perform(get("/api/version").principal(anonymous))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commit").doesNotExist());
  }

  @Test
  void withASessionItAlsoReturnsTheCommitAndTheBuildTime() throws Exception {
    var user = new TestingAuthenticationToken("ana", "n/a", "ROLE_VIEWER");
    user.setAuthenticated(true);

    mockMvc
        .perform(get("/api/version").principal(user))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("0.6.0"))
        .andExpect(jsonPath("$.commit").value("abc1234"))
        .andExpect(jsonPath("$.buildTime").value("2026-11-13T10:00:00Z"));
  }
}
