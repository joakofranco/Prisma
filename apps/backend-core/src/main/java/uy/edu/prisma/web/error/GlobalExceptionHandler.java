package uy.edu.prisma.web.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import uy.edu.prisma.application.OrganizationMessages;
import uy.edu.prisma.config.EvidenceConfig.EvidenceProperties;
import uy.edu.prisma.domain.exception.AiUnavailableException;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.MalwareDetectedException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.web.dto.Dto.ApiErrorDto;

/** Convierte excepciones de la aplicación en respuestas HTTP semánticas. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final EvidenceProperties evidenceProperties;

  public GlobalExceptionHandler(EvidenceProperties evidenceProperties) {
    this.evidenceProperties = evidenceProperties;
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ApiErrorDto> handleNotFound(ResourceNotFoundException ex) {
    return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ApiErrorDto> handleConflict(ConflictException ex) {
    Map<String, List<String>> details =
        ex.getField() == null ? null : Map.of(ex.getField(), List.of(ex.getMessage()));
    return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), details);
  }

  @ExceptionHandler(AiUnavailableException.class)
  public ResponseEntity<ApiErrorDto> handleAiUnavailable(AiUnavailableException ex) {
    log.warn("Asistente de IA no disponible: {}", ex.getCause() != null ? ex.getCause() : ex);
    return build(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", ex.getMessage(), null);
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ApiErrorDto> handleInvalidRequest(InvalidRequestException ex) {
    return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), null);
  }

  @ExceptionHandler(PayloadTooLargeException.class)
  public ResponseEntity<ApiErrorDto> handlePayloadTooLarge(PayloadTooLargeException ex) {
    return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", ex.getMessage(), null);
  }

  @ExceptionHandler(MalwareDetectedException.class)
  public ResponseEntity<ApiErrorDto> handleMalware(MalwareDetectedException ex) {
    Map<String, List<String>> details =
        ex.getSignature() == null ? null : Map.of("signature", List.of(ex.getSignature()));
    return build(HttpStatus.UNPROCESSABLE_ENTITY, "MALWARE_DETECTED", ex.getMessage(), details);
  }

  // Spring corta la petición multipart antes de llegar al controlador si supera el límite de
  // EvidenceConfig. Sin este manejador caía en handleGeneric: un 500 «Error inesperado» que
  // ocultaba el motivo real (RF-EVI-04). Hoy la única subida multipart es la de evidencias.
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiErrorDto> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
    return build(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "PAYLOAD_TOO_LARGE",
        "El archivo supera el tamaño máximo permitido de "
            + evidenceProperties.maxSizeDescription(),
        null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorDto> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, List<String>> details = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      details
          .computeIfAbsent(error.getField(), k -> new java.util.ArrayList<>())
          .add(error.getDefaultMessage());
    }
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Error de validación", details);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorDto> handleUnreadable(HttpMessageNotReadableException ex) {
    return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Cuerpo de la solicitud mal formado", null);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiErrorDto> handleIntegrity(DataIntegrityViolationException ex) {
    // Red de seguridad: OrganizationService ya chequea el RUT duplicado antes de guardar, pero si
    // dos altas simultáneas se le adelantan, la restricción única de la base es la que salta. En
    // vez del genérico "conflicto de integridad", se devuelve el mismo mensaje claro.
    String cause = ex.getMostSpecificCause().getMessage();
    if (cause != null && cause.contains("organizations_rut_key")) {
      return build(
          HttpStatus.CONFLICT,
          "CONFLICT",
          OrganizationMessages.DUPLICATE_RUT,
          Map.of("rut", List.of(OrganizationMessages.DUPLICATE_RUT)));
    }
    return build(HttpStatus.CONFLICT, "DATA_CONFLICT", "Conflicto de integridad de datos", null);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiErrorDto> handleAccessDenied(AccessDeniedException ex) {
    return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Acceso denegado", null);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiErrorDto> handleAuthentication(AuthenticationException ex) {
    return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Se requiere autenticación", null);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorDto> handleIllegalArgument(IllegalArgumentException ex) {
    return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), null);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorDto> handleGeneric(Exception ex) {
    // Sin esto, cualquier error no mapeado explícitamente devuelve 500 sin dejar ningún rastro
    // en los logs del servidor -- imposible de diagnosticar en producción.
    log.error("Unexpected error handling request", ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error inesperado", null);
  }

  // Traducción de los HttpStatus.getReasonPhrase() en inglés (p.ej. "Bad Request") que se
  // usarían como fallback si algún día un handler pasa message=null -- hoy ninguno lo hace, pero
  // sin esto ese fallback filtraría inglés igual que los mensajes que motivaron este archivo.
  private static final Map<HttpStatus, String> STATUS_MESSAGES_ES =
      Map.of(
          HttpStatus.BAD_REQUEST, "Solicitud inválida",
          HttpStatus.UNAUTHORIZED, "No autorizado",
          HttpStatus.FORBIDDEN, "Prohibido",
          HttpStatus.NOT_FOUND, "No encontrado",
          HttpStatus.CONFLICT, "Conflicto",
          HttpStatus.PAYLOAD_TOO_LARGE, "Archivo demasiado grande",
          HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");

  private ResponseEntity<ApiErrorDto> build(
      HttpStatus status, String code, String message, Map<String, List<String>> details) {
    String resolvedMessage =
        message == null
            ? STATUS_MESSAGES_ES.getOrDefault(status, status.getReasonPhrase())
            : message;
    return ResponseEntity.status(status).body(new ApiErrorDto(resolvedMessage, code, details));
  }
}
