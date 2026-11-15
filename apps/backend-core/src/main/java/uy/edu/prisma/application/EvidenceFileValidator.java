package uy.edu.prisma.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.config.EvidenceConfig.EvidenceProperties;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;

/**
 * RF-EVI-02 y RF-EVI-03: verifica que la extensión de una evidencia esté permitida y que su firma
 * binaria real (magic bytes) coincida con ella. Previene subir, p. ej., un ejecutable renombrado a
 * ".pdf".
 *
 * <p>Hay un catálogo interno de formatos soportados (extensión, tipo y comprobación de contenido).
 * La configuración ({@code prisma.evidence.allowed-extensions}) elige un subconjunto de ese
 * catálogo; una extensión que no esté en él hace fallar el arranque. No se admite ningún formato
 * que pueda llevar código (por ejemplo SVG).
 */
@Component
final class EvidenceFileValidator {

  static final String PDF = "application/pdf";
  static final String DOCX =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  static final String TEXT = "text/plain";
  static final String MARKDOWN = "text/markdown";
  static final String ZIP = "application/zip";
  static final String PNG = "image/png";
  static final String JPEG = "image/jpeg";

  /** Formatos que backend-ai sabe indexar (app/rag/loaders.py). ZIP e imágenes quedan afuera. */
  private static final Set<String> INDEXABLE = Set.of(PDF, DOCX, TEXT, MARKDOWN);

  private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
  private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final int TEXT_SAMPLE = 8192;
  // Tope de entradas que se recorren en un ZIP: se leen solo los nombres, sin descomprimir, pero
  // un archivo con millones de entradas igual cuesta tiempo de CPU.
  private static final int MAX_ZIP_ENTRIES = 10_000;

  @FunctionalInterface
  private interface ContentCheck {
    boolean matches(MultipartFile file) throws IOException;
  }

  private record Format(String mime, ContentCheck check) {}

  private static final Map<String, Format> CATALOG = buildCatalog();

  private final Map<String, Format> allowed;
  private final EvidenceProperties properties;

  EvidenceFileValidator(EvidenceProperties properties) {
    Map<String, Format> chosen = new LinkedHashMap<>();
    List<String> unknown = new ArrayList<>();
    List<String> configured =
        properties.allowedExtensions() == null ? List.of() : properties.allowedExtensions();
    for (String raw : configured) {
      String extension = normalize(raw);
      if (extension.isEmpty()) {
        continue;
      }
      Format format = CATALOG.get(extension);
      if (format == null) {
        unknown.add(extension);
      } else {
        chosen.put(extension, format);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalStateException(
          "prisma.evidence.allowed-extensions tiene formatos no soportados: "
              + unknown
              + ". Los soportados son: "
              + CATALOG.keySet());
    }
    if (chosen.isEmpty()) {
      throw new IllegalStateException(
          "prisma.evidence.allowed-extensions no puede estar vacía. Los soportados son: "
              + CATALOG.keySet());
    }
    if (properties.maxSize() == null || properties.maxSize().toBytes() <= 0) {
      throw new IllegalStateException("prisma.evidence.max-size tiene que ser mayor que cero");
    }
    this.allowed = chosen;
    this.properties = properties;
  }

  private static Map<String, Format> buildCatalog() {
    Format text = new Format(TEXT, file -> isPlainText(head(file, TEXT_SAMPLE)));
    Format markdown = new Format(MARKDOWN, file -> isPlainText(head(file, TEXT_SAMPLE)));
    Format jpeg = new Format(JPEG, file -> startsWith(head(file, JPEG_MAGIC.length), JPEG_MAGIC));
    Map<String, Format> catalog = new LinkedHashMap<>();
    catalog.put(
        "pdf", new Format(PDF, file -> startsWith(head(file, PDF_MAGIC.length), PDF_MAGIC)));
    catalog.put("zip", new Format(ZIP, EvidenceFileValidator::isSafeZip));
    catalog.put("docx", new Format(DOCX, EvidenceFileValidator::isDocx));
    catalog.put("txt", text);
    catalog.put("md", markdown);
    catalog.put("markdown", markdown);
    catalog.put(
        "png", new Format(PNG, file -> startsWith(head(file, PNG_MAGIC.length), PNG_MAGIC)));
    catalog.put("jpg", jpeg);
    catalog.put("jpeg", jpeg);
    return catalog;
  }

  /** Extensiones permitidas, sin punto y en el orden configurado. */
  List<String> allowedExtensions() {
    return List.copyOf(allowed.keySet());
  }

  /** Tamaño máximo de una evidencia, en bytes (RF-EVI-04). */
  long maxSizeBytes() {
    return properties.maxSize().toBytes();
  }

  /**
   * Rechaza el archivo si pesa más que el límite. Se llama antes de leerlo y de tocar el
   * almacenamiento: la petición multipart ya tiene el mismo tope en Spring, pero esta es la regla
   * de negocio y la que da el mensaje.
   *
   * @throws PayloadTooLargeException si el archivo supera el máximo (HTTP 413)
   */
  void assertWithinSizeLimit(MultipartFile file) {
    if (file.getSize() > maxSizeBytes()) {
      throw new PayloadTooLargeException(
          "El archivo supera el tamaño máximo permitido de " + properties.maxSizeDescription());
    }
  }

  /** {@code true} si backend-ai puede indexar este tipo de archivo. */
  static boolean isIndexable(String mimeType) {
    return INDEXABLE.contains(mimeType);
  }

  /**
   * Valida el archivo y devuelve el MIME type real detectado (el que se debe persistir, en lugar
   * del Content-Type que declara el cliente).
   *
   * @throws InvalidRequestException si la extensión no está permitida o la firma no coincide
   */
  String validate(MultipartFile file) {
    String extension = extensionOf(file.getOriginalFilename());
    Format format = allowed.get(extension);
    if (format == null) {
      throw new InvalidRequestException(
          "Tipo de archivo no permitido. Extensiones aceptadas: "
              + allowed.keySet().stream().map(e -> "." + e).collect(Collectors.joining(", ")));
    }
    boolean matches;
    try {
      matches = format.check().matches(file);
    } catch (IOException e) {
      matches = false;
    }
    if (!matches) {
      throw new InvalidRequestException(
          "El contenido del archivo no corresponde a la extensión ." + extension);
    }
    return format.mime();
  }

  private static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.strip().toLowerCase(Locale.ROOT);
    return value.startsWith(".") ? value.substring(1) : value;
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

  // Un .zip puede contener cualquier cosa. La mitigación mínima (RISK-18) es revisar solo los
  // nombres de las entradas, sin descomprimir, y rechazar las rutas que salgan del directorio
  // ("../", rutas absolutas): una extracción posterior no podría escribir fuera de su carpeta.
  private static boolean isSafeZip(MultipartFile file) throws IOException {
    if (!startsWith(head(file, ZIP_MAGIC.length), ZIP_MAGIC)) {
      return false;
    }
    try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
      ZipEntry entry;
      int count = 0;
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > MAX_ZIP_ENTRIES) {
          throw new InvalidRequestException("El ZIP tiene más de " + MAX_ZIP_ENTRIES + " entradas");
        }
        if (escapesDirectory(entry.getName())) {
          throw new InvalidRequestException(
              "El ZIP contiene rutas que salen del directorio: " + entry.getName());
        }
      }
    }
    return true;
  }

  private static boolean escapesDirectory(String entryName) {
    String name = entryName.replace('\\', '/');
    if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
      return true;
    }
    for (String segment : name.split("/")) {
      if ("..".equals(segment)) {
        return true;
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
