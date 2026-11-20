package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Cita de la IA guardada para una evaluación y un control (C2): sobrevive a recargar la pantalla y
 * conserva la decisión del auditor. A diferencia de {@link AiCitationFeedback} (dataset anónimo),
 * esta tabla SÍ está ligada a la evaluación: es el estado de trabajo del auditor. El {@code
 * fragmentId} lo devuelve backend-ai y es estable entre reindexados (evidencia + posición), por eso
 * al volver a buscar se conservan las decisiones de los fragmentos que siguen apareciendo.
 */
@Entity
@Table(name = "evidence_citation_result", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceCitationResult {

  public enum Status {
    PENDING,
    ACCEPTED,
    REJECTED,
    /** Reservado: el doc 02 lo nombra pero su significado exacto está por verificar. */
    VALIDATED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @Column(name = "evidence_id")
  private UUID evidenceId;

  @Column(name = "fragment_id", length = 200)
  private String fragmentId;

  @Column(name = "file_name")
  private String fileName;

  private String location;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String snippet;

  private Double score;

  @Column(nullable = false)
  private int position;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  @Builder.Default
  private Status status = Status.PENDING;

  /** La evidencia del control cambió: la próxima consulta vuelve a buscar y fusiona. */
  @Column(nullable = false)
  @Builder.Default
  private boolean stale = false;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by")
  private User decidedBy;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
