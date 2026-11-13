package uy.edu.prisma.application;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.IOException;
import java.util.List;

/**
 * Gráfico radar del reporte ejecutivo (RF-REP-01): un eje por función, con el nivel actual y el
 * objetivo. Se dibuja como vector con {@link PdfContentByte} (anillos, ejes, polígonos y
 * etiquetas), sin dependencias nuevas ni imágenes rasterizadas: se imprime y se amplía sin perder
 * nitidez.
 */
final class RadarChart {

  /** Un eje del radar: la función y sus niveles medios (de 0 a {@link #MAX_LEVEL}). */
  record Axis(String label, double current, double target) {}

  // La escala de madurez del MCU va de 0 a 4 (el catálogo solo tiene controles de nivel 1 a 4), la
  // misma que muestra la web.
  static final int MAX_LEVEL = 4;

  private static final Color RING = new Color(226, 232, 240); // slate-200
  private static final Color AXIS = new Color(203, 213, 225); // slate-300
  private static final Color TEXT = new Color(15, 23, 42); // slate-900
  private static final Color MUTED = new Color(100, 116, 139); // slate-500
  private static final Color CURRENT = new Color(30, 64, 175); // blue-800
  private static final Color TARGET = new Color(22, 163, 74); // green-600
  // CURRENT al 25 % sobre blanco: el relleno del área sin transparencia (PDF/A-1).
  private static final Color CURRENT_FILL = new Color(199, 207, 235);

  private RadarChart() {}

  /**
   * Dibuja el radar en una plantilla y la devuelve como imagen vectorial lista para agregar al
   * documento.
   *
   * @throws IllegalArgumentException si hay menos de tres ejes (con uno o dos no hay polígono)
   */
  static Image render(PdfWriter writer, List<Axis> axes, float width, float height)
      throws DocumentException, IOException {
    return render(writer, axes, width, height, true);
  }

  /**
   * Igual que {@link #render(PdfWriter, List, float, float)}; con {@code showTarget = false} dibuja
   * solo el nivel actual (sin polígono ni leyenda del objetivo) y deja cada etiqueta en una línea,
   * para gráficos con muchos ejes, como el de madurez por categoría.
   */
  static Image render(
      PdfWriter writer, List<Axis> axes, float width, float height, boolean showTarget)
      throws DocumentException, IOException {
    if (axes.size() < 3) {
      throw new IllegalArgumentException("Un radar necesita al menos 3 ejes");
    }
    BaseFont font = PdfA.regularBase();
    BaseFont bold = PdfA.boldBase();
    PdfTemplate template = writer.getDirectContent().createTemplate(width, height);

    float cx = width / 2;
    float cy = height / 2 - 4;
    // Deja lugar a las etiquetas (a la izquierda y a la derecha) y a la leyenda.
    float radius = Math.min(width / 2 - 135, height / 2 - 26);
    int n = axes.size();

    // PDF/A-1 prohíbe la transparencia: el área del nivel actual se rellena con un color sólido
    // claro (CURRENT mezclado con blanco al 25 %), por eso va primero y la grilla se dibuja encima.
    drawPolygon(template, axes, cx, cy, radius, true, true);
    drawRings(template, font, cx, cy, radius, n);
    drawAxes(template, cx, cy, radius, n);
    drawPolygon(template, axes, cx, cy, radius, true, false);
    if (showTarget) {
      drawPolygon(template, axes, cx, cy, radius, false, false);
    }
    drawLabels(template, font, bold, axes, cx, cy, radius, showTarget);
    drawLegend(template, font, height, showTarget);
    return Image.getInstance(template);
  }

  private static double angle(int index, int count) {
    // El primer eje apunta hacia arriba y los demás siguen el sentido de las agujas del reloj.
    return Math.PI / 2 - 2 * Math.PI * index / count;
  }

  private static float px(float cx, float radius, double fraction, double angle) {
    return (float) (cx + radius * fraction * Math.cos(angle));
  }

  private static float py(float cy, float radius, double fraction, double angle) {
    return (float) (cy + radius * fraction * Math.sin(angle));
  }

  private static void drawRings(
      PdfTemplate t, BaseFont font, float cx, float cy, float radius, int n) {
    t.saveState();
    t.setColorStroke(RING);
    t.setLineWidth(0.5f);
    for (int level = 1; level <= MAX_LEVEL; level++) {
      double fraction = (double) level / MAX_LEVEL;
      for (int i = 0; i < n; i++) {
        float x = px(cx, radius, fraction, angle(i, n));
        float y = py(cy, radius, fraction, angle(i, n));
        if (i == 0) {
          t.moveTo(x, y);
        } else {
          t.lineTo(x, y);
        }
      }
      t.closePathStroke();
    }
    t.restoreState();

    // Escala de niveles sobre el primer eje.
    t.saveState();
    t.setColorFill(MUTED);
    t.beginText();
    t.setFontAndSize(font, 6);
    for (int level = 1; level <= MAX_LEVEL; level++) {
      t.setTextMatrix(cx + 2, cy + radius * level / MAX_LEVEL + 1);
      t.showText(String.valueOf(level));
    }
    t.endText();
    t.restoreState();
  }

