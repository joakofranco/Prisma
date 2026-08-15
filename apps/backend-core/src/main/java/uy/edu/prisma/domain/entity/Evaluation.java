package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "evaluations", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evaluation {

  public enum Status {
    DRAFT,
    IN_PROGRESS,
    READY_FOR_AUDIT,
    IN_AUDIT,
    APPROVED,
    RETURNED,
    ARCHIVED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "organization_id", nullable = false)
  private Organization organization;

  @Column(name = "catalog_version", nullable = false, length = 20)
  @Builder.Default
  private String catalogVersion = "5.0";

  /** null = evaluación sobre el catálogo completo (comportamiento por defecto). */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "community_profile_id")
  private CommunityProfile communityProfile;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  @Builder.Default
  private Status status = Status.DRAFT;

  @Column(name = "global_maturity")
  private Integer globalMaturity;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by")
  private User createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }

  // Única fuente de verdad de "en qué estados se puede seguir tocando la autoevaluación" --
  // usado tanto por EvaluationService.saveResponse (respuestas de control) como por
  // EvidenceService (subir/borrar/reindexar evidencia): una vez enviada a auditoría
  // (READY_FOR_AUDIT en adelante), la organización ya no puede seguir cambiando nada de lo que el
  // auditor está revisando -- ni respuestas ni evidencia -- hasta que vuelva a RETURNED.
  public boolean isSelfAssessmentEditable() {
    return status == Status.DRAFT || status == Status.IN_PROGRESS || status == Status.RETURNED;
  }
}
