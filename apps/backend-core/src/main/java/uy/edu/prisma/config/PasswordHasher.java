package uy.edu.prisma.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hash y verificación de contraseñas con Argon2id.
 *
 * <p>Usa {@link Argon2PasswordEncoder} (Spring Security), que delega en la implementación Argon2 de
 * Bouncy Castle -- 100% Java, sin binarios nativos. Antes se usaba argon2-jvm (de.mkammerer), que
 * carga un {@code .so} nativo via JNA compilado para glibc: sobre el runtime Alpine (musl libc) de
 * este proyecto, revienta con SIGSEGV y tumba toda la JVM (no solo falla el request) -- se detectó
 * al probar el alta de usuarios end-to-end contra el contenedor real.
 */
@Component
public class PasswordHasher {

  // saltLength/hashLength en bytes: mismos valores que Spring Security usa como default
  // (defaultsForSpringSecurity_v5_8). iterations/memory/parallelism sí son configurables, para no
  // perder el ajuste de costo que ya existía.
  private static final int SALT_LENGTH = 16;
  private static final int HASH_LENGTH = 32;

  private final Argon2PasswordEncoder encoder;

  public PasswordHasher(
      @Value("${prisma.security.argon2.iterations:3}") int iterations,
      @Value("${prisma.security.argon2.memory-kb:65536}") int memoryKb,
      @Value("${prisma.security.argon2.parallelism:4}") int parallelism) {
    this.encoder =
        new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, parallelism, memoryKb, iterations);
  }

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean verify(String rawPassword, String storedHash) {
    if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
      return false;
    }
    try {
      return encoder.matches(rawPassword, storedHash);
    } catch (Exception e) {
      // Hash almacenado con formato inválido/no reconocido: nunca debe autenticar, tampoco
      // propagar un 500 por un dato corrupto.
      return false;
    }
  }
}
