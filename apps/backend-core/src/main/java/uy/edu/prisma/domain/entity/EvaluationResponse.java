package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "evaluation_responses", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvaluationResponse {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  /**
   * Cumple / no cumple este control puntual -- el catálogo real es un checklist por control, no una
   * autoevaluación de madurez 1-5 (ver EvaluationService.calculateMaturity: la madurez de una
   * subcategoría se deriva de forma acumulativa a partir de estos booleanos).
   */
  @Column(nullable = false)
  @Builder.Default
  private Boolean compliant = false;

  @Column(columnDefinition = "TEXT")
  private String observations;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responded_by")
  private User respondedBy;

  @Column(name = "responded_at", nullable = false)
  @Builder.Default
  private OffsetDateTime respondedAt = OffsetDateTime.now();
}
