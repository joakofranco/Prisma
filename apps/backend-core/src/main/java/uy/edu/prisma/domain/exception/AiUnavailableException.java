package uy.edu.prisma.domain.exception;

/**
 * El asistente de IA (backend-ai) no respondió: caído, colgado o con error. Mapea a HTTP 503.
 * Permite a la pantalla distinguir «la IA no está disponible» de «no hay resultados».
 */
public class AiUnavailableException extends RuntimeException {

  public AiUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
