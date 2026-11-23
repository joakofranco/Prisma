package uy.edu.prisma.application;

import com.lowagie.text.pdf.AcroFields;
import com.lowagie.text.pdf.PdfPKCS7;
import com.lowagie.text.pdf.PdfReader;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.springframework.stereotype.Service;
import uy.edu.prisma.domain.entity.AuditLog;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.repository.AuditLogRepository;
import uy.edu.prisma.web.dto.Dto.ReportSignatureDto;
import uy.edu.prisma.web.dto.Dto.ReportVerificationDto;

@Service
public class ReportSealingService {

  public static final String SEAL_ACTION = "SEAL_REPORT";
  public static final String RESOURCE_PREFIX = "report_pdf:";

  private static final String ROLE_PRISMA = "PRISMA";
  private static final String ROLE_USER = "USER";
  private static final String ROLE_UNKNOWN = "UNKNOWN";

  private final ReportSigner signer;
  private final CurrentUserService currentUser;
  private final AuditLogService auditLog;
  private final AuditLogRepository auditRepo;

  public ReportSealingService(
      ReportSigner signer,
      CurrentUserService currentUser,
      AuditLogService auditLog,
      AuditLogRepository auditRepo) {
    this.signer = signer;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
    this.auditRepo = auditRepo;
  }

  public byte[] seal(byte[] pdf, String reportKind, UUID evaluationId) {
    if (!signer.isEnabled()) {
      return pdf;
    }
    User user =
        currentUser
            .currentUser()
            .orElseThrow(
                () -> new IllegalStateException("No se puede sellar un reporte sin usuario"));
    String reason = "Reporte " + reportKind + " generado por PRISMA";
    byte[] sealed = signer.sealAsPrisma(pdf, reason);
    String fullName = (user.getFirstName() + " " + user.getLastName()).trim();
    byte[] signed =
        signer.signAsUser(
            sealed,
            new ReportSigner.UserIdentity(user.getId(), fullName, user.getEmail()),
            "Descargado por " + fullName);
    String hash = sha256(signed);
    auditLog.record(
        SEAL_ACTION,
        RESOURCE_PREFIX + hash,
        "{\"report\":\""
            + reportKind
            + "\",\"evaluationId\":\""
            + evaluationId
            + "\",\"signer\":\""
            + user.getId()
            + "\"}");
    return signed;
  }

  public ReportVerificationDto verify(byte[] pdf) {
    String hash = sha256(pdf);
    if (!signer.isEnabled()) {
      return result(
          false,
          List.of(),
          hash,
          Optional.empty(),
          "El sellado de PDFs no está habilitado en esta instalación: no hay certificado contra el"
              + " cual verificar.");
    }
    PdfReader reader;
    try {
      reader = new PdfReader(pdf);
    } catch (Exception e) {
      throw new InvalidRequestException("El archivo no es un PDF válido", "file");
    }
    List<ReportSignatureDto> signatures = new ArrayList<>();
    try {
      AcroFields fields = reader.getAcroFields();
      List<String> names = new ArrayList<>(fields.getSignatureNames());
      names.sort(Comparator.comparingInt(fields::getRevision));
      for (String name : names) {
        signatures.add(describe(fields, name));
      }
    } catch (Exception e) {
      throw new InvalidRequestException("No se pudieron leer las firmas del PDF", "file");
    }
    Optional<AuditLog> registry =
        auditRepo.findFirstByActionAndResourceOrderByCreatedAtDesc(
            SEAL_ACTION, RESOURCE_PREFIX + hash);
    return result(true, signatures, hash, registry, null);
  }

