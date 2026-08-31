package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import uy.edu.prisma.config.MinioConfig.MinioProperties;

class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher(3, 4096, 1);

  @Test
  void hashAndVerifyRoundTrip() {
    String hash = hasher.hash("Secreto123!");

    assertNotNull(hash);
    assertTrue(hash.contains("argon2"));
    assertTrue(hasher.verify("Secreto123!", hash));
    assertFalse(hasher.verify("otra-clave", hash));
  }

  @Test
  void verifyRejectsNullOrBlank() {
    assertFalse(hasher.verify(null, "$argon2id$v=19$m=65536,t=2,p=4$c2FsdA$a2V5"));
    assertFalse(hasher.verify("x", null));
    assertFalse(hasher.verify("x", " "));
  }

  @Test
  void minioPropertiesAccessors() {
    MinioProperties p =
        new MinioProperties("http://minio:9000", "http://localhost:9001", "ak", "sk", "bucket");

    assertEquals("http://minio:9000", p.endpoint());
    assertEquals("http://localhost:9001", p.publicEndpoint());
    assertEquals("ak", p.accessKey());
    assertEquals("sk", p.secretKey());
    assertEquals("bucket", p.bucket());

    MinioConfig config = new MinioConfig();
    assertNotNull(config.minioClient(p));
    // El cliente público apunta a un endpoint distinto del interno -- ver el comentario en
    // MinioConfig sobre por qué la URL de descarga que ve el navegador no puede firmarse con el
    // mismo cliente que usa backend-core para hablarle a MinIO dentro de la red de Docker.
    assertNotNull(config.minioPublicClient(p));
  }
}
