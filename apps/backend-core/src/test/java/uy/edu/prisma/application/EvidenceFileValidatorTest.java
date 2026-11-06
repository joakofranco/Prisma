package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import uy.edu.prisma.config.EvidenceConfig.EvidenceProperties;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;

class EvidenceFileValidatorTest {

  static final List<String> DEFAULT_FORMATS =
      List.of("pdf", "zip", "docx", "txt", "md", "markdown", "png", "jpg", "jpeg");

  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D
  };
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
  private static final byte[] EXE = {0x4D, 0x5A, (byte) 0x90, 0, 3, 0, 0, 0};

  private final EvidenceFileValidator validator = validatorFor(DEFAULT_FORMATS);

  static EvidenceFileValidator validatorFor(List<String> formats) {
    return new EvidenceFileValidator(new EvidenceProperties(formats, DataSize.ofMegabytes(50)));
  }

  static EvidenceFileValidator validatorWithMaxSize(DataSize maxSize) {
    return new EvidenceFileValidator(new EvidenceProperties(DEFAULT_FORMATS, maxSize));
  }

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

  private String messageOfRejection(String name, byte[] content) {
    return assertThrows(
            InvalidRequestException.class, () -> validator.validate(file(name, content)))
        .getMessage();
  }

  @Test
  void acceptsRealPdf() {
    assertEquals(
        EvidenceFileValidator.PDF, validator.validate(file("a.PDF", "%PDF-1.4\n...".getBytes())));
  }

  @Test
  void acceptsRealDocx() throws Exception {
    assertEquals(
        EvidenceFileValidator.DOCX,
        validator.validate(file("a.docx", zipWith("word/document.xml"))));
  }

  @Test
  void rejectsGenericZipRenamedToDocx() throws Exception {
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("a.docx", zipWith("payload.class"))));
  }

  @Test
  void acceptsUtf8TextAndMarkdown() {
    byte[] text = "Política de seguridad\n\tversión 2 ✓\r\n".getBytes(StandardCharsets.UTF_8);
    assertEquals(EvidenceFileValidator.TEXT, validator.validate(file("a.txt", text)));
    assertEquals(EvidenceFileValidator.MARKDOWN, validator.validate(file("a.md", text)));
  }

  @Test
  void rejectsBinaryRenamedToText() {
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("a.txt", new byte[] {0x7F, 'E', 'L', 'F', 0})));
  }

  @Test
  void rejectsPdfRenamedToText() {
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("a.txt", "%PDF-1.4".getBytes())));
  }

  @Test
  void rejectsNonPdfRenamedToPdf() {
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("a.pdf", new byte[] {0x4D, 0x5A, 0, 0, 0})));
  }

  @Test
  void rejectsDisallowedOrMissingExtension() {
    assertThrows(
        InvalidRequestException.class, () -> validator.validate(file("a.exe", "%PDF-".getBytes())));
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("sin-extension", "hola".getBytes())));
  }

  // ---- PRI-281: ZIP e imágenes ----

  @Test
  void acceptsRealPngAndJpeg() {
    assertEquals(EvidenceFileValidator.PNG, validator.validate(file("a.png", PNG)));
    assertEquals(EvidenceFileValidator.JPEG, validator.validate(file("a.jpg", JPEG)));
    assertEquals(EvidenceFileValidator.JPEG, validator.validate(file("a.JPEG", JPEG)));
  }

  @Test
  void rejectsPngRenamedToJpgAndExecutableRenamedToPng() {
    assertThrows(InvalidRequestException.class, () -> validator.validate(file("a.jpg", PNG)));
    assertThrows(InvalidRequestException.class, () -> validator.validate(file("a.png", EXE)));
    assertThrows(InvalidRequestException.class, () -> validator.validate(file("a.jpeg", EXE)));
  }

  @Test
  void rejectsSvgBecauseItCanCarryScripts() {
    byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>x</script></svg>".getBytes();
    assertTrue(messageOfRejection("a.svg", svg).contains("no permitido"));
  }

  @Test
  void acceptsValidZipWithAnyContent() throws Exception {
    assertEquals(
        EvidenceFileValidator.ZIP, validator.validate(file("a.zip", zipWith("carpeta/acta.pdf"))));
    // A diferencia del .docx no se exige ninguna parte interna.
    assertEquals(EvidenceFileValidator.ZIP, validator.validate(file("b.zip", zipWith("run.exe"))));
  }

  @Test
  void rejectsTextRenamedToZip() {
    assertThrows(
        InvalidRequestException.class,
        () -> validator.validate(file("a.zip", "hola, no soy un zip".getBytes())));
  }

  @Test
  void rejectsZipWithEntriesThatEscapeTheDirectory() throws Exception {
    for (String path : List.of("../fuera.txt", "a/../../fuera.txt", "/etc/passwd", "C:\\x.txt")) {
      String message = messageOfRejection("a.zip", zipWith(path));
      assertTrue(message.contains("salen del directorio"), path + " -> " + message);
    }
  }

  @Test
  void acceptsZipEntryNamesThatOnlyLookLikeTraversal() throws Exception {
    assertEquals(
        EvidenceFileValidator.ZIP,
        validator.validate(file("a.zip", zipWith("notas..v2/a..b.txt"))));
  }

  // ---- PRI-281: lista configurable ----

  @Test
  void onlyConfiguredExtensionsAreAccepted() throws Exception {
    EvidenceFileValidator onlyPdf = validatorFor(List.of("pdf"));

    assertEquals(EvidenceFileValidator.PDF, onlyPdf.validate(file("a.pdf", "%PDF-1".getBytes())));
    assertThrows(
        InvalidRequestException.class, () -> onlyPdf.validate(file("a.zip", zipWith("x.txt"))));
    assertThrows(InvalidRequestException.class, () -> onlyPdf.validate(file("a.png", PNG)));
  }

  @Test
  void rejectionMessageListsTheConfiguredExtensions() {
    EvidenceFileValidator reduced = validatorFor(List.of("pdf", ".ZIP"));

    String message =
        assertThrows(InvalidRequestException.class, () -> reduced.validate(file("a.exe", EXE)))
            .getMessage();

    assertEquals("Tipo de archivo no permitido. Extensiones aceptadas: .pdf, .zip", message);
  }

  @Test
  void defaultMessageNamesEveryAcceptedFormat() {
    String message = messageOfRejection("a.exe", EXE);

    for (String extension : DEFAULT_FORMATS) {
      assertTrue(message.contains("." + extension), extension + " falta en: " + message);
    }
  }

  @Test
  void configurationOrderAndCaseAreNormalized() {
    assertEquals(
        List.of("pdf", "png"),
        validatorFor(List.of(" PDF ", ".png", "", "pdf")).allowedExtensions());
  }

  @Test
  void unknownExtensionInConfigurationStopsTheStartup() {
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validatorFor(List.of("pdf", "svg", "exe")));

    assertTrue(error.getMessage().contains("svg"));
    assertTrue(error.getMessage().contains("exe"));
    assertTrue(error.getMessage().contains("soportados"));
  }

  @Test
  void emptyOrMissingConfigurationStopsTheStartup() {
    assertThrows(IllegalStateException.class, () -> validatorFor(List.of()));
    assertThrows(IllegalStateException.class, () -> validatorFor(null));
    assertThrows(IllegalStateException.class, () -> validatorFor(List.of(" ", ".")));
  }

  // ---- PRI-283: tamaño máximo ----

  @Test
  void aFileExactlyAtTheLimitIsAccepted() {
    EvidenceFileValidator small = validatorWithMaxSize(DataSize.ofBytes(8));

    small.assertWithinSizeLimit(file("a.pdf", new byte[8]));
    small.assertWithinSizeLimit(file("a.pdf", new byte[1]));
  }

  @Test
  void aFileOneByteOverTheLimitIsRejectedWithTheLimitInTheMessage() {
    EvidenceFileValidator small = validatorWithMaxSize(DataSize.ofMegabytes(5));

    PayloadTooLargeException error =
        assertThrows(
            PayloadTooLargeException.class,
            () ->
                small.assertWithinSizeLimit(
                    file("a.pdf", new byte[(int) DataSize.ofMegabytes(5).toBytes() + 1])));

    assertEquals("El archivo supera el tamaño máximo permitido de 5 MB", error.getMessage());
  }

  @Test
  void theLimitIsConfigurable() {
    assertEquals(50L * 1024 * 1024, validatorFor(DEFAULT_FORMATS).maxSizeBytes());
    assertEquals(5L * 1024 * 1024, validatorWithMaxSize(DataSize.ofMegabytes(5)).maxSizeBytes());
  }

  @Test
  void theLimitIsDescribedInTheLargestExactUnit() {
    assertEquals(
        "50 MB",
        new EvidenceProperties(List.of("pdf"), DataSize.ofMegabytes(50)).maxSizeDescription());
    assertEquals(
        "512 KB",
        new EvidenceProperties(List.of("pdf"), DataSize.ofKilobytes(512)).maxSizeDescription());
    assertEquals(
        "1500 bytes",
        new EvidenceProperties(List.of("pdf"), DataSize.ofBytes(1500)).maxSizeDescription());
  }

  @Test
  void aMissingOrNonPositiveLimitStopsTheStartup() {
    assertThrows(
        IllegalStateException.class,
        () -> new EvidenceFileValidator(new EvidenceProperties(DEFAULT_FORMATS, null)));
    assertThrows(
        IllegalStateException.class,
        () ->
            new EvidenceFileValidator(
                new EvidenceProperties(DEFAULT_FORMATS, DataSize.ofBytes(0))));
  }

  @Test
  void onlyTheFormatsBackendAiReadsAreIndexable() {
    for (String mime :
        List.of(
            EvidenceFileValidator.PDF,
            EvidenceFileValidator.DOCX,
            EvidenceFileValidator.TEXT,
            EvidenceFileValidator.MARKDOWN)) {
      assertTrue(EvidenceFileValidator.isIndexable(mime), mime);
    }
    for (String mime :
        List.of(EvidenceFileValidator.ZIP, EvidenceFileValidator.PNG, EvidenceFileValidator.JPEG)) {
      assertFalse(EvidenceFileValidator.isIndexable(mime), mime);
    }
  }
}
