package uy.edu.prisma.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uy.edu.prisma.config.ClamAvConfig.ClamAvProperties;

class ClamAvClientTest {

  private ServerSocket server;
  private Thread serverThread;
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> command = new AtomicReference<>();

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
  }

  private ClamAvClient startFakeClamd(String reply) throws IOException {
    server = new ServerSocket(0);
    serverThread =
        new Thread(
            () -> {
              try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] cmd = new byte[10];
                in.readFully(cmd);
                command.set(new String(cmd, StandardCharsets.US_ASCII));
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                while (true) {
                  int length = in.readInt();
                  if (length == 0) {
                    break;
                  }
                  byte[] chunk = new byte[length];
                  in.readFully(chunk);
                  body.write(chunk);
                }
                received.set(body.toByteArray());
                socket.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.UTF_8));
              } catch (IOException ignored) {
                received.set(null);
              }
            });
    serverThread.start();
    return new ClamAvClient(
        new ClamAvProperties("localhost", server.getLocalPort(), Duration.ofSeconds(5)));
  }

  @Test
  void aCleanReplyIsReportedAsClean() throws Exception {
    ClamAvClient client = startFakeClamd("stream: OK");

    ClamAvClient.ScanResult result = client.scan("hola".getBytes(StandardCharsets.UTF_8));
    serverThread.join();

    assertEquals(ClamAvClient.Status.CLEAN, result.status());
    assertNull(result.signature());
    assertEquals("zINSTREAM\0", command.get());
    assertArrayEquals("hola".getBytes(StandardCharsets.UTF_8), received.get());
  }

  @Test
  void aFoundReplyReportsTheSignature() throws Exception {
    ClamAvClient client = startFakeClamd("stream: Win.Test.EICAR_HDB-1 FOUND");

    ClamAvClient.ScanResult result = client.scan(new byte[] {1, 2, 3});

    assertEquals(ClamAvClient.Status.INFECTED, result.status());
    assertEquals("Win.Test.EICAR_HDB-1", result.signature());
  }

  @Test
  void contentLargerThanOneChunkIsSentIntact() throws Exception {
    byte[] big = new byte[200_000];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) (i % 251);
    }
    ClamAvClient client = startFakeClamd("stream: OK");

    client.scan(big);
    serverThread.join();

    assertArrayEquals(big, received.get());
  }

  @Test
  void anErrorReplyIsReportedAsError() throws Exception {
    ClamAvClient client = startFakeClamd("INSTREAM size limit exceeded. ERROR");

    ClamAvClient.ScanResult result = client.scan(new byte[] {1});

    assertEquals(ClamAvClient.Status.ERROR, result.status());
    assertTrue(result.detail().contains("size limit"));
  }

  @Test
  void aServerThatIsNotListeningIsReportedAsError() throws Exception {
    int closedPort;
    try (ServerSocket probe = new ServerSocket(0)) {
      closedPort = probe.getLocalPort();
    }
    ClamAvClient client =
        new ClamAvClient(new ClamAvProperties("localhost", closedPort, Duration.ofSeconds(1)));

    ClamAvClient.ScanResult result = client.scan(new byte[] {1});

    assertEquals(ClamAvClient.Status.ERROR, result.status());
  }

  @Test
  void anEmptyReplyIsReportedAsError() {
    assertEquals(ClamAvClient.Status.ERROR, ClamAvClient.parse("").status());
  }
}
