package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.web.dto.Dto.ReportSignatureDto;
import uy.edu.prisma.web.dto.Dto.ReportVerificationDto;

class ReportSealingServiceTest {

  private final CurrentUserService currentUser = mock(CurrentUserService.class);
  private final AuditLogService auditLog = mock(AuditLogService.class);
  private final AuditLogRepository auditRepo = mock(AuditLogRepository.class);

  private ReportSigner signer;
  private ReportSealingService service;
  private User user;

  private static KeyPair rsa() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private static X509Certificate certificate(
      X500Name subject, X500Name issuer, KeyPair subjectKeys, KeyPair issuerKeys, boolean ca)
      throws Exception {
    long now = System.currentTimeMillis();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            new Date(now - 60_000),
            new Date(now + 3_600_000),
            subject,
            subjectKeys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign));
    return new JcaX509CertificateConverter()
        .getCertificate(
            builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate())));
  }

  private static ReportSigner newSigner(String rootName) throws Exception {
    KeyPair rootKeys = rsa();
    X500Name root = new X500Name("CN=" + rootName + ",O=PRISMA");
    X509Certificate rootCert = certificate(root, root, rootKeys, rootKeys, true);
    KeyPair sealKeys = rsa();
    X500Name seal = new X500Name("CN=PRISMA - Sellado de reportes,OU=Sellado PRISMA,O=PRISMA");
    X509Certificate sealCert = certificate(seal, root, sealKeys, rootKeys, true);
    return new ReportSigner(sealKeys.getPrivate(), sealCert, rootCert);
  }

  private static byte[] samplePdf() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document document = new Document();
    PdfWriter writer = PdfWriter.getInstance(document, out);
    PdfA.prepare(
        writer,
        "Reporte de prueba",
        OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    document.open();
    PdfA.addOutputIntent(writer);
    document.add(
        new Paragraph("Resultados de madurez", PdfA.font(false, 11, java.awt.Color.BLACK)));
    document.close();
    return out.toByteArray();
  }

  @BeforeEach
  void setUp() throws Exception {
    signer = newSigner("PRISMA Internal CA");
    service = new ReportSealingService(signer, currentUser, auditLog, auditRepo);
    user = new User();
    user.setId(UUID.randomUUID());
    user.setEmail("ana@org.uy");
    user.setFirstName("Ana");
    user.setLastName("Pérez");
    when(currentUser.currentUser()).thenReturn(Optional.of(user));
  }

  private ReportVerificationDto sealAndVerify(boolean registered) throws Exception {
    byte[] signed = service.seal(samplePdf(), "de madurez", UUID.randomUUID());
    ArgumentCaptor<String> resource = ArgumentCaptor.forClass(String.class);
    verify(auditLog).record(eq("SEAL_REPORT"), resource.capture(), any());
    assertEquals("report_pdf:" + ReportSealingService.sha256(signed), resource.getValue());
    if (registered) {
      when(auditRepo.findFirstByActionAndResourceOrderByCreatedAtDesc(
              "SEAL_REPORT", resource.getValue()))
          .thenReturn(
              Optional.of(
                  AuditLog.builder()
                      .userId(user.getId())
                      .action("SEAL_REPORT")
                      .resource(resource.getValue())
                      .build()));
    }
    return service.verify(signed);
  }

  @Test
  void aSealedPdfCarriesThePrismaAndTheUserSignatures() throws Exception {
    byte[] signed = service.seal(samplePdf(), "de madurez", UUID.randomUUID());

    PdfReader reader = new PdfReader(signed);
    assertEquals(2, reader.getAcroFields().getSignatureNames().size());
    assertTrue(new String(signed, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
  }

  @Test
  void verifyAcceptsADocumentSealedByPrismaAndSignedByTheRegisteredUser() throws Exception {
    ReportVerificationDto result = sealAndVerify(true);

    assertTrue(result.valid(), result.message() + " " + result.signatures());
    assertTrue(result.sealedByPrisma());
    assertTrue(result.signedByUser());
    assertTrue(result.intact());
    assertTrue(result.registered());
    assertTrue(result.userMatchesRegistry());
    assertEquals(2, result.signatures().size());
    ReportSignatureDto prisma = result.signatures().get(0);
    ReportSignatureDto signedByUser = result.signatures().get(1);
    assertEquals("PRISMA", prisma.role());
    assertFalse(prisma.coversWholeDocument());
    assertEquals("USER", signedByUser.role());
    assertEquals("Ana Pérez", signedByUser.signerName());
    assertEquals("ana@org.uy", signedByUser.signerEmail());
    assertEquals(user.getId().toString(), signedByUser.signerUserId());
    assertTrue(signedByUser.coversWholeDocument());
  }

  @Test
  void verifyReportsAValidSignatureWithoutRegistryAsNotValid() throws Exception {
    ReportVerificationDto result = sealAndVerify(false);

    assertFalse(result.valid());
    assertTrue(result.sealedByPrisma());
    assertTrue(result.signedByUser());
    assertFalse(result.registered());
    assertTrue(result.message().contains("no tiene registrada"));
  }

  @Test
  void verifyRejectsAUserDifferentFromTheRegisteredOne() throws Exception {
    byte[] signed = service.seal(samplePdf(), "de madurez", UUID.randomUUID());
    when(auditRepo.findFirstByActionAndResourceOrderByCreatedAtDesc(eq("SEAL_REPORT"), any()))
        .thenReturn(
            Optional.of(
                AuditLog.builder().userId(UUID.randomUUID()).action("SEAL_REPORT").build()));

    ReportVerificationDto result = service.verify(signed);

    assertFalse(result.valid());
    assertFalse(result.userMatchesRegistry());
  }

  @Test
  void verifyDetectsAnyChangeToTheSignedContent() throws Exception {
    byte[] signed = service.seal(samplePdf(), "de madurez", UUID.randomUUID());
    byte[] tampered = signed.clone();
    String text = new String(tampered, StandardCharsets.ISO_8859_1);
    int at = text.indexOf("Reporte de prueba");
    assertNotEquals(-1, at);
    tampered[at] = (byte) 'X';

    ReportVerificationDto result = service.verify(tampered);

    assertFalse(result.valid());
    assertFalse(result.intact());
  }

  @Test
  void verifyDetectsContentAppendedAfterTheLastSignature() throws Exception {
    byte[] signed = service.seal(samplePdf(), "de madurez", UUID.randomUUID());
    byte[] appended = new byte[signed.length + 10];
    System.arraycopy(signed, 0, appended, 0, signed.length);
    System.arraycopy(
        "\n%extra\n".getBytes(StandardCharsets.ISO_8859_1), 0, appended, signed.length, 8);

    ReportVerificationDto result = service.verify(appended);

    assertFalse(result.valid());
    assertFalse(result.intact());
  }

  @Test
  void verifyDoesNotTrustSignaturesFromAnotherAuthority() throws Exception {
    ReportSigner stranger = newSigner("Otra CA");
    ReportSealingService strangerService =
        new ReportSealingService(stranger, currentUser, auditLog, auditRepo);
    byte[] foreign = strangerService.seal(samplePdf(), "de madurez", UUID.randomUUID());

    ReportVerificationDto result = service.verify(foreign);

    assertFalse(result.valid());
    assertFalse(result.sealedByPrisma());
    assertFalse(result.signedByUser());
    assertTrue(result.signatures().stream().noneMatch(ReportSignatureDto::trusted));
  }

  @Test
  void aPdfWithoutSignaturesIsNotSealed() throws Exception {
    ReportVerificationDto result = service.verify(samplePdf());

    assertFalse(result.signed());
    assertFalse(result.valid());
    assertTrue(result.message().contains("no tiene firmas"));
  }

  @Test
  void verifyRejectsAFileThatIsNotAPdf() {
    assertThrows(
        InvalidRequestException.class,
        () -> service.verify("no soy un pdf".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void whenSigningIsDisabledThePdfIsReturnedUntouched() throws Exception {
    ReportSigner disabled = new ReportSigner(false, "", "", "prisma-report-seal");
    ReportSealingService off = new ReportSealingService(disabled, currentUser, auditLog, auditRepo);
    byte[] pdf = samplePdf();

    assertArrayEquals(pdf, off.seal(pdf, "de madurez", UUID.randomUUID()));
    assertFalse(off.verify(pdf).sealingAvailable());
  }

  @Test
  void aMissingKeystoreStopsTheStartupWhenSigningIsEnabled() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new ReportSigner(true, "/no/existe.p12", "x", "prisma-report-seal"));

    assertNotNull(error.getMessage());
    assertTrue(error.getMessage().contains("/no/existe.p12"));
  }
}
