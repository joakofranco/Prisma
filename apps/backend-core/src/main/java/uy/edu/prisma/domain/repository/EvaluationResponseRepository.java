package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.EvaluationResponse;

public interface EvaluationResponseRepository extends JpaRepository<EvaluationResponse, UUID> {

  List<EvaluationResponse> findByEvaluationId(UUID evaluationId);

  Optional<EvaluationResponse> findByEvaluationIdAndControlId(UUID evaluationId, UUID controlId);
}
