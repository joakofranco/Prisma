package uy.edu.prisma.domain.exception;

/** Se alcanzó un límite de uso (p. ej. trabajos de IA pendientes por organización). HTTP 429. */
public class TooManyRequestsException extends RuntimeException {

  public TooManyRequestsException(String message) {
    super(message);
  }
}
