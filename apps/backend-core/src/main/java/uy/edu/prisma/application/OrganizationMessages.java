package uy.edu.prisma.application;

/**
 * Textos de usuario sobre organizaciones. El identificador de una organización puede ser un RUT
 * uruguayo (casilla «es un RUT» del formulario) u otro identificador libre.
 */
public final class OrganizationMessages {

  private OrganizationMessages() {}

  /** Largo mínimo de un identificador que no es RUT. */
  public static final int MIN_IDENTIFIER_LENGTH = 8;

  /** Largo máximo (el de la columna): un RUT normalizado ocupa 15. */
  public static final int MAX_IDENTIFIER_LENGTH = 20;

  /** Mensaje genérico (sin el nombre de la otra organización), usado por la red de seguridad. */
  public static final String DUPLICATE_IDENTIFIER =
      "Ya existe una organización con ese identificador";

  public static final String INVALID_RUT =
      "El RUT no es válido: debe tener 12 dígitos (AA-BBBBBB-001-D) y un dígito verificador"
          + " correcto";

  public static final String IDENTIFIER_TOO_SHORT =
      "El identificador debe tener al menos " + MIN_IDENTIFIER_LENGTH + " caracteres";

  public static final String IDENTIFIER_TOO_LONG =
      "El identificador no puede superar los " + MAX_IDENTIFIER_LENGTH + " caracteres";

  public static String duplicateIdentifier(String identifier, String existingOrganizationName) {
    return "Ya existe una organización con el identificador "
        + identifier
        + " («"
        + existingOrganizationName
        + "»). Revise el número o edite la organización existente.";
  }
}
