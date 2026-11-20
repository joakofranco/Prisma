package uy.edu.prisma.application;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.web.dto.Dto.ComparedEvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationComparisonDto;
import uy.edu.prisma.web.dto.Dto.FunctionDeltaDto;
import uy.edu.prisma.web.dto.Dto.SubcategoryDeltaDto;

/**
 * RF-VIZ-06 (PRI-320): PDF del comparativo entre dos evaluaciones, para usarlo como insumo
 * (reunión, comité, informe). Mismo estilo que el reporte técnico (PRI-310): PDF/A con fuente
 * embebida, encabezado y «Página X de N». Usa {@link EvaluationComparisonService#compare}, así que
 * valida lo mismo (misma organización, acceso y evaluaciones finalizadas). Solo lee.
 */
@Service
@Transactional(readOnly = true)
public class ComparisonPdfService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  private static final Color BRAND = new Color(30, 58, 138);
  private static final Color SLATE_900 = new Color(15, 23, 42);
  private static final Color SLATE_700 = new Color(51, 65, 85);
  private static final Color SLATE_500 = new Color(71, 85, 105);
  private static final Color SLATE_200 = new Color(226, 232, 240);
  private static final Color SLATE_100 = new Color(241, 245, 249);
  private static final Color BAR_A = new Color(148, 163, 184);
  private static final Color BAR_B = new Color(37, 99, 235);
  private static final Color UP = new Color(4, 120, 87);
  private static final Color UP_BG = new Color(236, 253, 245);
  private static final Color DOWN = new Color(185, 28, 28);
  private static final Color DOWN_BG = new Color(254, 242, 242);

  private final EvaluationComparisonService comparison;
  private final CurrentUserService currentUser;

  public ComparisonPdfService(
      EvaluationComparisonService comparison, CurrentUserService currentUser) {
    this.comparison = comparison;
    this.currentUser = currentUser;
  }

  public byte[] generate(UUID evaluationAId, UUID evaluationBId) {
    EvaluationComparisonDto c = comparison.compare(evaluationAId, evaluationBId);
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document doc = new Document(PageSize.A4, 40, 40, 60, 52);
      PdfWriter writer = PdfWriter.getInstance(doc, out);
      PdfA.prepare(
          writer,
          "Comparativo entre evaluaciones — " + c.organizationName(),
          OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS));
      writer.setPageEvent(new PageDecorator(c.organizationName()));
      doc.open();
      PdfA.addOutputIntent(writer);
      writeHeader(doc, c);
      writeSummary(doc, c);
      writeFunctions(doc, c);
      writeSubcategories(doc, c);
      writeNotes(doc, c);
      doc.close();
      return out.toByteArray();
    } catch (IOException | DocumentException e) {
      throw new IllegalStateException("No se pudo generar el comparativo en PDF", e);
    }
  }

  // ---- Secciones ----

  private void writeHeader(Document doc, EvaluationComparisonDto c) throws DocumentException {
    doc.add(new Paragraph("Comparativo entre evaluaciones", font(true, 18, SLATE_900)));
    Paragraph sub =
        new Paragraph(
            "Qué mejoró y qué empeoró entre dos evaluaciones de la misma organización"
                + " (RF-VIZ-06).",
            font(false, 9.5f, SLATE_500));
    sub.setSpacingAfter(10);
    doc.add(sub);

    PdfPTable info = new PdfPTable(2);
    info.setWidthPercentage(100);
    String by =
        currentUser
            .currentUser()
            .map(u -> " por " + (u.getFirstName() + " " + u.getLastName()).trim())
            .orElse("");
    infoCell(info, "ORGANIZACIÓN", c.organizationName());
    infoCell(
        info,
        "GENERADO",
        OffsetDateTime.now().atZoneSameInstant(ZoneId.systemDefault()).format(DATE_TIME) + by);
    infoCell(info, "EVALUACIÓN A (ANTERIOR)", describe(c.evaluationA()));
    infoCell(info, "EVALUACIÓN B (POSTERIOR)", describe(c.evaluationB()));
    info.setSpacingAfter(6);
    doc.add(info);

    if (!c.sameProfile()) {
      Paragraph warn =
          new Paragraph(
              "Las evaluaciones usan perfiles distintos ("
                  + c.evaluationA().profileName()
                  + " y "
                  + c.evaluationB().profileName()
                  + "): la madurez global no se compara directamente. Las subcategorías que están"
                  + " en una sola se marcan «solo en A» o «solo en B».",
              font(false, 9, new Color(120, 53, 15)));
      warn.setSpacingAfter(6);
      doc.add(warn);
    }
  }

  private void writeSummary(Document doc, EvaluationComparisonDto c) throws DocumentException {
    section(doc, "1. Resumen");
    ComparedEvaluationDto a = c.evaluationA();
    ComparedEvaluationDto b = c.evaluationB();
    PdfPTable cards = new PdfPTable(2);
    cards.setWidthPercentage(100);
    double maturityDelta = round(b.maturityAverage() - a.maturityAverage());
    card(
        cards,
        "MADUREZ GLOBAL",
        num(a.maturityAverage()) + " a " + num(b.maturityAverage()),
        signed(maturityDelta),
        maturityDelta,
        "Nivel " + level(a.globalMaturity()) + " a Nivel " + level(b.globalMaturity()));
    int controlsDelta = b.compliantControls() - a.compliantControls();
    card(
        cards,
        "CONTROLES CUMPLIDOS",
        a.compliantControls() + " a " + b.compliantControls(),
        signed(controlsDelta),
        controlsDelta,
        "de "
            + b.totalControls()
            + " · "
            + percent(a.compliancePercent())
            + " a "
            + percent(b.compliancePercent()));
    card(
        cards,
        "SUBCATEGORÍAS QUE SUBIERON",
        String.valueOf(c.subcategoriesUp()),
        "",
        c.subcategoriesUp(),
        "de " + c.subcategories().size());
    card(
        cards,
        "SUBCATEGORÍAS QUE BAJARON",
        String.valueOf(c.subcategoriesDown()),
        "",
        -c.subcategoriesDown(),
        c.subcategoriesSame() + " sin cambios");
    cards.setSpacingAfter(6);
    doc.add(cards);
    Paragraph reading = new Paragraph("", font(false, 9.5f, SLATE_700));
    reading.add(new Phrase("Lectura: ", font(true, 9.5f, SLATE_900)));
    reading.add(new Phrase(c.reading(), font(false, 9.5f, SLATE_700)));
    reading.setSpacingAfter(8);
    doc.add(reading);
  }

  private void writeFunctions(Document doc, EvaluationComparisonDto c) throws DocumentException {
    section(doc, "2. Cambio por función");
    PdfPTable table = new PdfPTable(new float[] {3f, 4f, 1.2f, 1.2f, 1.8f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    header(table, "Función", "A (gris) / B (azul)", "A", "B", "Cambio");
    for (FunctionDeltaDto f : c.functions()) {
      body(table, f.functionName(), Color.WHITE, false);
      // Barras dibujadas sobre la celda (sin tablas anidadas ni celdas vacías, que caen en
      // Helvetica no embebida y rompen la conformidad PDF/A).
      PdfPCell bars = new PdfPCell(new Phrase(" ", font(false, 8, Color.WHITE)));
      style(bars, Color.WHITE);
      bars.setMinimumHeight(18);
      bars.setCellEvent(new Bars(f.levelA(), f.levelB()));
      table.addCell(bars);
      body(table, f.levelA() != null ? num(f.levelA()) : "—", Color.WHITE, false);
      body(table, f.levelB() != null ? num(f.levelB()) : "—", Color.WHITE, false);
      deltaCell(table, f.delta(), Color.WHITE, true);
    }
    table.setSpacingAfter(4);
    doc.add(table);
    doc.add(
        new Paragraph(
            "Escala de 0 a 4: nivel promedio de las subcategorías de cada función.",
            font(false, 8, SLATE_500)));
  }

  private void writeSubcategories(Document doc, EvaluationComparisonDto c)
      throws DocumentException {
    section(doc, "3. Cambio por subcategoría");
    List<SubcategoryDeltaDto> changed =
        c.subcategories().stream().filter(r -> r.delta() == null || r.delta() != 0).toList();
    doc.add(
        new Paragraph(
            "Solo las subcategorías que cambiaron ("
                + changed.size()
                + " de "
                + c.subcategories().size()
                + "), agrupadas por función. Las que no cambiaron están en el anexo.",
            font(false, 9, SLATE_700)));
    if (changed.isEmpty()) {
      doc.add(new Paragraph("Ninguna subcategoría cambió de nivel.", font(false, 9, SLATE_500)));
      return;
    }
    PdfPTable table = new PdfPTable(new float[] {6.5f, 0.9f, 0.9f, 1.6f, 2f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    table.setSpacingBefore(6);
    header(table, "Subcategoría", "A", "B", "Cambio", "Objetivo");
    String currentFunction = null;
    for (SubcategoryDeltaDto r : changed) {
      if (!r.functionName().equals(currentFunction)) {
        currentFunction = r.functionName();
        PdfPCell group = new PdfPCell(new Phrase(currentFunction, font(true, 8.5f, SLATE_900)));
        group.setColspan(5);
        style(group, SLATE_100);
        table.addCell(group);
      }
      Color bg = r.delta() == null ? Color.WHITE : r.delta() > 0 ? UP_BG : DOWN_BG;
      body(table, (r.code() != null ? r.code() + " " : "") + r.name(), bg, false);
      body(table, r.levelA() != null ? String.valueOf(r.levelA()) : "—", bg, false);
      body(table, r.levelB() != null ? String.valueOf(r.levelB()) : "—", bg, false);
      if (r.delta() == null) {
        body(table, r.levelA() == null ? "solo en B" : "solo en A", bg, false);
      } else {
        deltaCell(table, r.delta().doubleValue(), bg, false);
      }
      body(table, target(r), bg, false);
    }
    table.setSpacingAfter(8);
    doc.add(table);
  }

  private void writeNotes(Document doc, EvaluationComparisonDto c) throws DocumentException {
    section(doc, "4. Notas");
    doc.add(
        new Paragraph(
            "Los niveles son los que PRISMA calculó para cada evaluación (modelo acumulativo: una"
                + " subcategoría alcanza el nivel N si cumple todos sus controles de nivel 1 a N)."
                + " La madurez global es el promedio sobre todas las subcategorías del catálogo."
                + (c.sameProfile()
                    ? " Ambas evaluaciones usan el perfil " + c.evaluationA().profileName() + "."
                    : ""),
            font(false, 9, SLATE_700)));
    List<SubcategoryDeltaDto> same =
        c.subcategories().stream().filter(r -> r.delta() != null && r.delta() == 0).toList();
    if (!same.isEmpty()) {
      section(doc, "Anexo · Subcategorías sin cambios");
      doc.add(
          new Paragraph(
              same.size()
                  + " subcategorías mantienen su nivel: "
                  + same.stream()
                      .map(r -> (r.code() != null ? r.code() : r.name()) + " (" + r.levelB() + ")")
                      .collect(Collectors.joining(", "))
                  + ".",
              font(false, 8.5f, SLATE_700)));
    }
  }

  // ---- Piezas ----

  private static String describe(ComparedEvaluationDto e) {
    return e.name()
        + " · "
        + e.profileName()
        + (e.approvedAt() != null ? " · aprobada " + e.approvedAt().format(DATE) : "");
  }

  private static String target(SubcategoryDeltaDto r) {
    if (r.targetLevel() == null) return "—";
    Integer reached = r.levelB() != null ? r.levelB() : r.levelA();
    int missing = r.targetLevel() - (reached != null ? reached : 0);
    return r.targetLevel() + (missing <= 0 ? " · alcanzado" : " · falta " + missing);
  }

  private static void section(Document doc, String title) throws DocumentException {
    Paragraph p = new Paragraph(title, font(true, 12, BRAND));
    p.setSpacingBefore(10);
    p.setSpacingAfter(5);
    doc.add(p);
  }

  private static void infoCell(PdfPTable table, String label, String value) {
    PdfPCell cell = new PdfPCell();
    cell.setBorder(Rectangle.NO_BORDER);
    cell.setPaddingBottom(5);
    cell.addElement(new Paragraph(label, font(true, 7, SLATE_500)));
    cell.addElement(new Paragraph(value, font(false, 9, SLATE_900)));
    table.addCell(cell);
  }

  private static void card(
      PdfPTable table, String label, String value, String delta, double sign, String foot) {
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(SLATE_200);
    cell.setPadding(7);
    cell.addElement(new Paragraph(label, font(true, 7, SLATE_500)));
    Paragraph v = new Paragraph("", font(true, 14, SLATE_900));
    v.add(
        new Phrase(value, font(true, 14, sign == 0 || !delta.isEmpty() ? SLATE_900 : color(sign))));
    if (!delta.isEmpty()) {
      v.add(new Phrase("  " + delta, font(true, 11, color(sign))));
    }
    cell.addElement(v);
    cell.addElement(new Paragraph(foot, font(false, 8, SLATE_500)));
    table.addCell(cell);
  }

  /** Dos barras horizontales (A arriba, B abajo) sobre una escala de 0 a 4. */
  private record Bars(Double levelA, Double levelB) implements PdfPCellEvent {
    @Override
    public void cellLayout(PdfPCell cell, Rectangle box, PdfContentByte[] canvases) {
      PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
      float left = box.getLeft() + 5;
      float width = box.getWidth() - 10;
      float middle = (box.getTop() + box.getBottom()) / 2;
      draw(cb, left, middle + 1.5f, width, levelA, BAR_A);
      draw(cb, left, middle - 6.5f, width, levelB, BAR_B);
    }

    private static void draw(
        PdfContentByte cb, float x, float y, float width, Double level, Color color) {
      cb.saveState();
      cb.setColorFill(SLATE_200);
      cb.rectangle(x, y, width, 5);
      cb.fill();
      if (level != null && level > 0) {
        cb.setColorFill(color);
        cb.rectangle(x, y, width * (float) Math.min(4, level) / 4f, 5);
        cb.fill();
      }
      cb.restoreState();
    }
  }

  private static void header(PdfPTable table, String... labels) {
    for (String label : labels) {
      PdfPCell cell = new PdfPCell(new Phrase(label, font(true, 8, Color.WHITE)));
      cell.setBackgroundColor(BRAND);
      cell.setBorderColor(BRAND);
      cell.setPadding(5);
      table.addCell(cell);
    }
  }

  private static void body(PdfPTable table, String text, Color bg, boolean bold) {
    PdfPCell cell = new PdfPCell(new Phrase(text, font(bold, 8.5f, SLATE_900)));
    style(cell, bg);
    table.addCell(cell);
  }

  /** {@code decimals}: promedios (dos decimales); si no, niveles enteros. */
  private static void deltaCell(PdfPTable table, Double delta, Color bg, boolean decimals) {
    String text =
        delta == null
            ? "—"
            : delta == 0
                ? "sin cambios"
                : decimals ? (delta > 0 ? "+" : "-") + num(Math.abs(delta)) : signed(delta);
    PdfPCell cell =
        new PdfPCell(
            new Phrase(
                text,
                font(delta != null && delta != 0, 8.5f, delta == null ? SLATE_500 : color(delta))));
    style(cell, bg);
    cell.setHorizontalAlignment(Element.ALIGN_LEFT);
    table.addCell(cell);
  }

  private static void style(PdfPCell cell, Color bg) {
    cell.setBackgroundColor(bg);
    cell.setBorder(Rectangle.BOTTOM);
    cell.setBorderColor(SLATE_200);
    cell.setPadding(5);
  }

  private static Color color(double sign) {
    return sign > 0 ? UP : sign < 0 ? DOWN : SLATE_500;
  }

  private static Font font(boolean bold, float size, Color color) {
    return PdfA.font(bold, size, color);
  }

  /** «+0,88», «-0,28», «+60», «0». */
  static String signed(double v) {
    if (v == 0) {
      return "0";
    }
    String n =
        v == Math.rint(v) && Math.abs(v) >= 1
            ? String.valueOf((long) Math.abs(v))
            : num(Math.abs(v));
    return (v > 0 ? "+" : v < 0 ? "-" : "") + n;
  }

  private static String num(double v) {
    return EvaluationComparisonService.number(v);
  }

  private static String percent(double v) {
    return String.format(java.util.Locale.forLanguageTag("es-UY"), "%.1f %%", v);
  }

  private static String level(Integer level) {
    return level == null ? "—" : String.valueOf(level);
  }

  private static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }

  /** Encabezado (desde la página 2) y pie con «Página X de N», como el reporte técnico. */
  private static final class PageDecorator extends PdfPageEventHelper {
    private final BaseFont regular = PdfA.regularBase();
    private final BaseFont bold = PdfA.boldBase();
    private final String organization;
    private PdfTemplate total;

    PageDecorator(String organization) {
      this.organization = organization;
    }

    @Override
    public void onOpenDocument(PdfWriter writer, Document document) {
      total = writer.getDirectContent().createTemplate(30, 12);
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte cb = writer.getDirectContent();
      float left = document.left();
      float right = document.right();
      cb.saveState();
      if (writer.getPageNumber() > 1) {
        float y = document.top() + 24;
        cb.beginText();
        cb.setColorFill(BRAND);
        cb.setFontAndSize(bold, 9);
        cb.showTextAligned(
            PdfContentByte.ALIGN_LEFT, "PRISMA · Comparativo entre evaluaciones", left, y, 0);
        cb.setColorFill(SLATE_500);
        cb.setFontAndSize(regular, 7.5f);
        cb.showTextAligned(PdfContentByte.ALIGN_RIGHT, organization, right, y, 0);
        cb.endText();
        cb.setColorStroke(BRAND);
        cb.setLineWidth(1.2f);
        cb.moveTo(left, document.top() + 16);
        cb.lineTo(right, document.top() + 16);
        cb.stroke();
      }
      float y = document.bottom() - 26;
      cb.setColorStroke(SLATE_200);
      cb.setLineWidth(0.5f);
      cb.moveTo(left, document.bottom() - 14);
      cb.lineTo(right, document.bottom() - 14);
      cb.stroke();
      String page = "Página " + writer.getPageNumber() + " de ";
      float pageWidth = regular.getWidthPoint(page, 7.5f);
      float x = right - pageWidth - 12;
      cb.beginText();
      cb.setColorFill(SLATE_500);
      cb.setFontAndSize(regular, 7.5f);
      cb.showTextAligned(
          PdfContentByte.ALIGN_LEFT, "PRISMA · Comparativo · " + organization, left, y, 0);
      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, page, x, y, 0);
      cb.endText();
      cb.addTemplate(total, x + pageWidth, y);
      cb.restoreState();
    }

    @Override
    public void onCloseDocument(PdfWriter writer, Document document) {
      total.beginText();
      total.setColorFill(SLATE_500);
      total.setFontAndSize(regular, 7.5f);
      total.setTextMatrix(0, 0);
      total.showText(String.valueOf(writer.getPageNumber() - 1));
      total.endText();
    }
  }
}
