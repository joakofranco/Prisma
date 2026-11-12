package uy.edu.prisma.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import uy.edu.prisma.config.ClamAvConfig.ClamAvProperties;

@EnabledIfEnvironmentVariable(named = "CLAMAV_HOST", matches = ".+")
class ClamAvClientRealTest {

  private static final String EICAR =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

  private ClamAvClient client() {
    String host = System.getenv("CLAMAV_HOST");
    int port = Integer.parseInt(System.getenv().getOrDefault("CLAMAV_PORT", "3310"));
    return new ClamAvClient(new ClamAvProperties(host, port, Duration.ofMinutes(3)));
  }

  @Test
  void eicarIsDetectedByTheRealAntivirus() {
    ClamAvClient.ScanResult result = client().scan(EICAR.getBytes(StandardCharsets.US_ASCII));

    assertEquals(ClamAvClient.Status.INFECTED, result.status());
    assertTrue(result.signature().toUpperCase().contains("EICAR"), result.signature());
  }

  @Test
  void aHarmlessFileIsCleanWithTheRealAntivirus() {
    ClamAvClient.ScanResult result =
        client().scan("%PDF-1.4 contenido inofensivo".getBytes(StandardCharsets.US_ASCII));

    assertEquals(ClamAvClient.Status.CLEAN, result.status());
    assertNull(result.signature());
  }

  @Test
  void aFileAtTheHundredMegabyteLimitIsScanned() {
    byte[] content = new byte[100 * 1024 * 1024];

    ClamAvClient.ScanResult result = client().scan(content);

    assertEquals(ClamAvClient.Status.CLEAN, result.status(), result.detail());
  }

  @Test
  void eicarInsideALargeZipIsStillDetected() throws Exception {
    byte[] filler = new byte[1024 * 1024];
    int fillerMegabytes = 99;
    CRC32 crc = new CRC32();
    for (int i = 0; i < fillerMegabytes; i++) {
      crc.update(filler);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      ZipEntry stored = new ZipEntry("relleno.bin");
      stored.setMethod(ZipEntry.STORED);
      stored.setSize((long) fillerMegabytes * filler.length);
      stored.setCompressedSize(stored.getSize());
      stored.setCrc(crc.getValue());
      zip.putNextEntry(stored);
      for (int i = 0; i < fillerMegabytes; i++) {
        zip.write(filler);
      }
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("eicar.txt"));
      zip.write(EICAR.getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
    }
    assertTrue(bytes.size() > 98 * 1024 * 1024);

    ClamAvClient.ScanResult result = client().scan(bytes.toByteArray());

    assertEquals(ClamAvClient.Status.INFECTED, result.status(), result.detail());
  }
}
