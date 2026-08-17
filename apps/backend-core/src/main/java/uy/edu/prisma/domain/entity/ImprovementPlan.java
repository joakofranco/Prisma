package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "improvement_plans", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImprovementPlan {

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum PlanStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    OVERDUE
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id")
  private CatalogControl control;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String action;

  @Column(length = 200)
  private String responsible;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  @Builder.Default
  private Priority priority = Priority.MEDIUM;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private PlanStatus status = PlanStatus.PENDING;

  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
