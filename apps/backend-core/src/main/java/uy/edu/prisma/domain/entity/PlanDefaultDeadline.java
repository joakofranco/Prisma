package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/** RF-PLN-03 — plazo por defecto (en meses) de una prioridad del plan de mejora. */
@Entity
@Table(name = "plan_default_deadlines", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanDefaultDeadline {

  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 10)
  private ImprovementPlan.Priority priority;

  @Column(nullable = false)
  private int months;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
