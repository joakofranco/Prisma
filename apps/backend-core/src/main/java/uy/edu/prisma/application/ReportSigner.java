package uy.edu.prisma.application;

import com.lowagie.text.pdf.PdfDate;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfSignature;
import com.lowagie.text.pdf.PdfSignatureAppearance;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ReportSigner {

  public static final String PRISMA_UNIT = "Sellado PRISMA";
  public static final String USER_UNIT = "Usuario PRISMA";
  public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

  private static final Logger log = LoggerFactory.getLogger(ReportSigner.class);
  private static final int CONTENTS_SIZE = 12_000;
  private static final int USER_KEY_BITS = 2048;
  private static final long USER_CERT_VALID_SECONDS = 24L * 3600;
  private static final long CLOCK_SKEW_SECONDS = 300;
  private static final SecureRandom RANDOM = new SecureRandom();

  static {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private final boolean enabled;
  private final PrivateKey sealKey;
  private final X509Certificate sealCertificate;
  private final X509Certificate rootCertificate;

  @Autowired
  public ReportSigner(
      @Value("${prisma.reports.signing.enabled:false}") boolean enabled,
      @Value("${prisma.reports.signing.keystore:}") String keystorePath,
      @Value("${prisma.reports.signing.keystore-password:}") String keystorePassword,
      @Value("${prisma.reports.signing.key-alias:prisma-report-seal}") String keyAlias) {
    this.enabled = enabled;
    if (!enabled) {
      log.warn("El sellado de PDFs está deshabilitado: los reportes se emiten sin firma.");
      this.sealKey = null;
      this.sealCertificate = null;
      this.rootCertificate = null;
      return;
    }
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
        store.load(in, keystorePassword.toCharArray());
      }
      this.sealKey = (PrivateKey) store.getKey(keyAlias, keystorePassword.toCharArray());
      Certificate[] chain = store.getCertificateChain(keyAlias);
      if (sealKey == null || chain == null || chain.length < 2) {
        throw new IllegalStateException(
            "El keystore debe tener la clave «"
                + keyAlias
                + "» con su cadena (certificado de sellado y CA raíz)");
      }
      this.sealCertificate = (X509Certificate) chain[0];
      this.rootCertificate = (X509Certificate) chain[chain.length - 1];
      sealCertificate.checkValidity();
      sealCertificate.verify(rootCertificate.getPublicKey());
    } catch (Exception e) {
      throw new IllegalStateException(
          "No se pudo cargar el certificado de sellado de PDFs ("
              + keystorePath
              + "): "
              + e.getMessage(),
          e);
    }
  }

  ReportSigner(PrivateKey sealKey, X509Certificate sealCertificate, X509Certificate root) {
    this.enabled = true;
    this.sealKey = sealKey;
    this.sealCertificate = sealCertificate;
    this.rootCertificate = root;
  }

  public boolean isEnabled() {
    return enabled;
  }

  X509Certificate sealCertificate() {
    return sealCertificate;
  }

  X509Certificate rootCertificate() {
    return rootCertificate;
  }

  public record UserIdentity(UUID id, String fullName, String email) {}

  byte[] sealAsPrisma(byte[] pdf, String reason) {
    return sign(pdf, sealKey, new X509Certificate[] {sealCertificate, rootCertificate}, reason);
  }

  byte[] signAsUser(byte[] pdf, UserIdentity user, String reason) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(USER_KEY_BITS, RANDOM);
      KeyPair pair = generator.generateKeyPair();
      X509Certificate userCertificate = issueUserCertificate(pair, user, Instant.now());
      return sign(
          pdf,
          pair.getPrivate(),
          new X509Certificate[] {userCertificate, sealCertificate, rootCertificate},
          reason);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo firmar el PDF con el usuario", e);
    }
  }

  X509Certificate issueUserCertificate(KeyPair pair, UserIdentity user, Instant now)
      throws Exception {
    X500Name subject =
        new X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.C, "UY")
            .addRDN(BCStyle.O, "PRISMA")
            .addRDN(BCStyle.OU, USER_UNIT)
            .addRDN(BCStyle.CN, user.fullName())
            .addRDN(BCStyle.E, user.email())
            .addRDN(BCStyle.SERIALNUMBER, user.id().toString())
            .build();
    JcaX509CertificateHolder issuer = new JcaX509CertificateHolder(sealCertificate);
    JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer.getSubject(),
            new BigInteger(127, RANDOM).add(BigInteger.ONE),
            Date.from(now.minusSeconds(CLOCK_SKEW_SECONDS)),
            Date.from(now.plusSeconds(USER_CERT_VALID_SECONDS)),
            subject,
            pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
    builder.addExtension(
        Extension.subjectKeyIdentifier,
        false,
        extensions.createSubjectKeyIdentifier(pair.getPublic()));
    builder.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        extensions.createAuthorityKeyIdentifier(sealCertificate));
    ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(sealKey);
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  private byte[] sign(byte[] pdf, PrivateKey key, X509Certificate[] chain, String reason) {
    try {
      PdfReader reader = new PdfReader(pdf);
      ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 2 * CONTENTS_SIZE + 4096);
      PdfStamper stamper = PdfStamper.createSignature(reader, out, '\0', null, true);
      PdfSignatureAppearance appearance = stamper.getSignatureAppearance();
      Calendar now = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
      appearance.setSignDate(now);
      appearance.setReason(reason);

      PdfSignature dictionary =
          new PdfSignature(PdfName.ADOBE_PPKLITE, new PdfName("adbe.pkcs7.detached"));
      dictionary.setReason(reason);
      dictionary.setDate(new PdfDate(now));
      dictionary.setName(commonName(chain[0]));
      appearance.setCryptoDictionary(dictionary);

      HashMap<PdfName, Integer> exclusions = new HashMap<>();
      exclusions.put(PdfName.CONTENTS, CONTENTS_SIZE * 2 + 2);
      appearance.preClose(exclusions);

      byte[] signed = detachedCms(appearance.getRangeStream().readAllBytes(), key, chain);
      if (signed.length > CONTENTS_SIZE) {
        throw new IOException("La firma excede el espacio reservado en el PDF");
      }
      byte[] padded = new byte[CONTENTS_SIZE];
      System.arraycopy(signed, 0, padded, 0, signed.length);
      PdfDictionary contents = new PdfDictionary();
      contents.put(PdfName.CONTENTS, new PdfString(padded).setHexWriting(true));
      appearance.close(contents);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo firmar el PDF", e);
    }
  }

  private static byte[] detachedCms(byte[] content, PrivateKey key, X509Certificate[] chain)
      throws Exception {
    CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
    ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(key);
    generator.addSignerInfoGenerator(
        new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
            .build(signer, chain[0]));
    generator.addCertificates(new JcaCertStore(List.copyOf(Arrays.asList(chain))));
    CMSSignedData data = generator.generate(new CMSProcessableByteArray(content), false);
    return data.getEncoded();
  }

  static String commonName(X509Certificate certificate) {
    try {
      var rdns = new JcaX509CertificateHolder(certificate).getSubject().getRDNs(BCStyle.CN);
      return rdns.length == 0 ? "" : rdns[0].getFirst().getValue().toString();
    } catch (Exception e) {
      return "";
    }
  }
}
