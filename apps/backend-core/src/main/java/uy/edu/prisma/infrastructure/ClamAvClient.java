package uy.edu.prisma.infrastructure;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uy.edu.prisma.config.ClamAvConfig.ClamAvProperties;

@Component
public class ClamAvClient {

  private static final Logger log = LoggerFactory.getLogger(ClamAvClient.class);
  private static final int CHUNK_SIZE = 64 * 1024;
  private static final int CONNECT_TIMEOUT_MS = 5_000;

  public enum Status {
    CLEAN,
    INFECTED,
    ERROR
  }

  public record ScanResult(Status status, String signature, String detail) {
    static ScanResult clean() {
      return new ScanResult(Status.CLEAN, null, null);
    }

    static ScanResult infected(String signature) {
      return new ScanResult(Status.INFECTED, signature, null);
    }

    static ScanResult error(String detail) {
      return new ScanResult(Status.ERROR, null, detail);
    }
  }

  private final ClamAvProperties properties;

  public ClamAvClient(ClamAvProperties properties) {
    this.properties = properties;
  }

  public ScanResult scan(byte[] content) {
    try (Socket socket = new Socket()) {
      socket.connect(
          new InetSocketAddress(properties.host(), properties.port()), CONNECT_TIMEOUT_MS);
      socket.setSoTimeout((int) properties.timeout().toMillis());
      DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
      for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
        int length = Math.min(CHUNK_SIZE, content.length - offset);
        out.writeInt(length);
        out.write(content, offset, length);
      }
      out.writeInt(0);
      out.flush();
      return parse(readReply(socket.getInputStream()));
    } catch (IOException e) {
      log.warn("No se pudo analizar el archivo con ClamAV: {}", e.getMessage());
      return ScanResult.error(e.getMessage());
    }
  }

  private static String readReply(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\0", "").strip();
  }

  static ScanResult parse(String reply) {
    if (reply.endsWith("OK")) {
      return ScanResult.clean();
    }
    if (reply.endsWith("FOUND")) {
      String body = reply.substring(0, reply.length() - "FOUND".length()).strip();
      int colon = body.indexOf(':');
      return ScanResult.infected(colon >= 0 ? body.substring(colon + 1).strip() : body);
    }
    return ScanResult.error(reply.isEmpty() ? "Respuesta vacía de ClamAV" : reply);
  }
}
