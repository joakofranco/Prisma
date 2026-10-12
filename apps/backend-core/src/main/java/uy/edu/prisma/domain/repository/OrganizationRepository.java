package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

  // El CAST(:search AS string) es necesario: sin él, cuando search es null Postgres no logra
  // inferir el tipo del parámetro dentro de LOWER(CONCAT(...)) y falla con
  // "function lower(bytea) does not exist" (Postgres tipa la expresión completa al preparar el
  // statement, sin importar que ":search IS NULL" haría cortocircuito en tiempo de ejecución).
  @Query(
      "SELECT o FROM Organization o WHERE "
          + "(:search IS NULL OR LOWER(o.name) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(o.rut) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')))")
  Page<Organization> search(@Param("search") String search, Pageable pageable);

  // Variante acotada a un conjunto de organizaciones (aislamiento multi-tenant): usada por
  // OrganizationService.list para todo rol que no sea PRISMA_ADMIN, ver el comentario ahí.
  @Query(
      "SELECT o FROM Organization o WHERE o.id IN :allowedIds AND "
          + "(:search IS NULL OR LOWER(o.name) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(o.rut) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')))")
  Page<Organization> searchByIdIn(
      @Param("allowedIds") Collection<UUID> allowedIds,
      @Param("search") String search,
      Pageable pageable);

  // Organizaciones cuyo RUT coincide con `normalizedRut` ignorando puntos, guiones, espacios y
  // mayúsculas: "21.234.567-0", "212345670" y " 21 234 567 0 " son el MISMO RUT. La restricción
  // única de la base compara el texto tal cual, así que no atraparía estas variantes.
  @Query(
      "SELECT o FROM Organization o WHERE o.rut IS NOT NULL AND "
          + "REPLACE(REPLACE(REPLACE(UPPER(o.rut), '.', ''), '-', ''), ' ', '') = :normalizedRut")
  List<Organization> findByNormalizedRut(@Param("normalizedRut") String normalizedRut);

  long countByEnabledTrue();

  long countByIdInAndEnabledTrue(Collection<UUID> ids);
}
