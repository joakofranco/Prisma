package uy.edu.prisma.domain.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.CatalogSubcategory;

public interface CatalogSubcategoryRepository extends JpaRepository<CatalogSubcategory, UUID> {

  /**
   * Cantidad total de subcategorías de una versión de catálogo. Es el denominador del promedio de
   * madurez (EvaluationService.calculateMaturity): se promedia sobre TODAS las subcategorías de la
   * versión, con nivel 0 en las que el perfil no cubre -- igual que la planilla de Agesic.
   */
  @Query(
      "SELECT COUNT(s) FROM CatalogSubcategory s "
          + "JOIN s.category c JOIN c.function_ f JOIN f.version v "
          + "WHERE v.version = :version")
  long countByCatalogVersion(@Param("version") String version);
}
