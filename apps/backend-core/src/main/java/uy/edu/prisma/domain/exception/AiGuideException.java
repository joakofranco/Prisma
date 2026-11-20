package uy.edu.prisma.domain.exception;

/**
 * Falla al generar una guía con backend-ai, ya clasificada para la cola de trabajos: {@code code}
 * (se guarda en el trabajo) y si vale la pena reintentar. {@code AI_UNAVAILABLE} (caído, colgado,
 * sin respuesta del modelo) y {@code AI_ERROR} (5xx) se reintentan; {@code PROMPT_TOO_LARGE} (el
 * prompt no entra en el contexto del modelo) y {@code AI_REJECTED} (4xx) no: repetirlos da lo
 * mismo.
 */
public class AiGuideException extends RuntimeException {

  public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
  public static final String AI_ERROR = "AI_ERROR";
  public static final String AI_REJECTED = "AI_REJECTED";
  public static final String PROMPT_TOO_LARGE = "PROMPT_TOO_LARGE";

  private final String code;
  private final boolean retryable;

  public AiGuideException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String getCode() {
    return code;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
