package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.Evidence;

public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

  List<Evidence> findByEvaluationIdOrderByUploadedAtDesc(UUID evaluationId);

  void deleteByEvaluationId(UUID evaluationId);
}
