package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.web.error.GlobalExceptionHandler;

/**
 * RF-EVI-04: prueba con un Tomcat real (no MockMvc) de que el límite de subida está aplicado. Las
 * pruebas unitarias con {@code MockMultipartFile} no pasan por la configuración multipart de
 * Spring, así que no podrían detectar que rige el valor por defecto (1 MB). El límite se baja a 1
 * MB para no mover archivos enormes.
 *
 * <p>El cliente es un socket crudo que lee la respuesta mientras todavía envía el cuerpo, como
 * hacen los navegadores y curl: el cliente HTTP del JDK, que primero envía todo y recién después
 * lee, pierde la respuesta cuando el servidor responde antes de que termine la subida.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = EvidenceUploadLimitTest.Minimal.class,
    properties = {
      "prisma.evidence.max-size=1MB",
      "prisma.evidence.allowed-extensions=pdf",
      "server.tomcat.max-swallow-size=64MB"
    })
class EvidenceUploadLimitTest {

  @Configuration
  @Import({EvidenceConfig.class, GlobalExceptionHandler.class, Echo.class})
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    MultipartAutoConfiguration.class,
    HttpMessageConvertersAutoConfiguration.class,
    HttpEncodingAutoConfiguration.class,
    WebMvcAutoConfiguration.class
  })
  static class Minimal {}

  @RestController
  static class Echo {
    @PostMapping("/upload")
    public String upload(@RequestPart("file") MultipartFile file) {
      return "recibido " + file.getSize();
    }
  }

  @Value("${local.server.port}")
  private int port;

  @Autowired private ServerProperties serverProperties;

  private record Reply(int status, String body) {}

  private Reply post(int fileBytes) throws Exception {
    String boundary = "----prisma" + System.nanoTime();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    long length = head.length + (long) fileBytes + tail.length;

    try (Socket socket = new Socket("localhost", port)) {
      Thread writer =
          new Thread(
              () -> {
                try {
                  OutputStream out = socket.getOutputStream();
                  out.write(
                      ("POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                              + "Content-Type: multipart/form-data; boundary="
                              + boundary
                              + "\r\nContent-Length: "
                              + length
                              + "\r\n\r\n")
                          .getBytes(StandardCharsets.UTF_8));
                  out.write(head);
                  byte[] chunk = new byte[64 * 1024];
                  for (long sent = 0; sent < fileBytes; sent += chunk.length) {
                    out.write(chunk, 0, (int) Math.min(chunk.length, fileBytes - sent));
                  }
                  out.write(tail);
                  out.flush();
                } catch (Exception ignored) {
                  // Si el servidor cortó, lo que importa es lo que alcanzó a responder.
                }
              });
      writer.start();

      InputStream in = socket.getInputStream();
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      try {
        int n;
        while ((n = in.read(buffer)) != -1) {
          raw.write(buffer, 0, n);
        }
      } catch (java.io.IOException reset) {
        // Un RST al final de la respuesta no invalida lo ya recibido; se evalúa abajo.
      }
      writer.join();
      String response = raw.toString(StandardCharsets.UTF_8);
      assertTrue(
          response.startsWith("HTTP/1.1 "), "sin respuesta del servidor: [" + response + "]");
      int status = Integer.parseInt(response.substring(9, 12));
      int bodyStart = response.indexOf("\r\n\r\n");
      return new Reply(status, bodyStart < 0 ? "" : response.substring(bodyStart + 4));
    }
  }

  @Test
  void tomcatSwallowSizeIsApplied() {
    assertEquals(64L * 1024 * 1024, serverProperties.getTomcat().getMaxSwallowSize().toBytes());
  }

  @Test
  void aFileWithinTheLimitGoesThrough() throws Exception {
    Reply reply = post(900 * 1024);

    assertEquals(200, reply.status());
    assertTrue(reply.body().contains("recibido"), reply.body());
  }

  @Test
  void aFileOverTheLimitGets413WithTheLimitInTheMessage() throws Exception {
    Reply reply = post(3 * 1024 * 1024);

    assertEquals(413, reply.status());
    assertTrue(
        reply.body().contains("El archivo supera el tamaño máximo permitido de 1 MB"),
        reply.body());
  }

  @Test
  void aMuchBiggerFileStillGetsTheAnswerInsteadOfAConnectionCut() throws Exception {
    // 20 MB contra un límite de 1 MB: Tomcat tiene que descartar el resto y responder igual.
    Reply reply = post(20 * 1024 * 1024);

    assertEquals(413, reply.status());
  }
}
