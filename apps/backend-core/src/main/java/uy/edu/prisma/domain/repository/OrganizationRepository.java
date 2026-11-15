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

  // PRI-368: :search es el patrón del nombre y :idSearch el del identificador, ambos ya plegados
  // (sin tildes, mayúsculas ni puntos; el del identificador también sin guiones ni espacios) o
  // null; ver
  // uy.edu.prisma.application.SearchText. Así «Organización» encuentra «Organizacion» y
  // «1.234.567-8» encuentra «12345678».
  String SEARCH_CONDITION =
      "(:search IS NULL "
          + "OR "
          + SearchSql.FOLD_PRE
          + "o.name"
          + SearchSql.FOLD_POST
          + " LIKE CAST(:search AS string) "
          + "OR "
          + SearchSql.ID_PRE
          + "COALESCE(o.identifier, '')"
          + SearchSql.ID_POST
          + " LIKE CAST(:idSearch AS string))";

  // El CAST(:search AS string) es necesario: sin él, cuando search es null Postgres no logra
  // inferir el tipo del parámetro dentro de LOWER(CONCAT(...)) y falla con
  // "function lower(bytea) does not exist" (Postgres tipa la expresión completa al preparar el
  // statement, sin importar que ":search IS NULL" haría cortocircuito en tiempo de ejecución).
  @Query("SELECT o FROM Organization o WHERE " + SEARCH_CONDITION)
  Page<Organization> search(
      @Param("search") String search, @Param("idSearch") String idSearch, Pageable pageable);

  // Variante acotada a un conjunto de organizaciones (aislamiento multi-tenant): usada por
  // OrganizationService.list para todo rol que no sea PRISMA_ADMIN, ver el comentario ahí.
  @Query("SELECT o FROM Organization o WHERE o.id IN :allowedIds AND " + SEARCH_CONDITION)
  Page<Organization> searchByIdIn(
      @Param("allowedIds") Collection<UUID> allowedIds,
      @Param("search") String search,
      @Param("idSearch") String idSearch,
      Pageable pageable);

  // Organizaciones cuyo identificador coincide con `normalizedIdentifier` ignorando puntos,
  // guiones, espacios y mayúsculas: "21-234567-001-0", "212345670010" y " 21 234567 001 0 " son el
  // MISMO identificador, sea o no un RUT. La restricción única de la base compara el texto tal
  // cual, así que no atraparía estas variantes.
  @Query(
      "SELECT o FROM Organization o WHERE o.identifier IS NOT NULL AND "
          + "REPLACE(REPLACE(REPLACE(UPPER(o.identifier), '.', ''), '-', ''), ' ', '') ="
          + " :normalizedIdentifier")
  List<Organization> findByNormalizedIdentifier(
      @Param("normalizedIdentifier") String normalizedIdentifier);

  long countByEnabledTrue();

  long countByIdInAndEnabledTrue(Collection<UUID> ids);
}
