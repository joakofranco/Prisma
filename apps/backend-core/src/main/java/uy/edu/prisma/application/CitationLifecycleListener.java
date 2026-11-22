package uy.edu.prisma.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import uy.edu.prisma.domain.entity.Evaluation;

/**
 * Ciclo de vida de las citas guardadas según el estado de la evaluación (C2, R7):
 *
 * <ul>
 *   <li>Al <b>entrar</b> a auditoría se encola {@code CITATIONS_PRECOMPUTE}: el auditor encuentra
 *       las citas listas.
 *   <li>Al <b>salir</b> de auditoría (devuelta, aprobada) se descartan las citas guardadas: ya no
 *       reflejan el estado de la evaluación. El dictamen ya fijado NO se toca (conserva su cita de
 *       respaldo en {@code source_ref}).
 * </ul>
 *
 * Se ejecuta después de confirmar la transacción del cambio de estado, y es de mejor esfuerzo: un
 * fallo acá no revierte el cambio de estado.
 */
@Component
public class CitationLifecycleListener {

  private static final Logger log = LoggerFactory.getLogger(CitationLifecycleListener.class);

  private final EvidenceCitationStore store;
  private final CitationPrecomputeService precompute;

  // En AFTER_COMMIT la transacción del cambio de estado ya se confirmó: lo que se escriba acá sin
  // una transacción propia NO se confirma nunca (el @Transactional de los servicios se une a la
  // ya terminada). Cada escritura abre la suya (REQUIRES_NEW).
  private final TransactionTemplate tx;

  public CitationLifecycleListener(
      EvidenceCitationStore store,
      CitationPrecomputeService precompute,
      PlatformTransactionManager txManager) {
    this.store = store;
    this.precompute = precompute;
    this.tx = new TransactionTemplate(txManager);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onStatusChanged(EvaluationStatusChangedEvent event) {
    try {
      if (event.from() == Evaluation.Status.IN_AUDIT) {
        int removed = store.invalidateEvaluation(event.evaluationId());
        if (removed > 0) {
          log.info(
              "Evaluación {} salió de auditoría: {} citas guardadas descartadas",
              event.evaluationId(),
              removed);
        }
      }
      if (event.to() == Evaluation.Status.IN_AUDIT) {
        tx.executeWithoutResult(
            status -> precompute.enqueue(event.evaluationId(), event.organizationId()));
      }
    } catch (RuntimeException e) {
      log.warn(
          "No se pudo actualizar las citas de la evaluación {}: {}",
          event.evaluationId(),
          e.getMessage());
    }
  }
}
