package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import uy.edu.prisma.config.ObjectStorageConfig.StorageProperties;

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
  void storagePropertiesAccessors() {
    StorageProperties p =
        new StorageProperties("http://rustfs:9000", "http://localhost:9001", "ak", "sk", "bucket");

    assertEquals("http://rustfs:9000", p.endpoint());
    assertEquals("http://localhost:9001", p.publicEndpoint());
    assertEquals("ak", p.accessKey());
    assertEquals("sk", p.secretKey());
    assertEquals("bucket", p.bucket());

    ObjectStorageConfig config = new ObjectStorageConfig();
    assertNotNull(config.s3Client(p));
    assertNotNull(config.internalPresigner(p));
    assertNotNull(config.publicPresigner(p));
  }

  @Test
  void presignersSignOfflineAgainstTheirOwnEndpointWithPathStyle() {
    StorageProperties p =
        new StorageProperties("http://rustfs:9000", "http://localhost:9001", "ak", "sk", "bucket");
    ObjectStorageConfig config = new ObjectStorageConfig();
    GetObjectPresignRequest request =
        GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofMinutes(15))
            .getObjectRequest(GetObjectRequest.builder().bucket("bucket").key("evidence/x").build())
            .build();

    // El presigner público firma para el host que ve el navegador; el interno, para el de la red
    // de contenedores. Ambos en path-style (/bucket/key) y sin ninguna llamada de red.
    URL publicUrl = config.publicPresigner(p).presignGetObject(request).url();
    URL internalUrl = config.internalPresigner(p).presignGetObject(request).url();

    assertEquals("localhost", publicUrl.getHost());
    assertEquals(9001, publicUrl.getPort());
    assertEquals("/bucket/evidence/x", publicUrl.getPath());
    assertTrue(publicUrl.getQuery().contains("X-Amz-Signature="));
    assertTrue(publicUrl.getQuery().contains("us-east-1"));
    assertEquals("rustfs", internalUrl.getHost());
    assertEquals(9000, internalUrl.getPort());
  }
}
