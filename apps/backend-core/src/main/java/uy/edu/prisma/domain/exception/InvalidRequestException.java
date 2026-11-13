package uy.edu.prisma.domain.exception;

/** Solicitud inválida (enumerados fuera de rango, parámetros incorrectos). Mapea a HTTP 400. */
public class InvalidRequestException extends RuntimeException {

  private final String field;

  public InvalidRequestException(String message) {
    this(message, null);
  }

  /**
   * @param field campo del formulario inválido (p.ej. {@code "identifier"}); el manejador global lo
   *     devuelve en {@code details} para que el front marque ese campo. {@code null} = solicitud
   *     inválida que no es de un campo puntual.
   */
  public InvalidRequestException(String message, String field) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
