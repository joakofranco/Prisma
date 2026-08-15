package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.CatalogControl;

public interface CatalogControlRepository extends JpaRepository<CatalogControl, UUID> {

  // Un Requisito puede pertenecer a varias Subcategorías (ver CatalogRequirementSubcategory), así
  // que ya no hay un único camino Requisito->Subcategoría->Categoría->Función por el que ordenar.
  // El orden por Subcategoría/Categoría/Función no le hace falta a ninguno de los dos consumidores
  // de este metodo (EvaluationService.calculateMaturity reagrupa, ImprovementPlanService.suggest
  // reordena despues por prioridad), asi que alcanza con ordenar por Requisito y Control.
  @Query(
      "SELECT c FROM CatalogControl c "
          + "JOIN c.requirement r "
          + "JOIN r.version v "
          + "WHERE v.version = :version "
          + "ORDER BY r.sortOrder, c.sortOrder")
  List<CatalogControl> findByVersionOrdered(@Param("version") String version);
}
