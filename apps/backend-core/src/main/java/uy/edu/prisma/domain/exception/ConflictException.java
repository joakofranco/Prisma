package uy.edu.prisma.domain.exception;

/** Conflicto de estado del recurso (duplicados, transiciones inválidas). Mapea a HTTP 409. */
public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
