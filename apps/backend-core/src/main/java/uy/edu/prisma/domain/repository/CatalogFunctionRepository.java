package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.CatalogFunction;

public interface CatalogFunctionRepository extends JpaRepository<CatalogFunction, UUID> {

  @Query(
      "SELECT f FROM CatalogFunction f JOIN FETCH f.version v "
          + "LEFT JOIN FETCH f.categories c "
          + "WHERE v.version = :version ORDER BY f.sortOrder")
  List<CatalogFunction> findByVersionWithCategories(@Param("version") String version);

  List<CatalogFunction> findByVersionIdOrderBySortOrder(UUID versionId);
}
