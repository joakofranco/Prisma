package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Qué decidió el auditor sobre una sugerencia de control para evidencia global. Dataset ANÓNIMO
 * (patrón V32/V33) y mínimo (D15): solo el control del catálogo, el puntaje, la posición y la
 * decisión; sin organización, evaluación, usuario, archivo ni texto.
 */
@Entity
@Table(name = "ai_evidence_classification_feedback", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiEvidenceClassificationFeedback {

  public enum Decision {
    ACCEPTED,
    REJECTED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @Column(nullable = false)
  private double score;

  @Column(nullable = false)
  private int rank;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Decision decision;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
