package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SearchTextTest {

  @Test
  void sinTextoNoHayPatron() {
    assertNull(SearchText.pattern(null));
    assertNull(SearchText.pattern("   "));
    assertNull(SearchText.idPattern(null));
    assertNull(SearchText.idPattern(""));
  }

  @Test
  void elPatronPlieguaTildesMayusculasYPuntos() {
    assertEquals("%organizacion demo%", SearchText.pattern("  Organización Demo "));
    assertEquals("%dr perez%", SearchText.pattern("Dr. Pérez"));
    assertEquals("%nino%", SearchText.pattern("NIÑO"));
  }

  @Test
  void elPatronDeIdentificadorTambienIgnoraGuionesYEspacios() {
    assertEquals("%12345678%", SearchText.idPattern("1.234.567-8"));
    assertEquals("%212345670%", SearchText.idPattern(" 21 234 567 0 "));
  }

  @Test
  void soloSeparadoresNoCoincideConTodo() {
    assertEquals("%__sin_coincidencia__%", SearchText.pattern("..."));
    assertEquals("%__sin_coincidencia__%", SearchText.idPattern("--"));
    assertEquals("%__sin_coincidencia__%", SearchText.idPattern(". -"));
  }
}
