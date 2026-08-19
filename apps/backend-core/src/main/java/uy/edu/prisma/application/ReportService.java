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
import java.util.List;
import java.util.Map;
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
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.EvaluationRepository;
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
  private final CurrentUserService currentUser;

  public ReportService(
      EvaluationRepository evalRepo,
      MaturityResultRepository matRepo,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.matRepo = matRepo;
    this.currentUser = currentUser;
  }

  // =====================================================================================
  // PDF
  // =====================================================================================

  public byte[] generatePdf(UUID evaluationId) {
    Evaluation eval = load(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document document = new Document(PageSize.A4, 36, 36, 0, 54);
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new FooterEvent());
      document.open();

      addPdfBanner(document);
      addPdfInfoBox(document, eval);
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
        new Paragraph(
            "Reporte de Madurez en Ciberseguridad — Marco de Ciberseguridad AGESIC MCU 5.0",
            FontFactory.getFont(FontFactory.HELVETICA, 10, BRAND_LIGHT));
    cell.addElement(title);
    cell.addElement(subtitle);
    banner.addCell(cell);
    document.add(banner);
  }

  private void addPdfInfoBox(Document document, Evaluation eval) throws DocumentException {
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
    document.add(info);
  }

  private void addInfoCell(PdfPTable table, String label, String value) {
    PdfPCell cell = new PdfPCell();
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

  /** Pie de pagina simple (marca + fecha + numero de pagina) en cada hoja del PDF. */
  private static final class FooterEvent extends PdfPageEventHelper {
    private final BaseFont baseFont;
    private final String generatedAt = OffsetDateTime.now().format(DATE_FMT);

    FooterEvent() {
      try {
        this.baseFont =
            BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte cb = writer.getDirectContent();
      String text = "PRISMA · Generado " + generatedAt + " · Página " + writer.getPageNumber();
      cb.saveState();
      cb.setColorFill(SLATE_500);
      cb.beginText();
      cb.setFontAndSize(baseFont, 8);
      cb.setTextMatrix(document.leftMargin(), document.bottom() - 20);
      cb.showText(text);
      cb.endText();
      cb.restoreState();
    }
  }

  // =====================================================================================
  // Excel
  // =====================================================================================

  public byte[] generateExcel(UUID evaluationId) {
    Evaluation eval = load(evaluationId);
    List<MaturityResult> results = results(evaluationId);
    Summary summary = summarize(results);

    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      var props = workbook.getProperties().getCoreProperties();
      props.setTitle("Reporte de Madurez PRISMA - " + eval.getName());
      props.setCreator("PRISMA");

      addSummarySheet(workbook, eval, summary);
      addDetailSheet(workbook, results);

      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("No se pudo generar el reporte Excel", e);
    }
  }

  private void addSummarySheet(XSSFWorkbook workbook, Evaluation eval, Summary summary) {
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
  // Datos compartidos
  // =====================================================================================

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
