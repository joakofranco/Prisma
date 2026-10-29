package uy.edu.prisma.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.domain.exception.InvalidRequestException;

/**
 * RF-EVI-03: verifica que la firma binaria real (magic bytes) de una evidencia coincida con la
 * extensión declarada, y que esta sea de un tipo permitido. Previene subir, p. ej., un ejecutable
 * renombrado a ".pdf". La lista blanca es la misma que indexa backend-ai (app/rag/loaders.py).
 */
final class EvidenceFileValidator {

  static final String PDF = "application/pdf";
  static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  static final String TEXT = "text/plain";
  static final String MARKDOWN = "text/markdown";

  private static final Map<String, String> ALLOWED =
      Map.of(
          "pdf", PDF,
          "docx", DOCX,
          "txt", TEXT,
          "md", MARKDOWN,
          "markdown", MARKDOWN);

  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final int TEXT_SAMPLE = 8192;

  private EvidenceFileValidator() {}

  /**
   * Valida el archivo y devuelve el MIME type real detectado (el que se debe persistir, en lugar
   * del Content-Type que declara el cliente).
   *
   * @throws InvalidRequestException si la extensión no está permitida o la firma no coincide
   */
  static String validate(MultipartFile file) {
    String extension = extensionOf(file.getOriginalFilename());
    String mime = ALLOWED.get(extension);
    if (mime == null) {
      throw new InvalidRequestException(
          "Tipo de archivo no permitido. Extensiones aceptadas: .pdf, .docx, .txt, .md");
    }
    boolean matches;
    try {
      matches =
          switch (mime) {
            case PDF -> startsWith(head(file, PDF_MAGIC.length), PDF_MAGIC);
            case DOCX -> isDocx(file);
            default -> isPlainText(head(file, TEXT_SAMPLE));
          };
    } catch (IOException e) {
      matches = false;
    }
    if (!matches) {
      throw new InvalidRequestException(
          "El contenido del archivo no corresponde a la extensión ." + extension);
    }
    return mime;
  }

  private static String extensionOf(String fileName) {
    if (fileName == null) {
      return "";
    }
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static byte[] head(MultipartFile file, int n) throws IOException {
    try (InputStream in = file.getInputStream()) {
      return in.readNBytes(n);
    }
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return data.length >= prefix.length
        && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
  }

  // Un .docx es un ZIP (OOXML): además de la firma PK, se exige la parte word/document.xml para no
  // aceptar cualquier ZIP (o .jar/.apk) renombrado.
  private static boolean isDocx(MultipartFile file) throws IOException {
    if (!startsWith(head(file, ZIP_MAGIC.length), ZIP_MAGIC)) {
      return false;
    }
    try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if ("word/document.xml".equals(entry.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  // Texto plano: no tiene firma, así que se rechaza todo lo que tenga bytes de control típicos de
  // binarios (NUL, etc.) o la firma de un formato binario conocido.
  private static boolean isPlainText(byte[] sample) {
    if (startsWith(sample, PDF_MAGIC) || startsWith(sample, ZIP_MAGIC)) {
      return false;
    }
    int start = startsWith(sample, UTF8_BOM) ? UTF8_BOM.length : 0;
    for (int i = start; i < sample.length; i++) {
      int b = sample[i] & 0xFF;
      boolean allowedControl = b == '\t' || b == '\n' || b == '\r' || b == '\f';
      if ((b < 0x20 && !allowedControl) || b == 0x7F) {
        return false;
      }
    }
    return true;
  }
}
