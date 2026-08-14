package uy.edu.prisma.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  @Conditional(MissingIssuerCondition.class)
  public JwtDecoder jwtDecoder(@Value("${prisma.jwt.secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  /**
   * Deliberadamente no delega en la auto-configuracion de Spring Boot a partir de issuer-uri (que
   * usaria esa misma URL tanto para validar el claim iss como para descargar las JWKS): en
   * docker-compose este backend necesita resolver las JWKS via la red interna
   * (prisma.keycloak.jwks-uri, ej. http://keycloak:8080/...) mientras que el token emitido por
   * Keycloak trae como iss la URL publica que ve el navegador. Si ambas se igualan, la validacion
   * del claim iss falla con 401 para todo request aunque el token sea valido.
   *
   * <p>Keycloak corre con hostname dinámico (KC_HOSTNAME sin fijar, ver el servicio "keycloak" en
   * docker-compose.yml): arma el claim "iss" con el mismo host que usó el navegador para loguearse,
   * así que puede ser https://prisma.local/... o https://localhost/... indistintamente -- por eso
   * acá se valida contra una LISTA de issuers confiables (prisma.keycloak.trusted- issuers) en vez
   * de un único valor fijo como haría {@code JwtValidators.createDefaultWithIssuer}.
   */
  @Bean
  @Conditional(KeycloakIssuerCondition.class)
  public JwtDecoder keycloakJwtDecoder(
      @Value("${prisma.keycloak.trusted-issuers}") String trustedIssuers,
      @Value("${prisma.keycloak.jwks-uri}") String jwksUri) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(trustedIssuerValidator(trustedIssuers));
    return decoder;
  }

  /**
   * Construye el validador de "iss" contra la lista de orígenes confiables (separada de {@link
   * #keycloakJwtDecoder} para poder probarlo sin necesitar red -- {@code NimbusJwtDecoder} intenta
   * resolver el JWKS-URI recién al decodificar, pero levantar el {@code JwtDecoder} completo en un
   * test igual acopla innecesariamente ambas cosas).
   */
  static OAuth2TokenValidator<Jwt> trustedIssuerValidator(String trustedIssuers) {
    Set<String> issuers =
        Arrays.stream(trustedIssuers.split(","))
            .map(String::strip)
            .filter(issuer -> !issuer.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    return new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefault(),
        new JwtClaimValidator<String>(JwtClaimNames.ISS, issuers::contains));
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/**", "/actuator")
                    .permitAll()
                    .requestMatchers("/api/v1/docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

    return http.build();
  }

  /**
   * Habilita CORS para que el frontend (SPA en un origen distinto: Vite dev server o el bundle
   * servido por Nginx) pueda invocar la API. Sin este bean, el navegador bloquea toda llamada
   * XHR/fetch por el preflight aunque el request este autenticado.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${prisma.cors.allowed-origins}") String allowedOrigins) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  /**
   * Convierte los roles de Keycloak (claim {@code realm_access.roles}) en autoridades Spring {@code
   * ROLE_*}. El {@code JwtGrantedAuthoritiesConverter} por defecto solo lee scope/scp.
   */
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::extractRealmRoles);
    return converter;
  }

  private static Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess != null) {
      Object roles = realmAccess.get("roles");
      if (roles instanceof Iterable<?> iterable) {
        for (Object role : iterable) {
          if (role != null && !role.toString().isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
          }
        }
      }
    }
    return authorities;
  }

  static class MissingIssuerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String issuer =
          context
              .getEnvironment()
              .getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
      return issuer == null || issuer.isBlank();
    }
  }

  static class KeycloakIssuerCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String issuer =
          context
              .getEnvironment()
              .getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
      return issuer != null && !issuer.isBlank();
    }
  }
}