  private ReportVerificationDto result(
      boolean available,
      List<ReportSignatureDto> signatures,
      String hash,
      Optional<AuditLog> registry,
      String forcedMessage) {
    ReportSignatureDto prisma = first(signatures, ROLE_PRISMA);
    ReportSignatureDto user = first(signatures, ROLE_USER);
    boolean prismaOk = prisma != null && prisma.integrityValid() && prisma.trusted();
    boolean userOk = user != null && user.integrityValid() && user.trusted();
    ReportSignatureDto last = signatures.isEmpty() ? null : signatures.get(signatures.size() - 1);
    boolean intact =
        !signatures.isEmpty()
            && signatures.stream().allMatch(ReportSignatureDto::integrityValid)
            && last.coversWholeDocument();
    String registeredUser = registry.map(a -> a.getUserId()).map(UUID::toString).orElse(null);
    boolean userMatches =
        userOk && registeredUser != null && registeredUser.equals(user.signerUserId());
    boolean valid = prismaOk && userOk && intact && registry.isPresent() && userMatches;
    String registeredAt =
        registry
            .map(AuditLog::getCreatedAt)
            .map(d -> d.withOffsetSameInstant(ZoneOffset.UTC).toString())
            .orElse(null);
    String message =
        forcedMessage != null
            ? forcedMessage
            : message(signatures, prismaOk, userOk, intact, registry.isPresent(), userMatches);
    return new ReportVerificationDto(
        available,
        !signatures.isEmpty(),
        prismaOk,
        userOk,
        intact,
        registry.isPresent(),
        registeredAt,
        registeredUser,
        userMatches,
        valid,
        hash,
        message,
        signatures);
  }

  private static String message(
      List<ReportSignatureDto> signatures,
      boolean prismaOk,
      boolean userOk,
      boolean intact,
      boolean registered,
      boolean userMatches) {
    if (signatures.isEmpty()) {
      return "El PDF no tiene firmas: no fue sellado por PRISMA.";
    }
    if (!intact) {
      return "El documento fue modificado después de firmarse o una firma no coincide con su"
          + " contenido.";
    }
    if (!prismaOk) {
      return "No hay una firma válida de PRISMA: el certificado no pertenece a esta plataforma.";
    }
    if (!userOk) {
      return "No hay una firma válida de un usuario de PRISMA.";
    }
    if (!registered) {
      return "Las firmas son válidas, pero PRISMA no tiene registrada la emisión de este"
          + " documento.";
    }
    if (!userMatches) {
      return "El usuario firmante no coincide con el registrado al emitir el documento.";
    }
    return "Documento íntegro: sellado por PRISMA y firmado por el usuario que lo generó.";
  }

  private static ReportSignatureDto first(List<ReportSignatureDto> signatures, String role) {
    return signatures.stream().filter(s -> role.equals(s.role())).findFirst().orElse(null);
  }

  private ReportSignatureDto describe(AcroFields fields, String name) throws Exception {
    PdfPKCS7 pkcs7 = fields.verifySignature(name);
    X509Certificate certificate = pkcs7.getSigningCertificate();
    boolean integrity = pkcs7.verify();
    boolean whole = fields.signatureCoversWholeDocument(name);
    Calendar signDate = pkcs7.getSignDate();
    boolean trusted = isTrusted(certificate, signDate);
    String role = trusted ? roleOf(certificate) : ROLE_UNKNOWN;
    return new ReportSignatureDto(
        role,
        ReportSigner.commonName(certificate),
        attribute(certificate, BCStyle.E),
        attribute(certificate, BCStyle.SERIALNUMBER),
        signDate == null
            ? null
            : OffsetDateTime.ofInstant(signDate.toInstant(), ZoneOffset.UTC).toString(),
        integrity,
        trusted,
        whole);
  }

  private boolean isTrusted(X509Certificate certificate, Calendar signDate) {
    try {
      X509Certificate seal = signer.sealCertificate();
      X509Certificate root = signer.rootCertificate();
      seal.verify(root.getPublicKey());
      root.verify(root.getPublicKey());
      if (certificate.equals(seal)) {
        return true;
      }
      certificate.verify(seal.getPublicKey());
      if (signDate != null) {
        certificate.checkValidity(signDate.getTime());
      }
      return certificate.getBasicConstraints() == -1;
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static String roleOf(X509Certificate certificate) {
    String unit = attribute(certificate, BCStyle.OU);
    if (ReportSigner.PRISMA_UNIT.equals(unit)) {
      return ROLE_PRISMA;
    }
    return ReportSigner.USER_UNIT.equals(unit) ? ROLE_USER : ROLE_UNKNOWN;
  }

  private static String attribute(
      X509Certificate certificate, org.bouncycastle.asn1.ASN1ObjectIdentifier oid) {
    try {
      RDN[] rdns = new JcaX509CertificateHolder(certificate).getSubject().getRDNs(oid);
      return rdns.length == 0 ? null : rdns[0].getFirst().getValue().toString();
    } catch (Exception e) {
      return null;
    }
  }

  static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
