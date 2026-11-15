package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.EvaluationSubcategoryComment;

public interface EvaluationSubcategoryCommentRepository
    extends JpaRepository<EvaluationSubcategoryComment, UUID> {

  List<EvaluationSubcategoryComment> findByEvaluationId(UUID evaluationId);

  Optional<EvaluationSubcategoryComment> findByEvaluationIdAndSubcategoryId(
      UUID evaluationId, UUID subcategoryId);
}
