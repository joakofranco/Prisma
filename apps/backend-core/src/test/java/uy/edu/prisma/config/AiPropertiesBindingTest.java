package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uy.edu.prisma.config.AiConfig.AiProperties;

/**
 * Las propiedades {@code prisma.ai.*} se enlazan como las enlaza Spring Boot al arrancar. Ese
 * enlace falló una vez (el record tenía dos constructores y Boot no sabía cuál usar: el backend no
 * arrancaba) y los tests que construyen {@link AiProperties} a mano no lo detectan.
 */
class AiPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Binding.class);

  @EnableConfigurationProperties(AiProperties.class)
  static class Binding {}

  @Test
  void enlazaSoloLaUrlYLaClaveYAplicaLosTimeoutsPorDefecto() {
    runner
        .withPropertyValues("prisma.ai.base-url=http://ai:8000", "prisma.ai.internal-api-key=k")
        .run(
            ctx -> {
              AiProperties p = ctx.getBean(AiProperties.class);
              assertEquals("http://ai:8000", p.baseUrl());
              assertEquals("k", p.internalApiKey());
              assertEquals(Duration.ofSeconds(3), p.connectTimeout());
              assertEquals(Duration.ofSeconds(60), p.readTimeout());
              assertEquals(Duration.ofSeconds(120), p.guideReadTimeout());
            });
  }

  @Test
  void enlazaLosTimeoutsConfigurados() {
    runner
        .withPropertyValues(
            "prisma.ai.base-url=http://ai:8000",
            "prisma.ai.connect-timeout=1s",
            "prisma.ai.read-timeout=10s",
            "prisma.ai.guide-read-timeout=200s")
        .run(
            ctx -> {
              AiProperties p = ctx.getBean(AiProperties.class);
              assertEquals(Duration.ofSeconds(1), p.connectTimeout());
              assertEquals(Duration.ofSeconds(10), p.readTimeout());
              assertEquals(Duration.ofSeconds(200), p.guideReadTimeout());
              assertNull(p.internalApiKey());
            });
  }
}
