package uy.edu.prisma.application;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza el texto que escribe quien busca (PRI-368) con la misma regla que aplican las consultas
 * en la base (ver {@code SearchSql}): minúsculas, sin tildes y sin puntos. Devuelve el patrón
 * {@code LIKE} ya con los comodines, o {@code null} si no hay nada que buscar.
 */
final class SearchText {

  private static final String NO_MATCH = "__sin_coincidencia__";

  /**
   * Patrón para nombres, emails, etc.: «Dr. Pérez» → {@code %dr perez%}. Si lo escrito son solo
   * puntos no coincide con nada (en vez de coincidir con todo).
   */
  static String pattern(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String folded = fold(text.trim());
    return folded.isEmpty() ? "%" + NO_MATCH + "%" : "%" + folded + "%";
  }

  /**
   * Patrón para identificadores (RUT): además ignora guiones y espacios, así «1.234.567-8»,
   * «1234567-8» y «12345678» son lo mismo. Si lo escrito son solo separadores, no coincide con nada
   * (en vez de coincidir con todo).
   */
  static String idPattern(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String id = fold(text).replace("-", "").replace(" ", "");
    return id.isEmpty() ? "%" + NO_MATCH + "%" : "%" + id + "%";
  }

  /** Minúsculas, sin tildes y sin puntos. */
  static String fold(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replace(".", "");
  }

  private SearchText() {}
}
