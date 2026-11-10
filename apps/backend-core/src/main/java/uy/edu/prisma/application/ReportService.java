package uy.edu.prisma.application;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.ImprovementPlanRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;

/**
 * Genera reportes (PDF/XLSX) de madurez para una evaluacion. El estilo (colores, tipografia,
 * layout) espeja el de la UI: mismo azul de marca y misma escala de colores por nivel de madurez
 * que MATURITY_LEVELS/EVALUATION_STATUS_CONFIG en el frontend (utils/constants.ts), para que un
 * reporte descargado se sienta parte de la misma aplicacion.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
  private static final int TOP_GAPS = 5;
  private static final int TOP_ACTIONS = 5;
  // Una acción muy larga no puede romper el límite de dos páginas del reporte ejecutivo.
  private static final int MAX_ACTION_CHARS = 240;

  // Paleta compartida PDF/Excel, igual a MATURITY_LEVELS del frontend.
  private static final Color BRAND = new Color(30, 64, 175); // blue-800
  private static final Color BRAND_LIGHT = new Color(219, 234, 254); // blue-100
  private static final Color SLATE_900 = new Color(15, 23, 42);
  private static final Color SLATE_500 = new Color(100, 116, 139);
  private static final Color SLATE_200 = new Color(226, 232, 240);
  private static final Color SLATE_50 = new Color(248, 250, 252);
  private static final Color DANGER = new Color(220, 38, 38); // red-600

  private final EvaluationRepository evalRepo;
  private final MaturityResultRepository matRepo;
  private final AuditObservationRepository obsRepo;
  private final EvidenceRepository evidenceRepo;
  private final CurrentUserService currentUser;
  private final AuditControlVerdictRepository verdictRepo;
  private final ImprovementPlanRepository planRepo;

  public ReportService(
      EvaluationRepository evalRepo,
      MaturityResultRepository matRepo,
      AuditObservationRepository obsRepo,
      EvidenceRepository evidenceRepo,
      CurrentUserService currentUser,
      AuditControlVerdictRepository verdictRepo,
      ImprovementPlanRepository planRepo) {
    this.evalRepo = evalRepo;
    this.matRepo = matRepo;
    this.obsRepo = obsRepo;
    this.evidenceRepo = evidenceRepo;
    this.currentUser = currentUser;
    this.verdictRepo = verdictRepo;
    this.planRepo = planRepo;
  }

  // =====================================================================================
  // PDF
  // =====================================================================================

  public byte[] generatePdf(UUID evaluationId) {
    Evaluation eval = loadApproved(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);
    List<String> auditors = auditorNames(evaluationId);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      // Margen superior de 54 pt en TODAS las paginas: ahi va el encabezado institucional que
      // repite PageEvent (organizacion, evaluacion, version del MCU, fecha y auditor).
      Document document = new Document(PageSize.A4, 36, 36, 54, 54);
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new PageEvent(eval, auditors));
      document.open();

      addPdfBanner(document);
      addPdfInfoBox(document, eval, auditors);
      addPdfKpiRow(document, eval, summary);
      if (!summary.topGaps().isEmpty()) {
        addPdfTopGaps(document, summary.topGaps());
      }
      addPdfResultsTable(document, results);

      document.close();
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el reporte PDF", e);
    }
  }

  private void addPdfBanner(Document document) throws DocumentException {
    addPdfBanner(
        document, "Reporte de Madurez en Ciberseguridad — Marco de Ciberseguridad AGESIC MCU 5.0");
  }

  private void addPdfBanner(Document document, String subtitleText) throws DocumentException {
    PdfPTable banner = new PdfPTable(1);
    banner.setWidthPercentage(100);
    PdfPCell cell = new PdfPCell();
    cell.setBackgroundColor(BRAND);
    cell.setBorder(Rectangle.NO_BORDER);
    cell.setPadding(16);

    Paragraph title =
        new Paragraph("PRISMA", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, Color.WHITE));
    title.setSpacingAfter(2);
    Paragraph subtitle =
        new Paragraph(subtitleText, FontFactory.getFont(FontFactory.HELVETICA, 10, BRAND_LIGHT));
    cell.addElement(title);
    cell.addElement(subtitle);
    banner.addCell(cell);
    document.add(banner);
  }

  private void addPdfInfoBox(Document document, Evaluation eval, List<String> auditors)
      throws DocumentException {
    PdfPTable info = new PdfPTable(4);
    info.setWidthPercentage(100);
    info.setWidths(new float[] {1.1f, 2f, 1.1f, 2f});
    info.setSpacingBefore(14);
    info.setSpacingAfter(14);

    addInfoCell(info, "ORGANIZACIÓN", eval.getOrganization().getName());
    addInfoCell(info, "EVALUACIÓN", eval.getName());
    addInfoCell(info, "CATÁLOGO", "MCU " + eval.getCatalogVersion());
    addInfoCell(info, "ESTADO", statusLabel(eval.getStatus()));
    addInfoCell(info, "ACTUALIZADA", eval.getUpdatedAt().format(DATE_FMT));
    addInfoCell(info, "GENERADO", OffsetDateTime.now().format(DATE_FMT));
    // PRI-367: con una evaluación archivada, la fecha de aprobación dice a qué versión aprobada
    // corresponde el reporte.
    int cells = 6;
    if (eval.getApprovedAt() != null) {
      addInfoCell(info, "APROBADA", eval.getApprovedAt().format(DATE_FMT));
      cells++;
    }
    // El auditor solo se muestra si la evaluacion ya tiene uno (ver auditorNames). Ocupa lo que
    // queda de la segunda fila: dos celdas, o una si ya está la fecha de aprobación.
    if (!auditors.isEmpty()) {
      int span = 4 - cells % 4;
      addInfoCell(info, "AUDITOR", String.join(", ", auditors), span);
      cells += span;
    }
    padRow(info, cells, 4);
    document.add(info);
  }

  private void addInfoCell(PdfPTable table, String label, String value) {
    addInfoCell(table, label, value, 1);
  }

  private void addInfoCell(PdfPTable table, String label, String value, int colspan) {
    PdfPCell cell = new PdfPCell();
    cell.setColspan(colspan);
    cell.setBorder(Rectangle.BOTTOM);
    cell.setBorderColor(SLATE_200);
    cell.setPaddingTop(2);
    cell.setPaddingBottom(8);
    cell.setPaddingLeft(2);
    Paragraph labelP =
        new Paragraph(label, FontFactory.getFont(FontFactory.HELVETICA, 7, SLATE_500));
    Paragraph valueP =
        new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, SLATE_900));
    valueP.setSpacingBefore(1);
    cell.addElement(labelP);
    cell.addElement(valueP);
    table.addCell(cell);
  }

  private void addPdfKpiRow(Document document, Evaluation eval, Summary summary)
      throws DocumentException {
    PdfPTable kpis = new PdfPTable(3);
    kpis.setWidthPercentage(100);
    kpis.setSpacingAfter(16);
    kpis.addCell(
        kpiCard(
            "NIVEL DE MADUREZ GLOBAL",
            (eval.getGlobalMaturity() != null ? eval.getGlobalMaturity() : summary.avgCurrent())
                + " / 5",
            maturityColor(
                eval.getGlobalMaturity() != null
                    ? eval.getGlobalMaturity()
                    : summary.avgCurrent())));
    kpis.addCell(
        kpiCard("BRECHA PROMEDIO", String.format("%.1f niveles", summary.avgGap()), SLATE_900));
    kpis.addCell(
        kpiCard(
            "FUNCIONES CON BRECHA",
            summary.functionsWithGap() + " de " + summary.totalItems(),
            summary.functionsWithGap() > 0 ? DANGER : SLATE_900));
    document.add(kpis);
  }

  private PdfPCell kpiCard(String label, String value, Color valueColor) {
    PdfPCell cell = new PdfPCell();
    cell.setBackgroundColor(SLATE_50);
    cell.setBorder(Rectangle.BOX);
    cell.setBorderColor(SLATE_200);
    cell.setBorderWidth(0.5f);
    cell.setPadding(10);
    Paragraph labelP =
        new Paragraph(label, FontFactory.getFont(FontFactory.HELVETICA, 7, SLATE_500));
    Paragraph valueP =
        new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, valueColor));
    valueP.setSpacingBefore(3);
    cell.addElement(labelP);
    cell.addElement(valueP);
    return cell;
  }

  private void addPdfTopGaps(Document document, List<MaturityResult> topGaps)
      throws DocumentException {
    Paragraph heading =
        new Paragraph(
            "Principales Brechas", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900));
    heading.setSpacingAfter(6);
    document.add(heading);

    PdfPTable table = new PdfPTable(4);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {3.4f, 1f, 1f, 1f});
    table.setSpacingAfter(16);
    addTableHeader(table, "Subcategoría", "Actual", "Objetivo", "Brecha");
    boolean shaded = false;
    for (MaturityResult r : topGaps) {
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      addBodyCell(
          table,
          r.getFunctionName() + " › " + r.getSubcategoryName(),
          bg,
          Element.ALIGN_LEFT,
          SLATE_900);
      addLevelCell(table, r.getCurrentLevel(), bg);
      addBodyCell(table, String.valueOf(r.getTargetLevel()), bg, Element.ALIGN_CENTER, SLATE_900);
      addBodyCell(table, String.valueOf(r.getGap()), bg, Element.ALIGN_CENTER, DANGER);
      shaded = !shaded;
    }
    document.add(table);
  }

  private void addPdfResultsTable(Document document, List<MaturityResult> results)
      throws DocumentException {
    Paragraph heading =
        new Paragraph(
            "Detalle de Resultados por Subcategoría",
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900));
    heading.setSpacingAfter(6);
    document.add(heading);

    PdfPTable table = new PdfPTable(5);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {2f, 2f, 2.4f, 1f, 1f});
    table.setHeaderRows(1);
    addTableHeader(table, "Función", "Categoría", "Subcategoría", "Nivel", "Brecha");

    boolean shaded = false;
    for (MaturityResult r : results) {
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      addBodyCell(table, r.getFunctionName(), bg, Element.ALIGN_LEFT, SLATE_900);
      addBodyCell(table, r.getCategoryName(), bg, Element.ALIGN_LEFT, SLATE_900);
      addBodyCell(table, r.getSubcategoryName(), bg, Element.ALIGN_LEFT, SLATE_900);
      addLevelCell(table, r.getCurrentLevel(), bg);
      addBodyCell(
          table,
          r.getGap() > 0 ? String.valueOf(r.getGap()) : "—",
          bg,
          Element.ALIGN_CENTER,
          r.getGap() > 0 ? DANGER : SLATE_500);
      shaded = !shaded;
    }
    document.add(table);
  }

  private void addTableHeader(PdfPTable table, String... labels) {
    Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    for (String label : labels) {
      PdfPCell cell = new PdfPCell(new Phrase(label, font));
      cell.setBackgroundColor(BRAND);
      cell.setBorderColor(BRAND);
      cell.setHorizontalAlignment(Element.ALIGN_CENTER);
      cell.setPadding(6);
      table.addCell(cell);
    }
  }

  private void addBodyCell(
      PdfPTable table, String text, Color background, int align, Color textColor) {
    PdfPCell cell =
        new PdfPCell(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 9, textColor)));
    cell.setBackgroundColor(background);
    cell.setBorderColor(SLATE_200);
    cell.setBorderWidth(0.5f);
    cell.setHorizontalAlignment(align);
    cell.setPadding(6);
    table.addCell(cell);
  }

  /** Celda de nivel de madurez con una pastilla de color de fondo, igual que en la UI. */
  private void addLevelCell(PdfPTable table, int level, Color rowBackground) {
    PdfPCell cell = new PdfPCell();
    cell.setBackgroundColor(rowBackground);
    cell.setBorderColor(SLATE_200);
    cell.setBorderWidth(0.5f);
    cell.setHorizontalAlignment(Element.ALIGN_CENTER);
    cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
    cell.setPadding(4);

    PdfPTable badge = new PdfPTable(1);
    badge.setWidthPercentage(60);
    PdfPCell badgeCell =
        new PdfPCell(
            new Phrase(
                String.valueOf(level),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE)));
    badgeCell.setBackgroundColor(maturityColor(level));
    badgeCell.setBorder(Rectangle.NO_BORDER);
    badgeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
    badgeCell.setPadding(3);
    badge.addCell(badgeCell);
    cell.addElement(badge);
    table.addCell(cell);
  }

  /**
   * Encabezado institucional y pie en cada hoja del PDF. El encabezado repite los datos de
   * identidad del reporte (organizacion, evaluacion, version del MCU, fecha de generacion y, si lo
   * hay, el auditor) para que cualquier pagina suelta se identifique sola.
   */
  private static final class PageEvent extends PdfPageEventHelper {
    private final BaseFont baseFont;
    private final BaseFont boldFont;
    private final String generatedAt = OffsetDateTime.now().format(DATE_FMT);
    private final String headline;
    private final String detail;

    PageEvent(Evaluation eval, List<String> auditors) {
      try {
        this.baseFont =
            BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        this.boldFont =
            BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      this.headline = eval.getOrganization().getName() + " · " + eval.getName();
      this.detail =
          "MCU "
              + eval.getCatalogVersion()
              + " · "
              + generatedAt
              + (auditors.isEmpty() ? "" : " · Auditor: " + String.join(", ", auditors));
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte cb = writer.getDirectContent();
      float width = document.right() - document.left();
      float top = document.top();

      cb.saveState();
      cb.setColorFill(SLATE_900);
      cb.beginText();
      cb.setFontAndSize(boldFont, 9);
      cb.setTextMatrix(document.left(), top + 30);
      cb.showText(fit(headline, boldFont, 9, width));
      cb.endText();
      cb.setColorFill(SLATE_500);
      cb.beginText();
      cb.setFontAndSize(baseFont, 8);
      cb.setTextMatrix(document.left(), top + 19);
      cb.showText(fit(detail, baseFont, 8, width));
      cb.endText();
      cb.setColorStroke(SLATE_200);
      cb.setLineWidth(0.5f);
      cb.moveTo(document.left(), top + 12);
      cb.lineTo(document.right(), top + 12);
      cb.stroke();

      String footer = "PRISMA · Generado " + generatedAt + " · Página " + writer.getPageNumber();
      cb.setColorFill(SLATE_500);
      cb.beginText();
      cb.setFontAndSize(baseFont, 8);
      cb.setTextMatrix(document.left(), document.bottom() - 20);
      cb.showText(footer);
      cb.endText();
      cb.restoreState();
    }

    /** Recorta el texto con «…» para que nunca se salga del ancho util de la pagina. */
    private static String fit(String text, BaseFont font, float size, float maxWidth) {
      if (font.getWidthPoint(text, size) <= maxWidth) {
        return text;
      }
      String cut = text;
      while (cut.length() > 1 && font.getWidthPoint(cut + "…", size) > maxWidth) {
        cut = cut.substring(0, cut.length() - 1);
      }
      return cut + "…";
    }
  }

  // =====================================================================================
  // Excel
  // =====================================================================================

  public byte[] generateExcel(UUID evaluationId) {
    Evaluation eval = loadApproved(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);
    List<String> auditors = auditorNames(evaluationId);

    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var props = workbook.getProperties().getCoreProperties();
      props.setTitle("Reporte de Madurez PRISMA - " + eval.getName());
      props.setCreator("PRISMA");

      addSummarySheet(workbook, eval, summary, auditors);
      addDetailSheet(workbook, results);
      // Encabezado de impresion: se repite en cada hoja impresa de ambas pestañas.
      String printHeader = printHeader(eval, auditors);
      for (Sheet sheet : workbook) {
        sheet.getHeader().setLeft(printHeader);
      }

      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el reporte Excel", e);
    }
  }

  private void addSummarySheet(
      XSSFWorkbook workbook, Evaluation eval, Summary summary, List<String> auditors) {
    Sheet sheet = workbook.createSheet("Resumen");
    sheet.setColumnWidth(0, 26 * 256);
    sheet.setColumnWidth(1, 40 * 256);

    XSSFCellStyle titleStyle =
        xlStyle(workbook, BRAND, Color.WHITE, true, 16, HorizontalAlignment.LEFT);
    XSSFCellStyle labelStyle =
        xlStyle(workbook, null, SLATE_500, false, 10, HorizontalAlignment.LEFT);
    XSSFCellStyle valueStyle =
        xlStyle(workbook, null, SLATE_900, true, 11, HorizontalAlignment.LEFT);
    XSSFCellStyle kpiLabelStyle =
        xlStyle(workbook, SLATE_50, SLATE_500, false, 9, HorizontalAlignment.LEFT);
    XSSFCellStyle kpiValueStyle =
        xlStyle(workbook, SLATE_50, BRAND, true, 14, HorizontalAlignment.LEFT);
    XSSFCellStyle sectionStyle =
        xlStyle(workbook, null, SLATE_900, true, 12, HorizontalAlignment.LEFT);

    int r = 0;
    Row title = sheet.createRow(r++);
    title.setHeightInPoints(28);
    setCell(title, 0, "PRISMA — Reporte de Madurez en Ciberseguridad", titleStyle);
    setCell(title, 1, "", titleStyle);
    sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
    r++;

    r =
        summaryRow(
            sheet, r, "Organización", eval.getOrganization().getName(), labelStyle, valueStyle);
    r = summaryRow(sheet, r, "Evaluación", eval.getName(), labelStyle, valueStyle);
    r = summaryRow(sheet, r, "Catálogo", "MCU " + eval.getCatalogVersion(), labelStyle, valueStyle);
    r = summaryRow(sheet, r, "Estado", statusLabel(eval.getStatus()), labelStyle, valueStyle);
    if (eval.getApprovedAt() != null) {
      String approvedAt = eval.getApprovedAt().format(DATE_FMT);
      r = summaryRow(sheet, r, "Aprobada", approvedAt, labelStyle, valueStyle);
    }
    if (!auditors.isEmpty()) {
      r = summaryRow(sheet, r, "Auditor", String.join(", ", auditors), labelStyle, valueStyle);
    }
    r =
        summaryRow(
            sheet, r, "Generado", OffsetDateTime.now().format(DATE_FMT), labelStyle, valueStyle);
    r++;

    int globalLevel =
        eval.getGlobalMaturity() != null ? eval.getGlobalMaturity() : summary.avgCurrent();
    r =
        summaryRow(
            sheet,
            r,
            "Nivel de Madurez Global",
            globalLevel + " / 5",
            kpiLabelStyle,
            kpiValueStyle);
    r =
        summaryRow(
            sheet,
            r,
            "Brecha Promedio",
            String.format("%.1f niveles", summary.avgGap()),
            kpiLabelStyle,
            kpiValueStyle);
    r =
        summaryRow(
            sheet,
            r,
            "Funciones con Brecha",
            summary.functionsWithGap() + " de " + summary.totalItems(),
            kpiLabelStyle,
            kpiValueStyle);
    r++;

    if (!summary.topGaps().isEmpty()) {
      Row sectionRow = sheet.createRow(r++);
      setCell(sectionRow, 0, "Principales Brechas", sectionStyle);
      r++;

      XSSFCellStyle headStyle =
          xlStyle(workbook, BRAND, Color.WHITE, true, 9, HorizontalAlignment.CENTER);
      Row head = sheet.createRow(r++);
      String[] cols = {"Subcategoría", "Actual", "Objetivo", "Brecha"};
      for (int i = 0; i < cols.length; i++) {
        setCell(head, i, cols[i], headStyle);
      }
      boolean shaded = false;
      for (MaturityResult g : summary.topGaps()) {
        Row row = sheet.createRow(r++);
        Color bg = shaded ? SLATE_50 : Color.WHITE;
        setCell(
            row,
            0,
            g.getFunctionName() + " › " + g.getSubcategoryName(),
            xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.LEFT));
        setCell(
            row,
            1,
            g.getCurrentLevel(),
            xlStyle(
                workbook,
                maturityColor(g.getCurrentLevel()),
                Color.WHITE,
                true,
                10,
                HorizontalAlignment.CENTER));
        setCell(
            row,
            2,
            g.getTargetLevel(),
            xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.CENTER));
        setCell(
            row,
            3,
            g.getGap(),
            xlStyle(workbook, bg, DANGER, true, 10, HorizontalAlignment.CENTER));
        shaded = !shaded;
      }
    }
  }

  private int summaryRow(
      Sheet sheet,
      int rowIdx,
      String label,
      String value,
      XSSFCellStyle labelStyle,
      XSSFCellStyle valueStyle) {
    Row row = sheet.createRow(rowIdx);
    setCell(row, 0, label, labelStyle);
    setCell(row, 1, value, valueStyle);
    return rowIdx + 1;
  }

  private void addDetailSheet(XSSFWorkbook workbook, List<MaturityResult> results) {
    Sheet sheet = workbook.createSheet("Detalle de Madurez");
    XSSFCellStyle headerStyle =
        xlStyle(workbook, BRAND, Color.WHITE, true, 10, HorizontalAlignment.CENTER);

    Row header = sheet.createRow(0);
    header.setHeightInPoints(20);
    String[] cols = {"Función", "Categoría", "Subcategoría", "Nivel Actual", "Objetivo", "Brecha"};
    for (int i = 0; i < cols.length; i++) {
      setCell(header, i, cols[i], headerStyle);
    }

    boolean shaded = false;
    int rowIdx = 1;
    for (MaturityResult r : results) {
      Row row = sheet.createRow(rowIdx++);
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      XSSFCellStyle textStyle =
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.LEFT);
      XSSFCellStyle centerStyle =
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.CENTER);
      setCell(row, 0, r.getFunctionName(), textStyle);
      setCell(row, 1, r.getCategoryName(), textStyle);
      setCell(row, 2, r.getSubcategoryName(), textStyle);
      setCell(
          row,
          3,
          r.getCurrentLevel(),
          xlStyle(
              workbook,
              maturityColor(r.getCurrentLevel()),
              Color.WHITE,
              true,
              10,
              HorizontalAlignment.CENTER));
      setCell(row, 4, r.getTargetLevel(), centerStyle);
      setCell(
          row,
          5,
          r.getGap(),
          xlStyle(
              workbook,
              bg,
              r.getGap() > 0 ? DANGER : SLATE_500,
              r.getGap() > 0,
              10,
              HorizontalAlignment.CENTER));
      shaded = !shaded;
    }

    for (int i = 0; i < cols.length; i++) {
      sheet.autoSizeColumn(i);
      sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 42 * 256));
    }
    sheet.createFreezePane(0, 1);
  }

  private void setCell(Row row, int col, String value, CellStyle style) {
    var cell = row.createCell(col);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private void setCell(Row row, int col, int value, CellStyle style) {
    var cell = row.createCell(col);
    cell.setCellValue(value);
    cell.setCellStyle(style);
  }

  private XSSFCellStyle xlStyle(
      XSSFWorkbook workbook,
      Color background,
      Color fontColor,
      boolean bold,
      int size,
      HorizontalAlignment align) {
    XSSFCellStyle style = workbook.createCellStyle();
    if (background != null) {
      style.setFillForegroundColor(new XSSFColor(background, null));
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }
    style.setBorderBottom(BorderStyle.THIN);
    style.setBottomBorderColor(new XSSFColor(SLATE_200, null));
    style.setAlignment(align);
    style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
    XSSFFont font = workbook.createFont();
    font.setBold(bold);
    font.setFontHeightInPoints((short) size);
    if (fontColor != null) {
      font.setColor(new XSSFColor(fontColor, null));
    }
    style.setFont(font);
    return style;
  }

  // =====================================================================================
  // Informe preliminar (RF-AUD-11): hallazgos de auditoría antes de aprobar -- estado de los
  // controles revisados, observaciones del auditor y subcategorías con brecha sin evidencia.
  // =====================================================================================

  // =====================================================================================
  // Reporte ejecutivo (RF-REP-01)
  // =====================================================================================

  /**
   * Reporte ejecutivo gerencial: una o dos páginas con la identidad de la evaluación, el perfil
   * comunitario, la madurez global, el radar por función, las cinco mayores brechas y las cinco
   * acciones principales del plan de mejora. Es un reporte aparte del PDF de madurez, y solo existe
   * para evaluaciones aprobadas.
   */
  public byte[] generateExecutivePdf(UUID evaluationId) {
    Evaluation eval = loadApproved(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);
    List<String> auditors = auditorNames(evaluationId);
    List<ImprovementPlan> actions = topActions(evaluationId);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document document = new Document(PageSize.A4, 36, 36, 54, 54);
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new PageEvent(eval, auditors));
      document.open();

      addPdfBanner(
          document, "Reporte Ejecutivo de Madurez en Ciberseguridad — Marco AGESIC MCU 5.0");
      addExecutiveInfoBox(document, eval, auditors);
      addPdfKpiRow(document, eval, summary);
      addFunctionMaturity(document, writer, results);
      if (!summary.topGaps().isEmpty()) {
        addPdfTopGaps(document, summary.topGaps());
      }
      addExecutiveActions(document, actions);

      document.close();
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el reporte ejecutivo en PDF", e);
    }
  }

  private void addExecutiveInfoBox(Document document, Evaluation eval, List<String> auditors)
      throws DocumentException {
    PdfPTable info = new PdfPTable(4);
    info.setWidthPercentage(100);
    info.setWidths(new float[] {1.1f, 2f, 1.1f, 2f});
    info.setSpacingBefore(14);
    info.setSpacingAfter(14);

    addInfoCell(info, "ORGANIZACIÓN", eval.getOrganization().getName());
    addInfoCell(info, "EVALUACIÓN", eval.getName());
    addInfoCell(
        info,
        "PERFIL COMUNITARIO",
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getName()
            : "Sin perfil (catálogo completo)");
    addInfoCell(info, "CATÁLOGO", "MCU " + eval.getCatalogVersion());
    addInfoCell(info, "ESTADO", statusLabel(eval.getStatus()));
    addInfoCell(info, "ACTUALIZADA", eval.getUpdatedAt().format(DATE_FMT));
    addInfoCell(info, "GENERADO", OffsetDateTime.now().format(DATE_FMT));
    int cells = 7;
    // PRI-367: con una evaluación archivada, la fecha de aprobación dice a qué versión aprobada
    // corresponde el reporte.
    if (eval.getApprovedAt() != null) {
      addInfoCell(info, "APROBADA", eval.getApprovedAt().format(DATE_FMT));
      cells++;
    }
    // El auditor solo se muestra si la evaluación ya tiene uno (ver auditorNames).
    if (!auditors.isEmpty()) {
      addInfoCell(info, "AUDITOR", String.join(", ", auditors));
      cells++;
    }
    padRow(info, cells, 4);
    document.add(info);
  }

  /**
   * Completa la última fila con celdas vacías sin borde: si no, iText la completa con cajas con
   * borde que se ven como campos vacíos.
   */
  private static void padRow(PdfPTable table, int usedCells, int columns) {
    for (int i = usedCells % columns; i != 0 && i < columns; i++) {
      PdfPCell blank = new PdfPCell();
      blank.setBorder(Rectangle.NO_BORDER);
      table.addCell(blank);
    }
  }

  /** Nivel actual y objetivo medios de cada función, en el orden en que aparecen los resultados. */
  private static List<RadarChart.Axis> functionAxes(List<MaturityResult> results) {
    Map<String, List<MaturityResult>> byFunction = new LinkedHashMap<>();
    for (MaturityResult r : results) {
      byFunction
          .computeIfAbsent(
              r.getFunctionName() != null ? r.getFunctionName() : "Sin función",
              k -> new java.util.ArrayList<>())
          .add(r);
    }
    List<RadarChart.Axis> axes = new java.util.ArrayList<>();
    byFunction.forEach(
        (name, items) ->
            axes.add(
                new RadarChart.Axis(
                    name,
                    items.stream().mapToInt(MaturityResult::getCurrentLevel).average().orElse(0),
                    items.stream().mapToInt(MaturityResult::getTargetLevel).average().orElse(0))));
    return axes;
  }

  private void addFunctionMaturity(
      Document document, PdfWriter writer, List<MaturityResult> results)
      throws DocumentException, java.io.IOException {
    Paragraph heading =
        new Paragraph(
            "Madurez por Función", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900));
    heading.setSpacingAfter(6);
    document.add(heading);

    List<RadarChart.Axis> axes = functionAxes(results);
    if (axes.isEmpty()) {
      document.add(
          new Paragraph(
              "Sin resultados de madurez para graficar.",
              FontFactory.getFont(FontFactory.HELVETICA, 9, SLATE_500)));
      return;
    }
    if (axes.size() < 3) {
      // Con una o dos funciones un radar no tiene forma: se muestran los valores en una tabla.
      PdfPTable table = new PdfPTable(3);
      table.setWidthPercentage(100);
      table.setWidths(new float[] {3f, 1f, 1f});
      table.setSpacingAfter(16);
      addTableHeader(table, "Función", "Actual", "Objetivo");
      for (RadarChart.Axis axis : axes) {
        addBodyCell(table, axis.label(), Color.WHITE, Element.ALIGN_LEFT, SLATE_900);
        addBodyCell(
            table,
            String.format("%.1f", axis.current()),
            Color.WHITE,
            Element.ALIGN_CENTER,
            SLATE_900);
        addBodyCell(
            table,
            String.format("%.1f", axis.target()),
            Color.WHITE,
            Element.ALIGN_CENTER,
            SLATE_900);
      }
      document.add(table);
      return;
    }
    var radar = RadarChart.render(writer, axes, document.right() - document.left(), 215);
    radar.setSpacingAfter(12);
    document.add(radar);
  }

  /** Las cinco acciones principales del plan: por prioridad (alta primero) y fecha límite. */
  private List<ImprovementPlan> topActions(UUID evaluationId) {
    return planRepo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId).stream()
        .sorted(
            Comparator.comparing(ImprovementPlan::getPriority, Comparator.reverseOrder())
                .thenComparing(
                    ImprovementPlan::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ImprovementPlan::getCreatedAt))
        .limit(TOP_ACTIONS)
        .toList();
  }

  private void addExecutiveActions(Document document, List<ImprovementPlan> actions)
      throws DocumentException {
    // El título va como primera fila de la tabla y la tabla se mantiene junta: así el título no
    // queda solo al pie de una página con su tabla en la siguiente.
    PdfPTable table = new PdfPTable(5);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {0.9f, 4f, 1.5f, 1.2f, 1.3f});
    table.setSpacingAfter(8);
    table.setKeepTogether(true);
    PdfPCell title =
        new PdfPCell(
            new Phrase(
                "Acciones Principales del Plan de Mejora",
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900)));
    title.setColspan(5);
    title.setBorder(Rectangle.NO_BORDER);
    title.setPaddingLeft(0);
    title.setPaddingBottom(6);
    table.addCell(title);

    if (actions.isEmpty()) {
      // La sección no se omite: quien lee el reporte tiene que ver que no hay plan, no suponerlo.
      PdfPCell none =
          new PdfPCell(
              new Phrase(
                  "Sin acciones registradas.",
                  FontFactory.getFont(FontFactory.HELVETICA, 9, SLATE_500)));
      none.setColspan(5);
      none.setBorder(Rectangle.NO_BORDER);
      none.setPaddingLeft(0);
      table.addCell(none);
      document.add(table);
      return;
    }
    addTableHeader(table, "Prioridad", "Acción", "Responsable", "Fecha límite", "Estado");
    table.setHeaderRows(2);
    boolean shaded = false;
    for (ImprovementPlan plan : actions) {
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      addBodyCell(table, priorityLabel(plan.getPriority()), bg, Element.ALIGN_CENTER, SLATE_900);
      addBodyCell(table, limit(plan.getAction()), bg, Element.ALIGN_LEFT, SLATE_900);
      addBodyCell(
          table,
          plan.getResponsible() != null && !plan.getResponsible().isBlank()
              ? plan.getResponsible()
              : "—",
          bg,
          Element.ALIGN_LEFT,
          SLATE_900);
      addBodyCell(
          table,
          plan.getDueDate() != null ? plan.getDueDate().format(DATE_ONLY) : "—",
          bg,
          Element.ALIGN_CENTER,
          SLATE_900);
      addBodyCell(
          table,
          planStatusLabel(plan.getStatus()),
          bg,
          Element.ALIGN_CENTER,
          plan.getStatus() == ImprovementPlan.PlanStatus.OVERDUE ? DANGER : SLATE_900);
      shaded = !shaded;
    }
    document.add(table);
  }

  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private static String limit(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= MAX_ACTION_CHARS ? text : text.substring(0, MAX_ACTION_CHARS - 1) + "…";
  }

  private static String priorityLabel(ImprovementPlan.Priority priority) {
    return switch (priority) {
      case HIGH -> "Alta";
      case MEDIUM -> "Media";
      case LOW -> "Baja";
    };
  }

  private static String planStatusLabel(ImprovementPlan.PlanStatus status) {
    return switch (status) {
      case PENDING -> "Pendiente";
      // Etiquetas de RF-PLN-04: Pendiente / En curso / Realizado (Vencida la asigna el sistema).
      case IN_PROGRESS -> "En curso";
      case COMPLETED -> "Realizado";
      case OVERDUE -> "Vencida";
    };
  }

  public byte[] generatePreliminaryPdf(UUID evaluationId) {
    Evaluation eval = loadForAudit(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);
    List<AuditObservation> observations =
        obsRepo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId);
    List<MaturityResult> missingEvidence = missingEvidence(evaluationId, results);
    List<String> auditors = auditorNames(evaluationId);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      // Igual que el reporte final (PRI-311): encabezado institucional en todas las paginas.
      Document document = new Document(PageSize.A4, 36, 36, 54, 54);
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new PageEvent(eval, auditors));
      document.open();

      addPdfBanner(document);
      addPdfInfoBox(document, eval, auditors);
      addPdfKpiRow(document, eval, summary);
      addPdfObservations(document, observations);
      addPdfMissingEvidence(document, missingEvidence);
      addPdfResultsTable(document, results);

      document.close();
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el informe preliminar en PDF", e);
    }
  }

  public byte[] generatePreliminaryExcel(UUID evaluationId) {
    Evaluation eval = loadForAudit(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);
    List<AuditObservation> observations =
        obsRepo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId);
    List<MaturityResult> missingEvidence = missingEvidence(evaluationId, results);
    List<String> auditors = auditorNames(evaluationId);

    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var props = workbook.getProperties().getCoreProperties();
      props.setTitle("Informe Preliminar de Auditoría PRISMA - " + eval.getName());
      props.setCreator("PRISMA");

      addSummarySheet(workbook, eval, summary, auditors);
      addObservationsSheet(workbook, observations);
      addMissingEvidenceSheet(workbook, missingEvidence);
      addDetailSheet(workbook, results);
      // Encabezado de impresion en cada hoja, como en el reporte final (PRI-311).
      String printHeader = printHeader(eval, auditors);
      for (Sheet sheet : workbook) {
        sheet.getHeader().setLeft(printHeader);
      }

      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el informe preliminar en Excel", e);
    }
  }

  /**
   * Igual que {@link #load(UUID)}, pero exige que la evaluación haya sido aprobada: el reporte
   * final y el ejecutivo solo valen para una evaluación «Aprobada», o «Archivada» después de haber
   * sido aprobada (PRI-367: tiene {@code approvedAt}, así que el reporte refleja esa aprobación).
   * Una archivada que se descartó sin aprobar no tiene reporte. El preliminar, en cambio, solo en
   * auditoría (ver {@link #loadForAudit(UUID)}). El endpoint se puede llamar directo con el id de
   * cualquier evaluación, por eso se valida acá y no solo en la pantalla. Generar un reporte no
   * modifica la evaluación.
   */
  private Evaluation loadApproved(UUID evaluationId) {
    Evaluation eval = load(evaluationId);
    if (!isReportable(eval)) {
      throw new ConflictException(
          "El reporte final y el ejecutivo sólo pueden exportarse con la evaluación 'Aprobada', o"
              + " 'Archivada' después de haber sido aprobada (estado actual: "
              + statusLabel(eval.getStatus())
              + ")");
    }
    return eval;
  }

  private static boolean isReportable(Evaluation eval) {
    return eval.getStatus() == Evaluation.Status.APPROVED
        || (eval.getStatus() == Evaluation.Status.ARCHIVED && eval.getApprovedAt() != null);
  }

  /** Igual que {@link #load(UUID)}, pero exige que la evaluación esté "En Auditoría". */
  private Evaluation loadForAudit(UUID evaluationId) {
    Evaluation eval = load(evaluationId);
    if (eval.getStatus() != Evaluation.Status.IN_AUDIT) {
      throw new ConflictException(
          "El informe preliminar sólo puede exportarse con la evaluación en estado 'En"
              + " Auditoría' (estado actual: "
              + statusLabel(eval.getStatus())
              + ")");
    }
    return eval;
  }

  /**
   * Subcategorías con brecha (gap > 0) que no tienen ninguna evidencia cargada: ni evidencia
   * general (sin control asociado) ni evidencia de un control que pertenezca a esa subcategoría
   * (ver CatalogControl.effectiveSubcategories).
   */
  private List<MaturityResult> missingEvidence(UUID evaluationId, List<MaturityResult> results) {
    List<Evidence> evidences = evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(evaluationId);
    if (evidences.stream().anyMatch(e -> e.getControl() == null)) {
      // Hay evidencia general (no atada a un control puntual): no podemos afirmar que falte
      // evidencia en ninguna subcategoría puntual.
      return List.of();
    }
    Set<UUID> subcategoriesWithEvidence = new HashSet<>();
    for (Evidence e : evidences) {
      if (e.getControl() != null) {
        e.getControl()
            .effectiveSubcategories()
            .forEach(sc -> subcategoriesWithEvidence.add(sc.getId()));
      }
    }
    return results.stream()
        .filter(r -> r.getGap() > 0)
        .filter(r -> !subcategoriesWithEvidence.contains(r.getSubcategoryId()))
        .toList();
  }

  private void addPdfObservations(Document document, List<AuditObservation> observations)
      throws DocumentException {
    Paragraph heading =
        new Paragraph(
            "Observaciones de Auditoría",
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900));
    heading.setSpacingAfter(6);
    document.add(heading);

    if (observations.isEmpty()) {
      Paragraph empty =
          new Paragraph(
              "Sin observaciones registradas.",
              FontFactory.getFont(FontFactory.HELVETICA, 9, SLATE_500));
      empty.setSpacingAfter(16);
      document.add(empty);
      return;
    }

    PdfPTable table = new PdfPTable(4);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {1.2f, 3.6f, 1.2f, 1f});
    table.setSpacingAfter(16);
    addTableHeader(table, "Tipo", "Descripción", "Estado", "Fecha");
    boolean shaded = false;
    for (AuditObservation o : observations) {
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      addBodyCell(table, observationTypeLabel(o.getType()), bg, Element.ALIGN_LEFT, SLATE_900);
      addBodyCell(table, o.getDescription(), bg, Element.ALIGN_LEFT, SLATE_900);
      addBodyCell(
          table,
          observationStatusLabel(o.getStatus()),
          bg,
          Element.ALIGN_CENTER,
          o.getStatus() == AuditObservation.ObservationStatus.OPEN ? DANGER : SLATE_900);
      addBodyCell(table, o.getCreatedAt().format(DATE_FMT), bg, Element.ALIGN_CENTER, SLATE_500);
      shaded = !shaded;
    }
    document.add(table);
  }

  private void addPdfMissingEvidence(Document document, List<MaturityResult> missingEvidence)
      throws DocumentException {
    Paragraph heading =
        new Paragraph(
            "Evidencias Faltantes", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SLATE_900));
    heading.setSpacingAfter(6);
    document.add(heading);

    if (missingEvidence.isEmpty()) {
      Paragraph empty =
          new Paragraph(
              "No se detectaron subcategorías con brecha sin evidencia asociada.",
              FontFactory.getFont(FontFactory.HELVETICA, 9, SLATE_500));
      empty.setSpacingAfter(16);
      document.add(empty);
      return;
    }

    PdfPTable table = new PdfPTable(4);
    table.setWidthPercentage(100);
    table.setWidths(new float[] {3.4f, 1f, 1f, 1f});
    table.setSpacingAfter(16);
    addTableHeader(table, "Subcategoría", "Actual", "Objetivo", "Brecha");
    boolean shaded = false;
    for (MaturityResult r : missingEvidence) {
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      addBodyCell(
          table,
          r.getFunctionName() + " › " + r.getSubcategoryName(),
          bg,
          Element.ALIGN_LEFT,
          SLATE_900);
      addLevelCell(table, r.getCurrentLevel(), bg);
      addBodyCell(table, String.valueOf(r.getTargetLevel()), bg, Element.ALIGN_CENTER, SLATE_900);
      addBodyCell(table, String.valueOf(r.getGap()), bg, Element.ALIGN_CENTER, DANGER);
      shaded = !shaded;
    }
    document.add(table);
  }

  private void addObservationsSheet(XSSFWorkbook workbook, List<AuditObservation> observations) {
    Sheet sheet = workbook.createSheet("Observaciones");
    XSSFCellStyle headerStyle =
        xlStyle(workbook, BRAND, Color.WHITE, true, 10, HorizontalAlignment.CENTER);
    Row header = sheet.createRow(0);
    String[] cols = {"Tipo", "Descripción", "Estado", "Fecha"};
    for (int i = 0; i < cols.length; i++) {
      setCell(header, i, cols[i], headerStyle);
    }
    boolean shaded = false;
    int rowIdx = 1;
    for (AuditObservation o : observations) {
      Row row = sheet.createRow(rowIdx++);
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      XSSFCellStyle textStyle =
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.LEFT);
      setCell(row, 0, observationTypeLabel(o.getType()), textStyle);
      setCell(row, 1, o.getDescription(), textStyle);
      setCell(row, 2, observationStatusLabel(o.getStatus()), textStyle);
      setCell(row, 3, o.getCreatedAt().format(DATE_FMT), textStyle);
      shaded = !shaded;
    }
    for (int i = 0; i < cols.length; i++) {
      sheet.autoSizeColumn(i);
      sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 42 * 256));
    }
  }

  private void addMissingEvidenceSheet(
      XSSFWorkbook workbook, List<MaturityResult> missingEvidence) {
    Sheet sheet = workbook.createSheet("Evidencias Faltantes");
    XSSFCellStyle headerStyle =
        xlStyle(workbook, BRAND, Color.WHITE, true, 10, HorizontalAlignment.CENTER);
    Row header = sheet.createRow(0);
    String[] cols = {"Subcategoría", "Actual", "Objetivo", "Brecha"};
    for (int i = 0; i < cols.length; i++) {
      setCell(header, i, cols[i], headerStyle);
    }
    boolean shaded = false;
    int rowIdx = 1;
    for (MaturityResult r : missingEvidence) {
      Row row = sheet.createRow(rowIdx++);
      Color bg = shaded ? SLATE_50 : Color.WHITE;
      setCell(
          row,
          0,
          r.getFunctionName() + " › " + r.getSubcategoryName(),
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.LEFT));
      setCell(
          row,
          1,
          r.getCurrentLevel(),
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.CENTER));
      setCell(
          row,
          2,
          r.getTargetLevel(),
          xlStyle(workbook, bg, SLATE_900, false, 10, HorizontalAlignment.CENTER));
      setCell(
          row, 3, r.getGap(), xlStyle(workbook, bg, DANGER, true, 10, HorizontalAlignment.CENTER));
      shaded = !shaded;
    }
    for (int i = 0; i < cols.length; i++) {
      sheet.autoSizeColumn(i);
      sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 512, 42 * 256));
    }
  }

  private static final Map<AuditObservation.ObservationType, String> OBSERVATION_TYPE_LABELS =
      Map.of(
          AuditObservation.ObservationType.OBSERVATION, "Observación",
          AuditObservation.ObservationType.NON_CONFORMITY, "No Conformidad",
          AuditObservation.ObservationType.RECOMMENDATION, "Recomendación");

  private static String observationTypeLabel(AuditObservation.ObservationType type) {
    return OBSERVATION_TYPE_LABELS.getOrDefault(type, type.name());
  }

  private static final Map<AuditObservation.ObservationStatus, String> OBSERVATION_STATUS_LABELS =
      Map.of(
          AuditObservation.ObservationStatus.OPEN, "Abierta",
          AuditObservation.ObservationStatus.IN_PROGRESS, "En Progreso",
          AuditObservation.ObservationStatus.RESOLVED, "Resuelta",
          AuditObservation.ObservationStatus.CLOSED, "Cerrada");

  private static String observationStatusLabel(AuditObservation.ObservationStatus status) {
    return OBSERVATION_STATUS_LABELS.getOrDefault(status, status.name());
  }

  // =====================================================================================
  // Datos compartidos
  // =====================================================================================

  /**
   * Auditores que efectivamente trabajaron la evaluacion: autores de sus observaciones de auditoria
   * y de sus dictamenes por control. Vacio mientras nadie auditó, y entonces el reporte omite el
   * campo.
   */
  private List<String> auditorNames(UUID evaluationId) {
    TreeSet<String> names = new TreeSet<>();
    for (AuditObservation o : obsRepo.findByEvaluationIdOrderByCreatedAtDesc(evaluationId)) {
      addName(names, o.getCreatedBy());
    }
    for (AuditControlVerdict v : verdictRepo.findByEvaluationId(evaluationId)) {
      addName(names, v.getSetBy());
    }
    return List.copyOf(names);
  }

  private static void addName(TreeSet<String> names, User user) {
    if (user == null) {
      return;
    }
    String name =
        ((user.getFirstName() == null ? "" : user.getFirstName())
                + " "
                + (user.getLastName() == null ? "" : user.getLastName()))
            .trim();
    if (!name.isEmpty()) {
      names.add(name);
    }
  }

  /** Texto del encabezado de impresion de Excel; «&» se duplica porque POI lo usa como escape. */
  private static String printHeader(Evaluation eval, List<String> auditors) {
    String text =
        "PRISMA · "
            + eval.getOrganization().getName()
            + " · "
            + eval.getName()
            + " · MCU "
            + eval.getCatalogVersion()
            + " · "
            + OffsetDateTime.now().format(DATE_FMT)
            + (auditors.isEmpty() ? "" : " · Auditor: " + String.join(", ", auditors));
    return text.replace("&", "&&");
  }

  private Evaluation load(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return eval;
  }

  private List<MaturityResult> results(UUID evaluationId) {
    return matRepo.findByEvaluationId(evaluationId);
  }

  private Summary summarize(List<MaturityResult> results) {
    int totalItems = results.size();
    double avgCurrent =
        results.stream().mapToInt(MaturityResult::getCurrentLevel).average().orElse(0);
    double avgGap = results.stream().mapToInt(MaturityResult::getGap).average().orElse(0);
    long functionsWithGap =
        results.stream()
            .filter(r -> r.getGap() > 0)
            .map(MaturityResult::getFunctionId)
            .distinct()
            .count();
    List<MaturityResult> topGaps =
        results.stream()
            .filter(r -> r.getGap() > 0)
            .sorted(Comparator.comparingInt(MaturityResult::getGap).reversed())
            .limit(TOP_GAPS)
            .toList();
    return new Summary(totalItems, (int) Math.round(avgCurrent), avgGap, functionsWithGap, topGaps);
  }

  private record Summary(
      int totalItems,
      int avgCurrent,
      double avgGap,
      long functionsWithGap,
      List<MaturityResult> topGaps) {}

  private static Color maturityColor(int level) {
    return switch (level) {
      case 1 -> new Color(239, 68, 68); // red-500
      case 2 -> new Color(249, 115, 22); // orange-500
      case 3 -> new Color(234, 179, 8); // amber-500
      case 4 -> new Color(34, 197, 94); // green-500
      case 5 -> new Color(59, 130, 246); // blue-500
      default -> new Color(148, 163, 184); // slate-400 (sin evaluar)
    };
  }

  private static final Map<Evaluation.Status, String> STATUS_LABELS =
      Map.of(
          Evaluation.Status.DRAFT, "Borrador",
          Evaluation.Status.IN_PROGRESS, "En Curso",
          Evaluation.Status.READY_FOR_AUDIT, "Lista para Auditoría",
          Evaluation.Status.IN_AUDIT, "En Auditoría",
          Evaluation.Status.APPROVED, "Aprobada",
          Evaluation.Status.RETURNED, "Devuelta",
          Evaluation.Status.ARCHIVED, "Archivada");

  private static String statusLabel(Evaluation.Status status) {
    return STATUS_LABELS.getOrDefault(status, status.name());
  }
}
