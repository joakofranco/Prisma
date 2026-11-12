package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Decisión del auditor sobre una cita propuesta por la IA (PRI-359). Alimenta la medición de la
 * búsqueda y un dataset de fine-tuning, por eso referencia al control del catálogo y no a la
 * evaluación, la organización ni el usuario.
 */
@Entity
@Table(name = "ai_citation_feedback", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCitationFeedback {

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

  @Column(name = "file_name")
  private String fileName;

  private String location;

  @Column(nullable = false)
  private String snippet;

  private Double score;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Decision decision;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
