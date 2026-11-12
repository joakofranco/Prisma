package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Qué se hizo con una guía de mejora generada por IA (PRI-370). Alimenta un dataset futuro de
 * fine-tuning, por eso referencia al control del catálogo y no a la evaluación, la organización ni
 * el usuario.
 */
@Entity
@Table(name = "ai_suggestion_feedback", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiSuggestionFeedback {

  public enum Outcome {
    ACCEPTED_UNEDITED,
    ACCEPTED_EDITED,
    REJECTED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @Column(name = "current_level", nullable = false)
  private int currentLevel;

  @Column(name = "target_level", nullable = false)
  private int targetLevel;

  @Column(name = "original_summary", nullable = false)
  @Builder.Default
  private String originalSummary = "";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "original_tips", nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> originalTips = new ArrayList<>();

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Outcome outcome;

  @Column(name = "edited_text")
  private String editedText;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
