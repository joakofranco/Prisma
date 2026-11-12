package uy.edu.prisma.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiConfig.AiProperties.class)
public class AiConfig {

  /**
   * Timeouts hacia backend-ai (PRI-358, RF-EVI-13): sin ellos, un backend-ai colgado (no caído)
   * dejaba la subida de evidencias esperando para siempre. {@code connectTimeout}: establecer la
   * conexión; {@code readTimeout}: esperar la respuesta de la ingesta y de las citas; {@code
   * guideReadTimeout}: la guía de mejora, que genera con el LLM y tarda cerca de un minuto con
   * Ollama local. Si no se configuran: 3 s, 60 s y 120 s.
   */
  @ConfigurationProperties(prefix = "prisma.ai")
  public record AiProperties(
      String baseUrl,
      String internalApiKey,
      Duration connectTimeout,
      Duration readTimeout,
      Duration guideReadTimeout) {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_GUIDE_READ_TIMEOUT = Duration.ofSeconds(120);

    // Con más de un constructor, Spring Boot necesita saber cuál usar para enlazar las propiedades:
    // sin esta anotación intenta el enlace por setters y el backend no arranca.
    @ConstructorBinding
    public AiProperties {
      connectTimeout = connectTimeout != null ? connectTimeout : DEFAULT_CONNECT_TIMEOUT;
      readTimeout = readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT;
      guideReadTimeout = guideReadTimeout != null ? guideReadTimeout : DEFAULT_GUIDE_READ_TIMEOUT;
    }

    public AiProperties(String baseUrl, String internalApiKey) {
      this(baseUrl, internalApiKey, null, null, null);
    }
  }

  /**
   * Cliente HTTP hacia backend-ai. La clave interna (X-Internal-Api-Key) se agrega acá para que
   * ningún llamador se olvide de mandarla -- ver app.core.security.verify_internal_key en
   * backend-ai, que la exige en todas las rutas.
   *
   * <p>Se inyecta el {@link RestClient.Builder} autoconfigurado por Spring Boot (no {@code
   * RestClient.builder()} manual) para heredar los HttpMessageConverters ya registrados por Boot,
   * en vez de depender de la resolución por classpath del builder desnudo.
   */
  // Sin @Primary a propósito: con él, cualquier inyección de RestClient por tipo (por ejemplo
  // KeycloakAdminClient) recibía este cliente y sus llamadas a Keycloak terminaban en backend-ai
  // con 404. Quien lo necesita lo pide con @Qualifier.
  @Bean
  public RestClient aiRestClient(RestClient.Builder builder, AiProperties properties) {
    return build(builder, properties, properties.readTimeout());
  }

  /** Igual que {@link #aiRestClient} pero con la espera larga de la guía de mejora (LLM). */
  @Bean
  public RestClient aiGuideRestClient(RestClient.Builder builder, AiProperties properties) {
    return build(builder, properties, properties.guideReadTimeout());
  }

  private static RestClient build(
      RestClient.Builder builder, AiProperties properties, Duration readTimeout) {
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
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.connectTimeout());
    factory.setReadTimeout(readTimeout);
    builder.requestFactory(factory);
    return builder.build();
  }
}
