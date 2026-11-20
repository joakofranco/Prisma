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
 * Guía de mejora generada por IA y persistida (C5), para registrar el feedback en el servidor. La
 * organización solo sirve para autorizar el acceso: el dataset de feedback ({@link
 * AiSuggestionFeedback}) sigue sin organización ni usuario. Ver V41.
 */
@Entity
@Table(name = "ai_generated_guide", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiGeneratedGuide {

  public enum Status {
    /** Generada, todavía no la vio quien la pidió: no cuenta para el feedback. */
    GENERATED,
    SHOWN,
    USED,
    /** Reservado: descarte explícito. */
    DISCARDED,
    EXPIRED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "job_id")
  private UUID jobId;

  @Column(name = "organization_id")
  private UUID organizationId;

  @Column(name = "requested_by")
  private UUID requestedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id", nullable = false)
  private CatalogControl control;

  @Column(name = "current_level", nullable = false)
  private int currentLevel;

  @Column(name = "target_level", nullable = false)
  private int targetLevel;

  @Column(length = 100)
  private String model;

  @Column(name = "prompt_version", nullable = false, length = 20)
  @Builder.Default
  private String promptVersion = "v1";

  @Column(nullable = false)
  @Builder.Default
  private String summary = "";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<String> tips = new ArrayList<>();

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private Status status = Status.GENERATED;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "shown_at")
  private OffsetDateTime shownAt;

  @Column(name = "used_at")
  private OffsetDateTime usedAt;
}
