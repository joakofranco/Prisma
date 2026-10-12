package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.Evaluation;

public interface EvaluationRepository extends JpaRepository<Evaluation, UUID> {

  Page<Evaluation> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
      UUID organizationId, Evaluation.Status status, Pageable pageable);

  Page<Evaluation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

  Page<Evaluation> findByStatusOrderByCreatedAtDesc(Evaluation.Status status, Pageable pageable);

  Page<Evaluation> findAllByOrderByCreatedAtDesc(Pageable pageable);

  long countByStatus(Evaluation.Status status);

  long countByOrganizationId(UUID organizationId);

  long countByOrganizationIdAndStatus(UUID organizationId, Evaluation.Status status);

  // Variantes "In" para AUDITOR: a diferencia de los roles acotados a un único tenant, un auditor
  // puede tener varias organizaciones asignadas a la vez (ver CurrentUserService), así que listar
  // "todo lo que puede ver" sin un organizationId puntual necesita filtrar por el conjunto entero.
  Page<Evaluation> findByOrganizationIdInOrderByCreatedAtDesc(
      Collection<UUID> organizationIds, Pageable pageable);

  Page<Evaluation> findByOrganizationIdInAndStatusOrderByCreatedAtDesc(
      Collection<UUID> organizationIds, Evaluation.Status status, Pageable pageable);

  long countByOrganizationIdIn(Collection<UUID> organizationIds);

  long countByOrganizationIdInAndStatus(Collection<UUID> organizationIds, Evaluation.Status status);

  // Usado por CatalogService.deleteVersion para no dejar evaluaciones huérfanas apuntando a una
  // versión de catálogo que ya no existe: Evaluation.catalogVersion es un string libre, no una FK
  // hacia CatalogVersion, así que la integridad referencial hay que garantizarla acá a mano -- la
  // base no la impone.
  // Datos que cuelgan de una evaluación con ON DELETE CASCADE: borrarla los destruiría en silencio
  // (y dejaría archivos huérfanos en MinIO), así que EvaluationService.delete solo la permite
  // cuando todos estos conteos son 0.
  @Query("SELECT COUNT(r) FROM EvaluationResponse r WHERE r.evaluation.id = :evaluationId")
  long countResponses(@Param("evaluationId") UUID evaluationId);

  @Query("SELECT COUNT(e) FROM Evidence e WHERE e.evaluation.id = :evaluationId")
  long countEvidence(@Param("evaluationId") UUID evaluationId);

  @Query("SELECT COUNT(o) FROM AuditObservation o WHERE o.evaluation.id = :evaluationId")
  long countAuditObservations(@Param("evaluationId") UUID evaluationId);

  @Query("SELECT COUNT(p) FROM ImprovementPlan p WHERE p.evaluation.id = :evaluationId")
  long countImprovementPlans(@Param("evaluationId") UUID evaluationId);

  @Query("SELECT COUNT(m) FROM MaturityResult m WHERE m.evaluation.id = :evaluationId")
  long countMaturityResults(@Param("evaluationId") UUID evaluationId);

  boolean existsByCatalogVersion(String catalogVersion);
}
