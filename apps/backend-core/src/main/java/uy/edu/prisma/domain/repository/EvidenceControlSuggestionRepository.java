package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.EvidenceControlSuggestion;

public interface EvidenceControlSuggestionRepository
    extends JpaRepository<EvidenceControlSuggestion, UUID> {

  List<EvidenceControlSuggestion> findByEvaluationIdOrderByEvidenceIdAscRankAsc(UUID evaluationId);

  List<EvidenceControlSuggestion> findByEvidenceId(UUID evidenceId);

  /** Se rehacen las no decididas; las decididas se conservan (y no se vuelven a sugerir). */
  @Modifying
  @Query(
      "DELETE FROM EvidenceControlSuggestion s WHERE s.evidence.id = :evidenceId AND s.status ="
          + " uy.edu.prisma.domain.entity.EvidenceControlSuggestion.Status.PENDING")
  int deletePendingOfEvidence(@Param("evidenceId") UUID evidenceId);
}
