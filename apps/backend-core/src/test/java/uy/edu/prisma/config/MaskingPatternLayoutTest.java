package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaskingPatternLayoutTest {

  @Test
  void masksPasswordInJsonLikeMessage() {
    String input = "Body recibido: {\"email\":\"a@b.com\",\"password\":\"secreto123\"}";

    String masked = MaskingPatternLayout.mask(input);

    assertFalse(masked.contains("secreto123"));
    assertTrue(masked.contains("\"password\":\"***REDACTED***\""));
    assertTrue(masked.contains("a@b.com"));
  }

  @Test
  void masksBearerToken() {
    String input = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature";

    String masked = MaskingPatternLayout.mask(input);

    assertFalse(masked.contains("eyJhbGciOiJSUzI1NiJ9"));
    assertTrue(masked.contains("Bearer ***REDACTED***"));
  }

  @Test
  void masksFormEncodedPassword() {
    String input = "password=S3creto&username=juan";

    String masked = MaskingPatternLayout.mask(input);

    assertFalse(masked.contains("S3creto"));
    assertTrue(masked.contains("password=***REDACTED***"));
    assertTrue(masked.contains("username=juan"));
  }

  @Test
  void leavesNonSensitiveMessagesUnchanged() {
    String input = "Usuario juan@prisma.local inicio sesion correctamente";

    assertEquals(input, MaskingPatternLayout.mask(input));
  }
}
