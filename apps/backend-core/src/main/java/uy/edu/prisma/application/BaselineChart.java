package uy.edu.prisma.application;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import uy.edu.prisma.web.dto.Dto.PlanBaselineDto;
import uy.edu.prisma.web.dto.Dto.PlanBaselineItemDto;

final class BaselineChart {

  record Slice(String label, Color color, int count) {}

  record Shift(String label, int days) {}

  static final int SHIFT_LIMIT = 8;

  private static final Color TEXT = new Color(15, 23, 42);
  private static final Color MUTED = new Color(100, 116, 139);
  private static final Color AXIS = new Color(203, 213, 225);
  private static final Color ON_TIME = new Color(22, 163, 74);
  private static final Color LATE = new Color(217, 119, 6);
  private static final Color DELAYED = new Color(220, 38, 38);
  private static final Color IN_TERM = new Color(37, 99, 235);
  private static final Color NO_DATE = new Color(148, 163, 184);

  private static final float BAR_HEIGHT = 14;
  private static final float ROW_HEIGHT = 15;
  private static final float LABEL_WIDTH = 170;
  private static final float VALUE_ROOM = 34;
  private static final float TITLE_SIZE = 8.5f;

  private BaselineChart() {}

  static List<Slice> slices(PlanBaselineDto baseline) {
    List<PlanBaselineItemDto> items = baseline.items();
    return List.of(
            new Slice("En plazo", ON_TIME, count(items, PlanBaseline.ON_TIME)),
            new Slice("Tarde", LATE, count(items, PlanBaseline.LATE)),
            new Slice("Atrasadas", DELAYED, count(items, PlanBaseline.DELAYED)),
            new Slice("En curso", IN_TERM, count(items, PlanBaseline.IN_TERM)),
            new Slice(
                "Sin fecha",
                NO_DATE,
                count(items, PlanBaseline.NO_CLOSE_DATE) + count(items, PlanBaseline.NO_DATE)))
        .stream()
        .filter(slice -> slice.count() > 0)
        .toList();
  }

  static List<Shift> shifts(PlanBaselineDto baseline) {
    return baseline.items().stream()
        .filter(i -> i.replanned() && i.dueDateShiftDays() != null && i.dueDateShiftDays() != 0)
        .sorted(
            Comparator.comparingInt((PlanBaselineItemDto i) -> Math.abs(i.dueDateShiftDays()))
                .reversed())
        .map(i -> new Shift(shiftLabel(i), i.dueDateShiftDays()))
        .toList();
  }

  static boolean canRender(PlanBaselineDto baseline) {
    return !baseline.items().isEmpty();
  }

  static Image render(PdfWriter writer, PlanBaselineDto baseline, float width)
      throws DocumentException, IOException {
    List<Slice> slices = slices(baseline);
    List<Shift> allShifts = shifts(baseline);
    List<Shift> shown = allShifts.stream().limit(SHIFT_LIMIT).toList();
    int hidden = allShifts.size() - shown.size();
    int rows = Math.max(1, shown.size());

    float complianceBlock = 14 + BAR_HEIGHT + 6 + 12;
    float shiftBlock = 14 + rows * ROW_HEIGHT + 12;
    float height = complianceBlock + 12 + shiftBlock;

    BaseFont font = PdfA.regularBase();
    BaseFont bold = PdfA.boldBase();
    PdfTemplate t = writer.getDirectContent().createTemplate(width, height);

    float y = height;
    y = drawCompliance(t, font, bold, slices, width, y);
    y -= 12;
    drawShifts(t, font, bold, shown, hidden, width, y);
    return Image.getInstance(t);
  }

  private static float drawCompliance(
      PdfTemplate t, BaseFont font, BaseFont bold, List<Slice> slices, float width, float top) {
    float y = top;
    text(t, bold, TITLE_SIZE, TEXT, "Cumplimiento contra la fecha original", 0, y - 9);
    y -= 14;
    float barBottom = y - BAR_HEIGHT;
    int total = slices.stream().mapToInt(Slice::count).sum();
    float x = 0;
    for (Slice slice : slices) {
      float w = width * slice.count() / total;
      t.saveState();
      t.setColorFill(slice.color());
      t.rectangle(x, barBottom, w, BAR_HEIGHT);
      t.fill();
      t.restoreState();
      if (w >= 16) {
        t.saveState();
        t.beginText();
        t.setColorFill(Color.WHITE);
        t.setFontAndSize(bold, 8);
        t.showTextAligned(
            PdfContentByte.ALIGN_CENTER,
            String.valueOf(slice.count()),
            x + w / 2,
            barBottom + 4,
            0);
        t.endText();
        t.restoreState();
      }
      x += w;
    }
    float legendY = barBottom - 6 - 8;
    float lx = 0;
    for (Slice slice : slices) {
      t.saveState();
      t.setColorFill(slice.color());
      t.rectangle(lx, legendY, 7, 7);
      t.fill();
      t.restoreState();
      String label = slice.label() + " " + slice.count();
      text(t, font, 8, TEXT, label, lx + 10, legendY + 0.5f);
      lx += 10 + font.getWidthPoint(label, 8) + 14;
    }
    return legendY;
  }

