package uy.edu.prisma.domain.exception;

import java.util.UUID;

/**
 * Aceptar una cita pasaría a «Validado» un control que el auditor había dictaminado a mano como «No
 * validado» o «Requiere más evidencia» (C4a / D10): hace falta que lo confirme. HTTP 409 con código
 * {@code VERDICT_OVERRIDE_REQUIRED}; el cliente repite el pedido con la confirmación.
 */
public class VerdictOverrideRequiredException extends RuntimeException {

  private final UUID controlId;
  private final String controlCode;
  private final String currentVerdict;

  public VerdictOverrideRequiredException(
      UUID controlId, String controlCode, String currentVerdict) {
    super(
        "El control "
            + controlCode
            + " ya tiene un dictamen puesto a mano ("
            + currentVerdict
            + "). Confirmá si querés reemplazarlo por «Validado».");
    this.controlId = controlId;
    this.controlCode = controlCode;
    this.currentVerdict = currentVerdict;
  }

  public UUID getControlId() {
    return controlId;
  }

  public String getControlCode() {
    return controlCode;
  }

  public String getCurrentVerdict() {
    return currentVerdict;
  }
}
