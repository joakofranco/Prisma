package uy.edu.prisma.domain.exception;

/** Conflicto de estado del recurso (duplicados, transiciones inválidas). Mapea a HTTP 409. */
public class ConflictException extends RuntimeException {

  private final String field;

  public ConflictException(String message) {
    this(message, null);
  }

  /**
   * @param field campo del formulario que provoca el conflicto (p.ej. {@code "rut"}); el manejador
   *     global lo devuelve en {@code details} para que el front marque ese campo en lugar de
   *     mostrar solo un aviso genérico. {@code null} = conflicto que no es de un campo puntual.
   */
  public ConflictException(String message, String field) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
