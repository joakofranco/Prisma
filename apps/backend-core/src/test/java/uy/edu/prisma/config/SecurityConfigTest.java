package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

class SecurityConfigTest {

  private final SecurityConfig config = new SecurityConfig();

  private Jwt jwt(Map<String, Object> claims) {
    return Jwt.withTokenValue("t").header("alg", "none").claims(c -> c.putAll(claims)).build();
  }

  private Collection<GrantedAuthority> authorities(Jwt token) {
    JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
    assertNotNull(converter);
    return converter.convert(token).getAuthorities();
  }

  @Test
  void convertsRealmRolesToAuthorities() {
    Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("PRISMA_ADMIN", "AUDITOR"))));

    Collection<GrantedAuthority> auths = authorities(token);

    assertTrue(auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_PRISMA_ADMIN")));
    assertTrue(auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_AUDITOR")));
  }

  @Test
  void missingRealmAccessYieldsNoAuthorities() {
    Jwt token = jwt(Map.of("scope", "openid"));

    assertTrue(authorities(token).isEmpty());
  }

  @Test
  void nonIterableRolesAreIgnored() {
    Jwt token = jwt(Map.of("realm_access", Map.of("roles", "PRISMA_ADMIN")));

    assertTrue(authorities(token).isEmpty());
  }

  // ---- trustedIssuerValidator: con KC_HOSTNAME dinamico el claim "iss" varia segun el host
  // usado para loguearse (prisma.local, localhost, ...), así que se valida contra una lista.

  @Test
  void trustedIssuerValidatorAcceptsAnyIssuerFromTheConfiguredList() {
    OAuth2TokenValidator<Jwt> validator =
        SecurityConfig.trustedIssuerValidator(
            "https://prisma.local/auth/realms/prisma,https://localhost/auth/realms/prisma");

    Jwt viaPrismaLocal = jwt(Map.of("iss", "https://prisma.local/auth/realms/prisma"));
    Jwt viaLocalhost = jwt(Map.of("iss", "https://localhost/auth/realms/prisma"));

    assertFalse(validator.validate(viaPrismaLocal).hasErrors());
    assertFalse(validator.validate(viaLocalhost).hasErrors());
  }

  @Test
  void trustedIssuerValidatorRejectsAnUntrustedIssuer() {
    OAuth2TokenValidator<Jwt> validator =
        SecurityConfig.trustedIssuerValidator("https://prisma.local/auth/realms/prisma");

    Jwt untrusted = jwt(Map.of("iss", "https://evil.example/auth/realms/prisma"));

    OAuth2TokenValidatorResult result = validator.validate(untrusted);
    assertTrue(result.hasErrors());
  }

  @Test
  void trustedIssuerValidatorTrimsWhitespaceAroundEachEntry() {
    OAuth2TokenValidator<Jwt> validator =
        SecurityConfig.trustedIssuerValidator(
            " https://prisma.local/auth/realms/prisma , https://localhost/auth/realms/prisma ");

    Jwt token = jwt(Map.of("iss", "https://localhost/auth/realms/prisma"));

    assertFalse(validator.validate(token).hasErrors());
  }
}
