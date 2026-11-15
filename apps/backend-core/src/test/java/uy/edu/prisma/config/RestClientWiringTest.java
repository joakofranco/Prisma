package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import uy.edu.prisma.infrastructure.AiEvidenceClient;
import uy.edu.prisma.infrastructure.KeycloakAdminClient;

/**
 * Cada cliente HTTP recibe SU {@link RestClient}. Hubo una regresión: con {@code aiRestClient}
 * marcado {@code @Primary}, {@link KeycloakAdminClient} (que pide un {@code RestClient} por tipo)
 * recibía el cliente de backend-ai y todas las llamadas a Keycloak terminaban en backend-ai con
 * 404. Los tests que construyen los clientes a mano no lo ven: hace falta el contexto de Spring.
 */
class RestClientWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
          .withUserConfiguration(
              AiConfig.class,
              KeycloakAdminConfig.class,
              AiEvidenceClient.class,
              KeycloakAdminClient.class)
          .withPropertyValues(
              "prisma.ai.base-url=http://backend-ai:8000",
              "prisma.keycloak.admin.base-url=http://keycloak:8080",
              "prisma.keycloak.admin.realm=prisma",
              "prisma.keycloak.admin.client-id=c",
              "prisma.keycloak.admin.client-secret=s");

  @Test
  void keycloakRecibeSuClienteYNoElDeLaIa() {
    runner.run(
        ctx -> {
          assertNotNull(ctx.getBean(KeycloakAdminClient.class));
          assertSame(
              ctx.getBean("keycloakAdminRestClient", RestClient.class),
              ReflectionTestUtils.getField(ctx.getBean(KeycloakAdminClient.class), "restClient"));
        });
  }

  @Test
  void elClienteDeIaRecibeLosSuyos() {
    runner.run(
        ctx -> {
          AiEvidenceClient client = ctx.getBean(AiEvidenceClient.class);
          assertSame(
              ctx.getBean("aiRestClient", RestClient.class),
              ReflectionTestUtils.getField(client, "restClient"));
          assertSame(
              ctx.getBean("aiGuideRestClient", RestClient.class),
              ReflectionTestUtils.getField(client, "guideRestClient"));
        });
  }
}
