package uy.edu.prisma.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiConfig.AiProperties.class)
public class AiConfig {

  @ConfigurationProperties(prefix = "prisma.ai")
  public record AiProperties(String baseUrl, String internalApiKey) {}

  /**
   * Cliente HTTP hacia backend-ai. La clave interna (X-Internal-Api-Key) se agrega acá para que
   * ningún llamador se olvide de mandarla -- ver app.core.security.verify_internal_key en
   * backend-ai, que la exige en todas las rutas.
   *
   * <p>Se inyecta el {@link RestClient.Builder} autoconfigurado por Spring Boot (no {@code
   * RestClient.builder()} manual) para heredar los HttpMessageConverters ya registrados por Boot,
   * en vez de depender de la resolución por classpath del builder desnudo.
   */
  @Bean
  public RestClient aiRestClient(RestClient.Builder builder, AiProperties properties) {
    builder.baseUrl(properties.baseUrl());
    if (properties.internalApiKey() != null && !properties.internalApiKey().isBlank()) {
      builder.defaultHeader("X-Internal-Api-Key", properties.internalApiKey());
    }
    // El RestClient.Builder autoconfigurado usa por defecto java.net.http.HttpClient (JDK), que
    // intenta upgrade a HTTP/2 en texto plano (h2c) contra cualquier servidor http://. uvicorn
    // (backend-ai) sólo habla HTTP/1.1 y, al recibir ese intento de upgrade, descarta el body de
    // la request ("Unsupported upgrade request" en sus logs) -- el body enviado por Spring
    // llegaba íntegro y bien formado, pero backend-ai lo veía vacío. SimpleClientHttpRequestFactory
    // usa HttpURLConnection, que siempre habla HTTP/1.1 sin intentos de upgrade.
    builder.requestFactory(new SimpleClientHttpRequestFactory());
    return builder.build();
  }
}
