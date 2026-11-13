package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import uy.edu.prisma.web.dto.Dto.AppVersionDto;

class AppVersionServiceTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<BuildProperties> provider(String version, String time) {
    ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
    if (version == null) {
      when(provider.getIfAvailable()).thenReturn(null);
    } else {
      Properties props = new Properties();
      props.setProperty("version", version);
      if (time != null) {
        props.setProperty("time", time);
      }
      when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));
    }
    return provider;
  }

  private static AppVersionService service(String environment, String commit, String buildTime) {
    return new AppVersionService(
        provider("0.6.0", "2026-11-13T10:00:00Z"), environment, commit, buildTime);
  }

  @Test
  void reportsTheBuildVersionAndTheEnvironment() {
    AppVersionDto info = service("production", "", "").publicInfo();

    assertEquals("prisma-backend-core", info.application());
    assertEquals("0.6.0", info.version());
    assertEquals("production", info.environment());
    assertFalse(info.development());
  }

  @Test
  void aDevelopmentEnvironmentIsFlagged() {
    assertTrue(service("development", "", "").publicInfo().development());
  }

  @Test
  void anUnsetEnvironmentIsTreatedAsDevelopmentSoAMissingSettingInProductionIsVisible() {
    for (String unset : new String[] {null, "", "   "}) {
      AppVersionDto info = service(unset, "", "").publicInfo();
      assertEquals("development", info.environment(), String.valueOf(unset));
      assertTrue(info.development());
    }
  }

  @Test
  void commonAliasesAreNormalized() {
    assertEquals("development", AppVersionService.normalizeEnvironment("dev"));
    assertEquals("development", AppVersionService.normalizeEnvironment(" LOCAL "));
    assertEquals("production", AppVersionService.normalizeEnvironment("PROD"));
    assertEquals("staging", AppVersionService.normalizeEnvironment("stage"));
    assertEquals("staging", AppVersionService.normalizeEnvironment("staging"));
    // Un valor desconocido se conserva (en minúsculas) y no cuenta como desarrollo.
    assertEquals("qa", AppVersionService.normalizeEnvironment("QA"));
    assertFalse(service("qa", "", "").publicInfo().development());
  }

  @Test
  void thePublicViewHidesTheCommitAndTheBuildTime() {
    AppVersionDto info = service("staging", "abc1234", "2026-11-13T09:00:00Z").publicInfo();

    assertNull(info.commit());
    assertNull(info.buildTime());
  }

  @Test
  void theFullViewAddsTheCommitAndTheBuildTime() {
    AppVersionDto info = service("staging", "abc1234", "2026-11-13T09:00:00Z").fullInfo();

    assertEquals("abc1234", info.commit());
    assertEquals("2026-11-13T09:00:00Z", info.buildTime());
  }

  @Test
  void withoutCommitOrBuildTimeFromTheCiTheyAreNotReported() {
    // El build.time de build-info es una fecha fija (compilación reproducible): no se usa.
    AppVersionDto info = service("staging", "", "").fullInfo();

    assertNull(info.commit());
    assertNull(info.buildTime());
  }

  @Test
  void withoutBuildInfoTheVersionIsUnknown() {
    AppVersionService service = new AppVersionService(provider(null, null), "development", "", "");

    assertEquals("unknown", service.publicInfo().version());
    assertNull(service.fullInfo().buildTime());
  }

  @Test
  void theActuatorInfoOmitsWhatIsNotKnown() {
    var info = service("development", "", "").asInfo();

    assertEquals("0.6.0", info.get("version"));
    assertEquals("development", info.get("environment"));
    assertFalse(info.containsKey("commit"));
    assertFalse(info.containsKey("buildTime"));
  }
}
