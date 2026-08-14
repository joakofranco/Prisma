package uy.edu.prisma.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KeycloakAdminConfig.KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

  /**
   * {@code clientId}/{@code clientSecret} son los del client "prisma-backend" con Service Accounts
   * habilitado (ver infra/keycloak/realm-prisma.json) -- NO son credenciales de un usuario admin
   * humano. Ese client necesita los roles manage-users/view-users de realm-management dentro del
   * realm, si no cualquier llamada a la Admin REST API devuelve 403.
   */
  @ConfigurationProperties(prefix = "prisma.keycloak.admin")
  public record KeycloakAdminProperties(
      String baseUrl, String realm, String clientId, String clientSecret) {}

  /**
   * Igual que en AiConfig: se inyecta el RestClient.Builder autoconfigurado por Boot (no
   * RestClient.builder() manual) y se fuerza HTTP/1.1 vía SimpleClientHttpRequestFactory -- el
   * HttpClient del JDK intenta upgrade a HTTP/2 en texto plano contra servidores http://, lo que ya
   * rompió silenciosamente otra integración de este mismo tipo (ver AiConfig).
   */
  @Bean
  public RestClient keycloakAdminRestClient(
      RestClient.Builder builder, KeycloakAdminProperties properties) {
    builder.baseUrl(properties.baseUrl());
    builder.requestFactory(new SimpleClientHttpRequestFactory());
    return builder.build();
  }
}
