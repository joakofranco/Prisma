package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uy.edu.prisma.domain.entity.EvaluationResponse;

public interface EvaluationResponseRepository extends JpaRepository<EvaluationResponse, UUID> {

  List<EvaluationResponse> findByEvaluationId(UUID evaluationId);

  Optional<EvaluationResponse> findByEvaluationIdAndControlId(UUID evaluationId, UUID controlId);

  // RF-VIZ-07 (PRI-321): organizaciones activas ordenadas por su respuesta más reciente; la
  // tendencia del Panel del administrador arranca en la primera.
  @Query(
      "SELECT e.organization.id FROM EvaluationResponse r JOIN r.evaluation e"
          + " WHERE e.organization.enabled = true"
          + " GROUP BY e.organization.id ORDER BY MAX(r.respondedAt) DESC")
  List<UUID> findOrganizationIdsByLatestResponse(Pageable pageable);
}
