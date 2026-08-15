package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
  boolean existsByCatalogVersion(String catalogVersion);
}
