package uy.edu.prisma.application;

/**
 * Validación del RUT uruguayo (Registro Único Tributario) de personas jurídicas.
 *
 * <p>Tiene 12 dígitos con la forma {@code AA-BBBBBB-001-D}:
 *
 * <ul>
 *   <li>{@code AA}: número de registro, de 01 a 22;
 *   <li>{@code BBBBBB}: secuencial, distinto de 000000;
 *   <li>{@code 001}: grupo fijo;
 *   <li>{@code D}: dígito verificador, módulo 11 con los pesos 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2
 *       aplicados a los 11 primeros dígitos. Un resultado de 10 no tiene dígito: ese RUT no existe.
 * </ul>
 *
 * <p>El algoritmo sale de la librería python-stdnum ({@code stdnum/uy/rut.py}); la DGI no publica
 * el suyo. El frontend tiene la misma lógica y los mismos casos de prueba (utils/uruguayanRut.ts):
 * si se cambia una, se cambia la otra.
 */
public final class UruguayanRut {

  private static final int[] WEIGHTS = {4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
  private static final int LENGTH = 12;

  private UruguayanRut() {}

  /** Quita espacios, puntos y guiones, pasa a mayúsculas y descarta un prefijo «UY». */
  static String compact(String raw) {
    if (raw == null) {
      return "";
    }
    String compact = raw.replaceAll("[.\\-\\s]", "").toUpperCase(java.util.Locale.ROOT);
    return compact.startsWith("UY") ? compact.substring(2) : compact;
  }

  /** Dígito verificador esperado para los 11 primeros dígitos (0 a 10; 10 = no existe). */
  static int checkDigit(String first11Digits) {
    int sum = 0;
    for (int i = 0; i < WEIGHTS.length; i++) {
      sum += (first11Digits.charAt(i) - '0') * WEIGHTS[i];
    }
    return (11 - sum % 11) % 11;
  }

  public static boolean isValid(String raw) {
    String rut = compact(raw);
    if (rut.length() != LENGTH || !rut.chars().allMatch(c -> c >= '0' && c <= '9')) {
      return false;
    }
    int registry = Integer.parseInt(rut.substring(0, 2));
    if (registry < 1 || registry > 22) {
      return false;
    }
    if (rut.substring(2, 8).equals("000000") || !rut.substring(8, 11).equals("001")) {
      return false;
    }
    return checkDigit(rut) == rut.charAt(LENGTH - 1) - '0';
  }

  /**
   * Forma normalizada {@code AA-BBBBBB-001-D} (15 caracteres), o {@code null} si no es un RUT
   * válido.
   */
  public static String normalize(String raw) {
    if (!isValid(raw)) {
      return null;
    }
    String rut = compact(raw);
    return rut.substring(0, 2)
        + "-"
        + rut.substring(2, 8)
        + "-"
        + rut.substring(8, 11)
        + "-"
        + rut.substring(11);
  }
}
