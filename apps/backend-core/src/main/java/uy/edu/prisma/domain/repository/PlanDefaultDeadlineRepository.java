package uy.edu.prisma.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.ImprovementPlan.Priority;
import uy.edu.prisma.domain.entity.PlanDefaultDeadline;

public interface PlanDefaultDeadlineRepository
    extends JpaRepository<PlanDefaultDeadline, Priority> {}
