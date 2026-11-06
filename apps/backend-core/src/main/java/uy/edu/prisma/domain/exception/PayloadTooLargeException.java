package uy.edu.prisma.domain.exception;

/** El contenido enviado supera el tamaño máximo permitido (p. ej. una evidencia). HTTP 413. */
public class PayloadTooLargeException extends RuntimeException {

  public PayloadTooLargeException(String message) {
    super(message);
  }
}
