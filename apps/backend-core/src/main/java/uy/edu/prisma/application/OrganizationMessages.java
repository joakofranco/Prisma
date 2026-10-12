package uy.edu.prisma.application;

/**
 * Textos de usuario sobre organizaciones. El identificador tributario se llama "RUT" (Uruguay;
 * columna {@code organizations.rut} desde V17); si la investigación I2 decide otro nombre, se
 * cambia acá y en el rótulo del formulario del front.
 */
public final class OrganizationMessages {

  private OrganizationMessages() {}

  /** Mensaje genérico (sin el nombre de la otra organización), usado por la red de seguridad. */
  public static final String DUPLICATE_RUT = "Ya existe una organización con ese RUT";

  public static String duplicateRut(String rut, String existingOrganizationName) {
    return "Ya existe una organización con el RUT "
        + rut
        + " («"
        + existingOrganizationName
        + "»). Revise el número o edite la organización existente.";
  }
}
