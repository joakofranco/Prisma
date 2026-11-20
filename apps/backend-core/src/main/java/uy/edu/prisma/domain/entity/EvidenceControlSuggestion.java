package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Sugerencia de la IA de a qué control podría pertenecer una evidencia sin control asignado (C1,
 * D7). Es SOLO una sugerencia: aceptarla no asigna la evidencia a ese control (una política típica
 * respalda varios) sino que hace que el control aparezca en la lista de la auditoría, donde los
 * fragmentos de la evidencia global ya entran en las citas.
 */
@Entity
@Table(name = "evidence_control_suggestion", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceControlSuggestion {

  public enum Status {
    PENDING,
    ACCEPTED,
    REJECTED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evidence_id", nullable = false)
  private Evidence evidence;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @Column(nullable = false)
  private double score;

  @Column(nullable = false)
  private int rank;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  @Builder.Default
  private Status status = Status.PENDING;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by")
  private User decidedBy;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
