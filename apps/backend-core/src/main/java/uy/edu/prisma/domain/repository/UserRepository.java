package uy.edu.prisma.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.User;

public interface UserRepository extends JpaRepository<User, UUID> {

  // Ver el comentario en OrganizationRepository.search: sin el CAST, un search null hace fallar
  // la query en Postgres con "function lower(bytea) does not exist".
  @Query(
      "SELECT u FROM User u WHERE "
          + "(:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(u.firstName) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(u.lastName) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')))")
  Page<User> search(@Param("search") String search, Pageable pageable);

  // Variante acotada a un conjunto de organizaciones (aislamiento multi-tenant): usuarios que
  // pertenecen a ALGUNA de ellas. Usada por UserService.list para todo rol que no sea PRISMA_ADMIN.
  @Query(
      "SELECT DISTINCT u FROM User u JOIN u.organizations o WHERE o.id IN :orgIds AND "
          + "(:search IS NULL OR LOWER(u.email) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(u.firstName) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(u.lastName) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')))")
  Page<User> searchByOrganizationIdIn(
      @Param("orgIds") Collection<UUID> orgIds,
      @Param("search") String search,
      Pageable pageable);

  // Usada por EvaluationService para mostrar "qué auditor está asignado" en la grilla de
  // evaluaciones: usuarios con rol AUDITOR que tienen alguna de estas organizaciones. Ahora que
  // cualquier rol puede tener organizaciones, hay que filtrar explícitamente por el rol.
  @Query(
      "SELECT DISTINCT u FROM User u JOIN u.organizations o JOIN u.roles r "
          + "WHERE o.id IN :orgIds AND r = uy.edu.prisma.domain.entity.UserRole.AUDITOR")
  List<User> findAuditorsForOrganizations(@Param("orgIds") Collection<UUID> orgIds);

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
