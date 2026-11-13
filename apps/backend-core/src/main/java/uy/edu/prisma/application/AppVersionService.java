package uy.edu.prisma.application;

import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import uy.edu.prisma.web.dto.Dto.AppVersionDto;

/**
 * Versión y entorno de esta instancia del backend.
 *
 * <p>La versión es la del {@code pom.xml} (Spring la expone en {@link BuildProperties} al generar
 * {@code META-INF/build-info.properties} en el empaquetado). El entorno viene de la variable {@code
 * APP_ENV} (en docker-compose, el {@code ENV} del {@code .env}): {@code development}, {@code
 * staging} o {@code production}. Sin la variable se asume {@code development}: si alguien olvida
 * configurarla en producción, el error es visible (un cartel de desarrollo) en vez de pasar
 * desapercibido. El commit y la fecha de compilación son opcionales ({@code APP_COMMIT}, {@code
 * APP_BUILD_TIME}, los inyecta el CI al construir la imagen).
 */
@Service
public class AppVersionService {

  static final String APPLICATION = "prisma-backend-core";
  static final String UNKNOWN_VERSION = "unknown";
  static final String DEVELOPMENT = "development";

  private final String version;
  private final String environment;
  private final String commit;
  private final String buildTime;

  public AppVersionService(
      ObjectProvider<BuildProperties> buildProperties,
      @Value("${prisma.app.environment:development}") String environment,
      @Value("${prisma.app.commit:}") String commit,
      @Value("${prisma.app.build-time:}") String buildTime) {
    BuildProperties build = buildProperties.getIfAvailable();
    this.version =
        build != null && build.getVersion() != null ? build.getVersion() : UNKNOWN_VERSION;
    this.environment = normalizeEnvironment(environment);
    this.commit = blankToNull(commit);
    // Solo APP_BUILD_TIME: el build.time de build-info es una fecha fija (compilación reproducible,
    // project.build.outputTimestamp) y no dice cuándo se construyó realmente.
    this.buildTime = blankToNull(buildTime);
  }

  /**
   * Lo que se publica sin autenticación (pantalla de login): versión y entorno, nada que ayude a
   * identificar la compilación exacta.
   */
  public AppVersionDto publicInfo() {
    return new AppVersionDto(APPLICATION, version, environment, isDevelopment(), null, null);
  }

  /** Además, el commit y la fecha de compilación: solo para usuarios autenticados. */
  public AppVersionDto fullInfo() {
    return new AppVersionDto(APPLICATION, version, environment, isDevelopment(), commit, buildTime);
  }

  /** Para {@code /actuator/info}. */
  public Map<String, Object> asInfo() {
    AppVersionDto full = fullInfo();
    Map<String, Object> info = new java.util.LinkedHashMap<>();
    info.put("application", full.application());
    info.put("version", full.version());
    info.put("environment", full.environment());
    if (full.commit() != null) {
      info.put("commit", full.commit());
    }
    if (full.buildTime() != null) {
      info.put("buildTime", full.buildTime());
    }
    return info;
  }

  public boolean isDevelopment() {
    return DEVELOPMENT.equals(environment);
  }

  /** Acepta los alias habituales y deja el resto como viene, en minúsculas. */
  static String normalizeEnvironment(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEVELOPMENT;
    }
    String value = raw.strip().toLowerCase(Locale.ROOT);
    return switch (value) {
      case "dev", "develop", "local" -> DEVELOPMENT;
      case "stage" -> "staging";
      case "prod" -> "production";
      default -> value;
    };
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
