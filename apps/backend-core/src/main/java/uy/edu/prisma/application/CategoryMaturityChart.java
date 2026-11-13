package uy.edu.prisma.application;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.pdf.PdfWriter;
import java.io.IOException;
import java.util.List;

/**
 * Gráfico «Madurez por categoría» de los reportes PDF completo y técnico: un radar con un eje por
 * categoría del marco (identificada por su sigla, p. ej. «GV.OC») y el nivel actual promedio de sus
 * subcategorías. Es el mismo gráfico que la pestaña «Tablas y promedios» del detalle de la
 * evaluación. Reutiliza {@link RadarChart}, así que es vectorial y compatible con PDF/A.
 */
final class CategoryMaturityChart {

  /** Una categoría y su nivel actual medio (media plana de sus subcategorías, de 0 a 4). */
  record Point(String functionName, String code, String name, double level) {

    /** Lo que se imprime en el eje: la sigla, y si no la hay, el nombre. */
    String label() {
      return code != null && !code.isBlank() ? code : name;
    }
  }

  /** Con menos de tres categorías un radar no tiene forma: se muestra solo la tabla. */
  static final int MIN_AXES = 3;

  private static final float WIDTH_PADDING = 0;

  private CategoryMaturityChart() {}

  static boolean canRender(List<Point> points) {
    return points.size() >= MIN_AXES;
  }

  /**
   * Dibuja el radar de las categorías; {@code width} es el ancho útil de la página. Con muchas
   * categorías (el MCU completo tiene más de veinte) el gráfico es más alto para que las etiquetas
   * no se pisen.
   */
  static Image render(PdfWriter writer, List<Point> points, float width)
      throws DocumentException, IOException {
    List<RadarChart.Axis> axes =
        points.stream().map(p -> new RadarChart.Axis(p.label(), p.level(), 0)).toList();
    float height = points.size() > 12 ? 280 : 230;
    return RadarChart.render(writer, axes, width - WIDTH_PADDING, height, false);
  }
}
