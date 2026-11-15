package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AuditControlVerdict;

public interface AuditControlVerdictRepository extends JpaRepository<AuditControlVerdict, UUID> {

  List<AuditControlVerdict> findByEvaluationId(UUID evaluationId);

  Optional<AuditControlVerdict> findByEvaluationIdAndControlId(UUID evaluationId, UUID controlId);
}
