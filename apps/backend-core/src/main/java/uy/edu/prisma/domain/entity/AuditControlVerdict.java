package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Dictamen del auditor sobre un control de una evaluación (uno por evaluación y control). */
@Entity
@Table(name = "audit_control_verdicts", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditControlVerdict {

  public enum Verdict {
    VALIDATED,
    NOT_VALIDATED,
    NEEDS_MORE_EVIDENCE
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

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private Verdict verdict;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "set_by")
  private User setBy;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
