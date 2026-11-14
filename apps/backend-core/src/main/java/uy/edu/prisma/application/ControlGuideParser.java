package uy.edu.prisma.application;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la «Guía de interpretación de controles» en Markdown: un bloque por control con el título
 * {@code #### CODIGO (Nivel N)} seguido de las líneas {@code **Control:**}, {@code
 * **Interpretación:**}, {@code **Ejemplo de aplicación:**} y {@code **Evidencia a considerar:**}.
 */
final class ControlGuideParser {

  record Entry(String code, String interpretation, String example, String evidence) {
    boolean isComplete() {
      return notBlank(interpretation) && notBlank(example) && notBlank(evidence);
    }

    private static boolean notBlank(String value) {
      return value != null && !value.isBlank();
    }
  }

  private static final Pattern HEADER =
      Pattern.compile("^####\\s+(\\S+)\\s+\\(Nivel\\s+\\d\\)\\s*$");
  private static final Pattern FIELD =
      Pattern.compile(
          "^\\*\\*(Control|Interpretación|Ejemplo de aplicación|Evidencia a considerar):\\*\\*\\s*(.*)$");

  private ControlGuideParser() {}

  static List<Entry> parse(String markdown) {
    List<Entry> entries = new ArrayList<>();
    Builder current = null;
    String field = null;
    for (String raw : markdown.split("\\R")) {
      String line = raw.strip();
      Matcher header = HEADER.matcher(line);
      if (header.matches()) {
        if (current != null) {
          entries.add(current.build());
        }
        current = new Builder(header.group(1));
        field = null;
        continue;
      }
      if (current == null) {
        continue;
      }
      if (line.startsWith("#")) {
        entries.add(current.build());
        current = null;
        field = null;
        continue;
      }
      Matcher fieldMatch = FIELD.matcher(line);
      if (fieldMatch.matches()) {
        field = fieldMatch.group(1);
        current.append(field, fieldMatch.group(2));
      } else if (field != null && !line.isEmpty()) {
        current.append(field, line);
      }
    }
    if (current != null) {
      entries.add(current.build());
    }
    return entries;
  }

  private static final class Builder {
    private final String code;
    private final StringBuilder interpretation = new StringBuilder();
    private final StringBuilder example = new StringBuilder();
    private final StringBuilder evidence = new StringBuilder();

    Builder(String code) {
      this.code = code;
    }

    void append(String field, String text) {
      StringBuilder target =
          switch (field) {
            case "Interpretación" -> interpretation;
            case "Ejemplo de aplicación" -> example;
            case "Evidencia a considerar" -> evidence;
            default -> null;
          };
      if (target == null || text.isBlank()) {
        return;
      }
      if (target.length() > 0) {
        target.append(' ');
      }
      target.append(text.strip());
    }

    Entry build() {
      return new Entry(code, interpretation.toString(), example.toString(), evidence.toString());
    }
  }
}
