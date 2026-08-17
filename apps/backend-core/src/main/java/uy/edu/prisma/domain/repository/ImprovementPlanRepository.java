package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uy.edu.prisma.domain.entity.ImprovementPlan;
import uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus;

public interface ImprovementPlanRepository extends JpaRepository<ImprovementPlan, UUID> {

  List<ImprovementPlan> findByEvaluationIdOrderByCreatedAtDesc(UUID evaluationId);

  long countByStatusNot(PlanStatus status);

  long countByEvaluation_Organization_IdAndStatusNot(UUID organizationId, PlanStatus status);

  // Variante "In" para AUDITOR (varias organizaciones asignadas a la vez, ver
  // EvaluationRepository).
  long countByEvaluation_Organization_IdInAndStatusNot(
      Collection<UUID> organizationIds, PlanStatus status);

  @Modifying
  @Query(
      "update ImprovementPlan p set p.status = "
          + "uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus.OVERDUE "
          + "where p.status in (uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus.PENDING, "
          + "uy.edu.prisma.domain.entity.ImprovementPlan.PlanStatus.IN_PROGRESS) "
          + "and p.dueDate < current_date")
  int markOverdue();
}
