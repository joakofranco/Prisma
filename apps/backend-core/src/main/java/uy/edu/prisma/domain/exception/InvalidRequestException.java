package uy.edu.prisma.domain.exception;

/** Solicitud inválida (enumerados fuera de rango, parámetros incorrectos). Mapea a HTTP 400. */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
