package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.Evidence;

public interface EvidenceRepository extends JpaRepository<Evidence, UUID> {

  List<Evidence> findByEvaluationIdOrderByUploadedAtDesc(UUID evaluationId);

  List<Evidence> findByScanStatusOrderByUploadedAtAsc(Evidence.ScanStatus scanStatus);

  void deleteByEvaluationId(UUID evaluationId);

  /** Controles de la evaluación que tienen evidencia ya indexada (para precalcular sus citas). */
  @Query(
      "SELECT DISTINCT e.control.id FROM Evidence e WHERE e.evaluation.id = :evaluationId AND"
          + " e.control IS NOT NULL AND e.indexStatus ="
          + " uy.edu.prisma.domain.entity.Evidence.IndexStatus.INDEXED")
  List<UUID> findIndexedControlIds(@Param("evaluationId") UUID evaluationId);
}
