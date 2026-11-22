package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Qué hizo el auditor con el texto que la app precarga desde una cita al «Registrar como
 * observación» (C4b): conservarlo tal cual (UNEDITED) o reescribirlo (EDITED), y qué tipo de
 * hallazgo eligió. Dataset ANÓNIMO (patrón V32/V33): referencia al control del catálogo y no a la
 * evaluación, la organización ni el usuario. Se escribe solo al guardar la observación.
 */
@Entity
@Table(name = "ai_citation_validation_feedback", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCitationValidationFeedback {

  public enum Outcome {
    UNEDITED,
    EDITED
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

  @Column(nullable = false, columnDefinition = "TEXT")
  private String snippet;

  private Double score;

  @Column(name = "prefill_text", nullable = false, columnDefinition = "TEXT")
  private String prefillText;

  @Column(name = "final_text", nullable = false, columnDefinition = "TEXT")
  private String finalText;

  @Column(name = "final_type", nullable = false, length = 20)
  private String finalType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  private Outcome outcome;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
