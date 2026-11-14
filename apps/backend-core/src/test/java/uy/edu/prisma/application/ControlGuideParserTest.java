package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ControlGuideParserTest {

  private static final String GUIDE =
      """
      # Guía

      ## Organización y Gobernanza

      ### PS.1 Adoptar una Política

      #### PS.1-1 (Nivel 1)

      **Control:** Existe una política aprobada

      **Interpretación:** La organización tiene escrita su política.

      **Ejemplo de aplicación:** El Directorio la aprueba por resolución.

      **Evidencia a considerar:** Resolución o acta de aprobación.

      #### PS.1-2 (Nivel 1)

      **Control:** La política es difundida

      **Interpretación:** Todos fueron informados
      y saben qué les exige.

      **Ejemplo de aplicación:** Se envía por correo.

      ### PS.2 Otro requisito

      #### PS.2-1 (Nivel 2)

      **Interpretación:** Solo tiene interpretación.
      """;

  @Test
  void parsesOneEntryPerControlWithItsThreeSections() {
    List<ControlGuideParser.Entry> entries = ControlGuideParser.parse(GUIDE);

    assertEquals(3, entries.size());
    ControlGuideParser.Entry first = entries.get(0);
    assertEquals("PS.1-1", first.code());
    assertEquals("La organización tiene escrita su política.", first.interpretation());
    assertEquals("El Directorio la aprueba por resolución.", first.example());
    assertEquals("Resolución o acta de aprobación.", first.evidence());
    assertTrue(first.isComplete());
  }

  @Test
  void joinsTheLinesOfASectionThatSpansSeveralLines() {
    ControlGuideParser.Entry second = ControlGuideParser.parse(GUIDE).get(1);

    assertEquals("Todos fueron informados y saben qué les exige.", second.interpretation());
  }

  @Test
  void aControlWithMissingSectionsIsParsedAsIncomplete() {
    List<ControlGuideParser.Entry> entries = ControlGuideParser.parse(GUIDE);

    assertFalse(entries.get(1).isComplete());
    assertFalse(entries.get(2).isComplete());
    assertEquals("PS.2-1", entries.get(2).code());
  }

  @Test
  void aSectionHeadingEndsTheControlBeforeIt() {
    ControlGuideParser.Entry second = ControlGuideParser.parse(GUIDE).get(1);

    assertEquals("Se envía por correo.", second.example());
    assertEquals("", second.evidence());
  }

  @Test
  void textWithoutControlsYieldsNothing() {
    assertTrue(ControlGuideParser.parse("# Solo un título\n\nTexto suelto").isEmpty());
  }
}
