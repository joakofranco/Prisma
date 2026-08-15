package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.MaturityResult;

public interface MaturityResultRepository extends JpaRepository<MaturityResult, UUID> {

  List<MaturityResult> findByEvaluationId(UUID evaluationId);
}
