package uy.edu.prisma.application;

import java.util.ArrayList;
import java.util.List;

/** Lectura y escritura de CSV (RFC 4180) con comillas, delimitador y saltos de línea en celdas. */
final class ControlGuideCsv {

  static final char DELIMITER = ';';
  private static final char BOM = '﻿';

  private ControlGuideCsv() {}

  static char detectDelimiter(String text) {
    int end = text.indexOf('\n');
    String header = end < 0 ? text : text.substring(0, end);
    int semicolons = count(header, ';');
    int commas = count(header, ',');
    int tabs = count(header, '\t');
    if (tabs > semicolons && tabs > commas) {
      return '\t';
    }
    return commas > semicolons ? ',' : ';';
  }

  static List<List<String>> parse(String text, char delimiter) {
    String source = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    int length = source.length();
    for (int i = 0; i < length; i++) {
      char c = source.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < length && source.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == delimiter) {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < length && source.charAt(i + 1) == '\n') {
          i++;
        }
        endRow(rows, row, cell);
        row = new ArrayList<>();
      } else {
        cell.append(c);
      }
    }
    endRow(rows, row, cell);
    return rows;
  }

  static String write(List<List<String>> rows) {
    StringBuilder out = new StringBuilder().append(BOM);
    for (List<String> row : rows) {
      for (int i = 0; i < row.size(); i++) {
        if (i > 0) {
          out.append(DELIMITER);
        }
        String value = row.get(i) == null ? "" : row.get(i);
        out.append('"').append(value.replace("\"", "\"\"")).append('"');
      }
      out.append("\r\n");
    }
    return out.toString();
  }

  private static void endRow(List<List<String>> rows, List<String> row, StringBuilder cell) {
    row.add(cell.toString());
    cell.setLength(0);
    boolean empty = row.size() == 1 && row.get(0).isBlank();
    if (!empty) {
      rows.add(row);
    }
  }

  private static int count(String text, char c) {
    int total = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == c) {
        total++;
      }
    }
    return total;
  }
}
