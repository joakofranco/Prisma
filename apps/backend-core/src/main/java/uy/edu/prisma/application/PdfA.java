package uy.edu.prisma.application;

import com.lowagie.text.Font;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfDate;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfString;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;

/**
 * Soporte para que los PDF de los reportes cumplan PDF/A-1b (RNF-12, PRI-338).
 *
 * <p>PDF/A-1b exige, entre otras cosas: todas las fuentes embebidas, metadatos XMP con la
 * identificación {@code pdfaid} (part 1, conformance B) coherentes con el diccionario Info, una
 * intención de salida con perfil de color, PDF 1.4 y nada de transparencia, cifrado ni contenido
 * dinámico. Las fuentes son Liberation Sans (licencia SIL OFL 1.1, métricas compatibles con
 * Helvetica) incluidas como recurso en {@code /fonts}.
 *
 * <p>Sellado criptográfico (PRI-314): la firma debe aplicarse sobre el PDF ya generado como
 * actualización incremental, para no invalidar la conformidad PDF/A.
 */
final class PdfA {

  private static final String REGULAR = "/fonts/LiberationSans-Regular.ttf";
  private static final String BOLD = "/fonts/LiberationSans-Bold.ttf";
  private static final String CREATOR_TOOL = "PRISMA";
  private static final String PRODUCER = "PRISMA (OpenPDF)";

  private static volatile byte[] regularBytes;
  private static volatile byte[] boldBytes;

  private PdfA() {}

  /** Fuente base embebida (subconjunto) para texto regular. */
  static BaseFont regularBase() {
    return base(REGULAR, regular());
  }

  /** Fuente base embebida (subconjunto) para texto en negrita. */
  static BaseFont boldBase() {
    return base(BOLD, bold());
  }

  /** Fuente para {@code Phrase}/{@code Paragraph}: reemplaza a {@code FontFactory.HELVETICA*}. */
  static Font font(boolean bold, float size, Color color) {
    return new Font(bold ? boldBase() : regularBase(), size, Font.NORMAL, color);
  }

  /**
   * Deja el escritor en modo PDF/A-1b. Se llama después de crearlo y antes de {@code
   * document.open()}; después hay que llamar a {@link #addOutputIntent(PdfWriter)}.
   */
  static void prepare(PdfWriter writer, String title, OffsetDateTime created) throws IOException {
    writer.setPdfVersion(PdfWriter.VERSION_1_4);
    writer.setPDFXConformance(PdfWriter.PDFA1B);

    var info = writer.getInfo();
    info.put(PdfName.TITLE, new PdfString(title, PdfObject.TEXT_UNICODE));
    info.put(PdfName.AUTHOR, new PdfString(CREATOR_TOOL, PdfObject.TEXT_UNICODE));
    info.put(PdfName.CREATOR, new PdfString(CREATOR_TOOL, PdfObject.TEXT_UNICODE));
    info.put(PdfName.PRODUCER, new PdfString(PRODUCER, PdfObject.TEXT_UNICODE));
    GregorianCalendar calendar = GregorianCalendar.from(created.toZonedDateTime());
    info.put(PdfName.CREATIONDATE, new PdfDate(calendar));
    info.put(PdfName.MODDATE, new PdfDate(calendar));

    writer.setXmpMetadata(xmp(title, created).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Intención de salida con el perfil sRGB. Se llama justo después de {@code document.open()}: el
   * escritor recién tiene cuerpo para agregar objetos cuando el documento está abierto.
   */
  static void addOutputIntent(PdfWriter writer) throws IOException {
    writer.setOutputIntents(
        "sRGB IEC61966-2.1",
        "sRGB IEC61966-2.1",
        "http://www.color.org",
        "sRGB IEC61966-2.1",
        ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
  }

  /** Paquete XMP con la identificación PDF/A-1b y los mismos datos que el diccionario Info. */
  static String xmp(String title, OffsetDateTime created) {
    String date = created.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    String safeTitle = escape(title);
    return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
        + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
        + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
        + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n"
        + "<pdfaid:part>1</pdfaid:part>\n"
        + "<pdfaid:conformance>B</pdfaid:conformance>\n"
        + "</rdf:Description>\n"
        + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
        + "<dc:format>application/pdf</dc:format>\n"
        + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">"
        + safeTitle
        + "</rdf:li></rdf:Alt></dc:title>\n"
        + "<dc:creator><rdf:Seq><rdf:li>"
        + CREATOR_TOOL
        + "</rdf:li></rdf:Seq></dc:creator>\n"
        + "</rdf:Description>\n"
        + "<rdf:Description rdf:about=\"\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n"
        + "<pdf:Producer>"
        + PRODUCER
        + "</pdf:Producer>\n"
        + "</rdf:Description>\n"
        + "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">\n"
        + "<xmp:CreatorTool>"
        + CREATOR_TOOL
        + "</xmp:CreatorTool>\n"
        + "<xmp:CreateDate>"
        + date
        + "</xmp:CreateDate>\n"
        + "<xmp:ModifyDate>"
        + date
        + "</xmp:ModifyDate>\n"
        + "</rdf:Description>\n"
        + "</rdf:RDF>\n"
        + "</x:xmpmeta>\n"
        + "<?xpacket end=\"w\"?>";
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static BaseFont base(String name, byte[] bytes) {
    try {
      // IDENTITY_H + EMBEDDED: Unicode completo (—, ·, …, ›, tildes) y subconjunto embebido.
      return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo cargar la fuente " + name, e);
    }
  }

  private static byte[] regular() {
    if (regularBytes == null) {
      regularBytes = load(REGULAR);
    }
    return regularBytes;
  }

  private static byte[] bold() {
    if (boldBytes == null) {
      boldBytes = load(BOLD);
    }
    return boldBytes;
  }

  private static byte[] load(String resource) {
    try (InputStream in = PdfA.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Falta el recurso de fuente " + resource);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("No se pudo leer la fuente " + resource, e);
    }
  }
}
