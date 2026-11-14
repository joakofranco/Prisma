package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ControlGuideCsvTest {

  @Test
  void parsesQuotedCellsWithDelimitersQuotesAndLineBreaks() {
    String csv = "a;b;c\r\n\"uno; dos\";\"dice \"\"hola\"\"\";\"linea 1\nlinea 2\"\r\n";

    List<List<String>> rows = ControlGuideCsv.parse(csv, ';');

    assertEquals(2, rows.size());
    assertEquals(List.of("uno; dos", "dice \"hola\"", "linea 1\nlinea 2"), rows.get(1));
  }

  @Test
  void ignoresTheByteOrderMarkAndBlankRows() {
    List<List<String>> rows = ControlGuideCsv.parse("﻿a,b\n\n1,2\n", ',');

    assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), rows);
  }

  @Test
  void detectsTheDelimiterFromTheHeader() {
    assertEquals(';', ControlGuideCsv.detectDelimiter("codigo;nivel;control\n1;2;3"));
    assertEquals(',', ControlGuideCsv.detectDelimiter("codigo,nivel,control\n1,2,3"));
    assertEquals('\t', ControlGuideCsv.detectDelimiter("codigo\tnivel\tcontrol\n"));
  }

  @Test
  void writesAndReadsBackTheSameContent() {
    List<List<String>> rows =
        List.of(
            List.of("codigo", "texto"),
            List.of("PS.1-1", "Con \"comillas\"; punto y coma\ny salto de línea"));

    String written = ControlGuideCsv.write(rows);

    assertEquals('﻿', written.charAt(0));
    assertEquals(rows, ControlGuideCsv.parse(written, ControlGuideCsv.DELIMITER));
  }
}
