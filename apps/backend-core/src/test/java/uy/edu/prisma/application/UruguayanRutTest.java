package uy.edu.prisma.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Casos compartidos con el frontend (tests/utils/uruguayanRut.spec.ts): si se agrega o cambia uno,
 * se cambia en los dos.
 */
class UruguayanRutTest {

  // Casos del algoritmo de python-stdnum (uy/rut.py) y el RUT que aportó Joaquín.
  private static final List<String> VALID = List.of("211003420017", "211406340011", "216166580014");

  @Test
  void acceptsValidRutsInAnyFormat() {
    for (String rut : VALID) {
      assertTrue(UruguayanRut.isValid(rut), rut);
    }
    assertTrue(UruguayanRut.isValid("21-100342-001-7"));
    assertTrue(UruguayanRut.isValid("UY 21 140634 001 1"));
    assertTrue(UruguayanRut.isValid(" uy21.616658.001-4 "));
  }

  @Test
  void theCheckDigitFollowsTheModuloElevenFormula() {
    // 21616658001: 2*4 + 1*3 + 6*2 + 1*9 + 6*8 + 6*7 + 5*6 + 8*5 + 0*4 + 0*3 + 1*2 = 194
    // 194 mod 11 = 7 → 11 - 7 = 4
    assertEquals(4, UruguayanRut.checkDigit("21616658001"));
    assertEquals(7, UruguayanRut.checkDigit("21100342001")); // suma 70
    assertEquals(1, UruguayanRut.checkDigit("21140634001")); // suma 131
  }

  @Test
  void aSumDivisibleByElevenGivesCheckDigitZeroAndOneLeftOverGivesTen() {
    // Cualquier 11 dígitos sirve para probar los bordes de la fórmula.
    String zero = null;
    String ten = null;
    for (int n = 0; n < 1_000_000 && (zero == null || ten == null); n++) {
      String candidate = String.format("21%06d001", n);
      int digit = UruguayanRut.checkDigit(candidate);
      if (digit == 0 && zero == null) {
        zero = candidate;
      }
      if (digit == 10 && ten == null) {
        ten = candidate;
      }
    }
    // Con verificador 0 el RUT es válido; con 10 no hay dígito que lo represente.
    assertTrue(UruguayanRut.isValid(zero + "0"), zero);
    for (int d = 0; d <= 9; d++) {
      assertFalse(UruguayanRut.isValid(ten + d), ten + d);
    }
  }

  @Test
  void rejectsAWrongCheckDigit() {
    assertFalse(UruguayanRut.isValid("210303670014")); // el verificador correcto sería 0
    assertFalse(UruguayanRut.isValid("216166580015"));
  }

  @Test
  void rejectsWrongLengthOrNonDigits() {
    assertFalse(UruguayanRut.isValid(null));
    assertFalse(UruguayanRut.isValid(""));
    assertFalse(UruguayanRut.isValid("12345678"));
    assertFalse(UruguayanRut.isValid("21616658001")); // 11
    assertFalse(UruguayanRut.isValid("2161665800144")); // 13
    assertFalse(UruguayanRut.isValid("21616658001A"));
  }

  @Test
  void rejectsAnOutOfRangeRegistryAnEmptySequenceOrAnotherFixedGroup() {
    assertFalse(UruguayanRut.isValid("006166580014")); // prefijo 00
    assertFalse(UruguayanRut.isValid("236166580014")); // prefijo 23
    assertFalse(UruguayanRut.isValid("210000000014")); // secuencial 000000
    assertFalse(UruguayanRut.isValid("216166580024")); // grupo 002
  }

  @Test
  void normalizesToTheFifteenCharacterForm() {
    assertEquals("21-616658-001-4", UruguayanRut.normalize("216166580014"));
    assertEquals("21-616658-001-4", UruguayanRut.normalize("UY 21.616658.001-4"));
    assertEquals(15, UruguayanRut.normalize("216166580014").length());
    assertNull(UruguayanRut.normalize("210303670014"));
    assertNull(UruguayanRut.normalize(null));
  }
}
