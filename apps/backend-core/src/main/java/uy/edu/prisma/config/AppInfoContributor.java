package uy.edu.prisma.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import uy.edu.prisma.application.AppVersionService;

/** Suma la versión, el entorno y el commit a {@code /actuator/info} (red interna). */
@Component
public class AppInfoContributor implements InfoContributor {

  private final AppVersionService versionService;

  public AppInfoContributor(AppVersionService versionService) {
    this.versionService = versionService;
  }

  @Override
  public void contribute(Info.Builder builder) {
    builder.withDetail("app", versionService.asInfo());
  }
}
