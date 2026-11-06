package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.EvaluationControlFlag;

public interface EvaluationControlFlagRepository
    extends JpaRepository<EvaluationControlFlag, UUID> {

  List<EvaluationControlFlag> findByEvaluationId(UUID evaluationId);

  Optional<EvaluationControlFlag> findByEvaluationIdAndControlId(UUID evaluationId, UUID controlId);
}
