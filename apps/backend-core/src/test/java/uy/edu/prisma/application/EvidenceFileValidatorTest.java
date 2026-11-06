package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import uy.edu.prisma.domain.exception.InvalidRequestException;

class EvidenceFileValidatorTest {

  private static MockMultipartFile file(String name, byte[] content) {
    return new MockMultipartFile("file", name, "application/octet-stream", content);
  }

  private static byte[] zipWith(String entryName) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry(entryName));
      zip.write("<xml/>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  @Test
  void acceptsRealPdf() {
    assertEquals(
        EvidenceFileValidator.PDF,
        EvidenceFileValidator.validate(file("a.PDF", "%PDF-1.4\n...".getBytes())));
  }

  @Test
  void acceptsRealDocx() throws Exception {
    assertEquals(
        EvidenceFileValidator.DOCX,
        EvidenceFileValidator.validate(file("a.docx", zipWith("word/document.xml"))));
  }

  @Test
  void rejectsGenericZipRenamedToDocx() throws Exception {
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("a.docx", zipWith("payload.class"))));
  }

  @Test
  void acceptsUtf8TextAndMarkdown() {
    byte[] text = "Política de seguridad\n\tversión 2 ✓\r\n".getBytes(StandardCharsets.UTF_8);
    assertEquals(EvidenceFileValidator.TEXT, EvidenceFileValidator.validate(file("a.txt", text)));
    assertEquals(
        EvidenceFileValidator.MARKDOWN, EvidenceFileValidator.validate(file("a.md", text)));
  }

  @Test
  void rejectsBinaryRenamedToText() {
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("a.txt", new byte[] {0x7F, 'E', 'L', 'F', 0})));
  }

  @Test
  void rejectsPdfRenamedToText() {
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("a.txt", "%PDF-1.4".getBytes())));
  }

  @Test
  void rejectsNonPdfRenamedToPdf() {
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("a.pdf", new byte[] {0x4D, 0x5A, 0, 0, 0})));
  }

  @Test
  void rejectsDisallowedOrMissingExtension() {
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("a.exe", "%PDF-".getBytes())));
    assertThrows(
        InvalidRequestException.class,
        () -> EvidenceFileValidator.validate(file("sin-extension", "hola".getBytes())));
  }
}
