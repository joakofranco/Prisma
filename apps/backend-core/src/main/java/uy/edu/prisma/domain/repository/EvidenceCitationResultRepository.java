package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.EvidenceCitationResult;
import uy.edu.prisma.domain.entity.EvidenceCitationResult.Status;

public interface EvidenceCitationResultRepository
    extends JpaRepository<EvidenceCitationResult, UUID> {

  List<EvidenceCitationResult> findByEvaluationIdAndControlIdOrderByPositionAsc(
      UUID evaluationId, UUID controlId);

  List<EvidenceCitationResult> findByEvaluationIdOrderByControlIdAscPositionAsc(UUID evaluationId);

  List<EvidenceCitationResult> findByEvaluationIdAndControlIdAndStatus(
      UUID evaluationId, UUID controlId, Status status);

  @Modifying
  @Query("DELETE FROM EvidenceCitationResult r WHERE r.evaluation.id = :evaluationId")
  int deleteByEvaluation(@Param("evaluationId") UUID evaluationId);

  /** Las no decididas se descartan: no hay nada que conservar y la próxima búsqueda las rehace. */
  @Modifying
  @Query(
      "DELETE FROM EvidenceCitationResult r WHERE r.evaluation.id = :evaluationId AND"
          + " r.control.id = :controlId AND r.status = :status")
  int deleteByControlAndStatus(
      @Param("evaluationId") UUID evaluationId,
      @Param("controlId") UUID controlId,
      @Param("status") Status status);

  /** Las decididas se marcan viejas: la próxima consulta vuelve a buscar y conserva la decisión. */
  @Modifying
  @Query(
      "UPDATE EvidenceCitationResult r SET r.stale = true WHERE r.evaluation.id = :evaluationId AND"
          + " r.control.id = :controlId AND r.status <> :pending")
  int markDecidedStale(
      @Param("evaluationId") UUID evaluationId,
      @Param("controlId") UUID controlId,
      @Param("pending") Status pending);

  /** Cambio de modelo de embedding: ningún puntaje guardado vale. Las no decididas se descartan. */
  @Modifying
  @Query("DELETE FROM EvidenceCitationResult r WHERE r.status = :status")
  int deleteAllByStatus(@Param("status") Status status);

  /**
   * ... y las decididas se marcan viejas: la próxima búsqueda las rehace conservando la decisión.
   */
  @Modifying
  @Query("UPDATE EvidenceCitationResult r SET r.stale = true WHERE r.status <> :pending")
  int markAllDecidedStale(@Param("pending") Status pending);

  boolean existsByEvaluationIdAndControlIdAndStaleTrue(UUID evaluationId, UUID controlId);

  @Query(
      "SELECT DISTINCT r.control.id FROM EvidenceCitationResult r WHERE r.evaluation.id ="
          + " :evaluationId AND r.control.id IN :controlIds")
  List<UUID> findControlsWithResults(
      @Param("evaluationId") UUID evaluationId, @Param("controlIds") Collection<UUID> controlIds);
}