  private static void drawAxes(PdfTemplate t, float cx, float cy, float radius, int n) {
    t.saveState();
    t.setColorStroke(AXIS);
    t.setLineWidth(0.5f);
    for (int i = 0; i < n; i++) {
      t.moveTo(cx, cy);
      t.lineTo(px(cx, radius, 1, angle(i, n)), py(cy, radius, 1, angle(i, n)));
      t.stroke();
    }
    t.restoreState();
  }

  private static void drawPolygon(
      PdfTemplate t,
      List<Axis> axes,
      float cx,
      float cy,
      float radius,
      boolean current,
      boolean fillOnly) {
    int n = axes.size();
    t.saveState();
    if (fillOnly) {
      t.setColorFill(CURRENT_FILL);
    }
    t.setColorStroke(current ? CURRENT : TARGET);
    t.setLineWidth(current ? 1.6f : 1.4f);
    if (!current) {
      t.setLineDash(4, 3);
    }
    for (int i = 0; i < n; i++) {
      Axis axis = axes.get(i);
      double fraction = clamp(current ? axis.current() : axis.target()) / MAX_LEVEL;
      float x = px(cx, radius, fraction, angle(i, n));
      float y = py(cy, radius, fraction, angle(i, n));
      if (i == 0) {
        t.moveTo(x, y);
      } else {
        t.lineTo(x, y);
      }
    }
    if (fillOnly) {
      t.closePath();
      t.fill();
    } else {
      t.closePathStroke();
    }
    t.restoreState();
  }

  private static double clamp(double level) {
    return Math.max(0, Math.min(MAX_LEVEL, level));
  }

  private static void drawLabels(
      PdfTemplate t,
      BaseFont font,
      BaseFont bold,
      List<Axis> axes,
      float cx,
      float cy,
      float radius,
      boolean showTarget) {
    int n = axes.size();
    for (int i = 0; i < n; i++) {
      Axis axis = axes.get(i);
      double a = angle(i, n);
      float x = px(cx, radius, 1, a) + (float) Math.cos(a) * 9;
      float y = py(cy, radius, 1, a) + (float) Math.sin(a) * 9;
      // Con pocos ejes las etiquetas de arriba y abajo van centradas. Con muchos (sin objetivo,
      // etiquetas de una línea) cada etiqueta se alinea hacia el lado en que está su eje: dos
      // ejes vecinos del extremo quedan uno a cada lado y no se pisan.
      double flat = showTarget ? 0.3 : 0.05;
      int align;
      if (Math.cos(a) > flat) {
        align = PdfContentByte.ALIGN_LEFT;
      } else if (Math.cos(a) < -flat) {
        align = PdfContentByte.ALIGN_RIGHT;
      } else {
        align = PdfContentByte.ALIGN_CENTER;
      }
      // Etiquetas arriba o abajo del eje: un poco más lejos para que no pisen el polígono.
      float nameY = y + (Math.sin(a) > 0.3 ? 14 : Math.sin(a) < -0.3 ? -2 : 2);
      t.saveState();
      t.beginText();
      t.setColorFill(TEXT);
      if (showTarget) {
        t.setFontAndSize(bold, 8);
        t.showTextAligned(align, shorten(axis.label(), bold, 8, 120), x, nameY, 0);
        t.setColorFill(MUTED);
        t.setFontAndSize(font, 7);
        t.showTextAligned(
            align,
            String.format("actual %.1f · objetivo %.1f", axis.current(), axis.target()),
            x,
            nameY - 9,
            0);
      } else {
        // Una sola línea («GV.OC 2.4»): con muchos ejes, dos líneas por etiqueta se pisarían.
        t.setFontAndSize(bold, 7.5f);
        t.showTextAligned(
            align,
            shorten(axis.label(), bold, 7.5f, 100)
                + " "
                + String.format(java.util.Locale.ROOT, "%.1f", axis.current()),
            x,
            nameY - 2,
            0);
      }
      t.endText();
      t.restoreState();
    }
  }

  private static void drawLegend(PdfTemplate t, BaseFont font, float height, boolean showTarget) {
    float x = 4;
    float y = height - 12;
    t.saveState();
    t.setColorFill(CURRENT_FILL);
    t.setColorStroke(CURRENT);
    t.setLineWidth(1f);
    t.rectangle(x, y - 1, 12, 7);
    t.fillStroke();
    t.restoreState();

    if (showTarget) {
      t.saveState();
      t.setColorStroke(TARGET);
      t.setLineWidth(1.4f);
      t.setLineDash(4, 3);
      t.moveTo(x, y - 17);
      t.lineTo(x + 12, y - 17);
      t.stroke();
      t.restoreState();
    }

    t.saveState();
    t.beginText();
    t.setColorFill(TEXT);
    t.setFontAndSize(font, 8);
    t.showTextAligned(PdfContentByte.ALIGN_LEFT, "Nivel actual", x + 17, y, 0);
    if (showTarget) {
      t.showTextAligned(PdfContentByte.ALIGN_LEFT, "Nivel objetivo", x + 17, y - 18, 0);
    }
    t.setColorFill(MUTED);
    t.setFontAndSize(font, 7);
    t.showTextAligned(
        PdfContentByte.ALIGN_LEFT, "Escala de 0 a " + MAX_LEVEL, x, y - (showTarget ? 32 : 16), 0);
    t.endText();
    t.restoreState();
  }

  /** Recorta el texto con «…» para que no pase del ancho disponible. */
  private static String shorten(String text, BaseFont font, float size, float maxWidth) {
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