  private static void drawShifts(
      PdfTemplate t,
      BaseFont font,
      BaseFont bold,
      List<Shift> shown,
      int hidden,
      float width,
      float top) {
    float y = top;
    text(t, bold, TITLE_SIZE, TEXT, "Corrimiento de la fecha límite (días)", 0, y - 9);
    y -= 14;
    if (shown.isEmpty()) {
      text(t, font, 8, MUTED, "Ninguna acción cambió su fecha límite.", 0, y - 11);
      return;
    }

    int maxLate = shown.stream().mapToInt(Shift::days).filter(d -> d > 0).max().orElse(0);
    int maxEarly = -shown.stream().mapToInt(Shift::days).filter(d -> d < 0).min().orElse(0);
    float plotStart = LABEL_WIDTH + VALUE_ROOM;
    float plotWidth = width - plotStart - VALUE_ROOM;
    float unit = plotWidth / Math.max(1, maxLate + maxEarly);
    float zero = plotStart + maxEarly * unit;

    for (int i = 0; i < shown.size(); i++) {
      Shift shift = shown.get(i);
      float rowBottom = y - (i + 1) * ROW_HEIGHT;
      float barY = rowBottom + 3;
      float barH = ROW_HEIGHT - 6;
      float len = Math.abs(shift.days()) * unit;
      boolean late = shift.days() > 0;
      float barX = late ? zero : zero - len;

      text(t, font, 7.5f, TEXT, shorten(shift.label(), font, 7.5f, LABEL_WIDTH - 4), 0, barY + 1);
      t.saveState();
      t.setColorFill(late ? DELAYED : ON_TIME);
      t.rectangle(barX, barY, len, barH);
      t.fill();
      t.restoreState();

      String value = (late ? "+" : "") + shift.days();
      float valueW = bold.getWidthPoint(value, 7.5f);
      float valueX = late ? zero + len + 3 : zero - len - 3 - valueW;
      text(t, bold, 7.5f, TEXT, value, valueX, barY + 1);
    }

    float axisBottom = y - shown.size() * ROW_HEIGHT;
    t.saveState();
    t.setColorStroke(AXIS);
    t.setLineWidth(0.6f);
    t.moveTo(zero, axisBottom + 1);
    t.lineTo(zero, y);
    t.stroke();
    t.restoreState();

    String note =
        "Rojo: se atrasó · Verde: se adelantó"
            + (hidden > 0
                ? String.format(
                    Locale.ROOT,
                    " · se muestran las %d de mayor corrimiento (%d más en la tabla)",
                    shown.size(),
                    hidden)
                : "");
    text(t, font, 7, MUTED, note, 0, axisBottom - 9);
  }

  private static void text(
      PdfTemplate t, BaseFont font, float size, Color color, String value, float x, float y) {
    t.saveState();
    t.beginText();
    t.setColorFill(color);
    t.setFontAndSize(font, size);
    t.showTextAligned(PdfContentByte.ALIGN_LEFT, value, x, y, 0);
    t.endText();
    t.restoreState();
  }

  private static int count(List<PlanBaselineItemDto> items, String compliance) {
    return (int) items.stream().filter(i -> compliance.equals(i.compliance())).count();
  }

  private static String shiftLabel(PlanBaselineItemDto item) {
    if (item.controlCode() != null && !item.controlCode().isBlank()) {
      return item.controlCode();
    }
    return item.action();
  }

  private static String shorten(String value, BaseFont font, float size, float maxWidth) {
    if (font.getWidthPoint(value, size) <= maxWidth) {
      return value;
    }
    String cut = value;
    while (cut.length() > 1 && font.getWidthPoint(cut + "…", size) > maxWidth) {
      cut = cut.substring(0, cut.length() - 1);
    }
    return cut + "…";
  }
}
