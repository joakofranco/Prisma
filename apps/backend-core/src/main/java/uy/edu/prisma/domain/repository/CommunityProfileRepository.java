package uy.edu.prisma.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.CommunityProfile;

public interface CommunityProfileRepository extends JpaRepository<CommunityProfile, UUID> {

  List<CommunityProfile> findByCatalogVersionOrderByNameAsc(String catalogVersion);

  // Evaluaciones (de cualquier estado) que usan este perfil. Consulta propia en vez de un campo del
  // repositorio de evaluaciones para no acoplar CommunityProfileService a otro repositorio.
  @Query("SELECT COUNT(e) FROM Evaluation e WHERE e.communityProfile.id = :profileId")
  long countEvaluationsUsing(@Param("profileId") UUID profileId);

  // Perfiles comunitarios armados sobre esa versión de catálogo (CommunityProfile.catalogVersion es
  // un string libre, no una FK, así que la integridad se valida a mano al borrar la versión).
  long countByCatalogVersion(String catalogVersion);
}
