package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AuditObservation;

public interface AuditObservationRepository extends JpaRepository<AuditObservation, UUID> {

  List<AuditObservation> findByEvaluationIdOrderByCreatedAtDesc(UUID evaluationId);
}
