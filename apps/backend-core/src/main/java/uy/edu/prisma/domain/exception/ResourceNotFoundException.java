package uy.edu.prisma.domain.exception;

/** Recurso solicitado que no existe. Mapea a HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String message) {
    super(message);
  }

  public ResourceNotFoundException(String resource, Object id) {
    super("No se encontró " + resource + ": " + id);
  }
}
