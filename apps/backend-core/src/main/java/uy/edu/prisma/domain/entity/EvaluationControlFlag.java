package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Marca «para revisar» de un control dentro de una evaluación (existe la fila = marcado). */
@Entity
@Table(name = "evaluation_control_flags", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationControlFlag {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "flagged_by")
  private User flaggedBy;

  @Column(name = "flagged_at", nullable = false)
  @Builder.Default
  private OffsetDateTime flaggedAt = OffsetDateTime.now();
}
