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

  // PRI-368: :search llega como patrón ya plegado (minúsculas, sin tildes ni puntos, con los %) o
  // null; ver uy.edu.prisma.application.SearchText. Busca en lo que muestra la tabla: email,
  // «Nombre Apellido» y nombre de alguna de sus organizaciones. Ver SearchSql.
  String SEARCH_CONDITION =
      "(:search IS NULL "
          + "OR "
          + SearchSql.FOLD_PRE
          + "u.email"
          + SearchSql.FOLD_POST
          + " LIKE CAST(:search AS string) "
          + "OR "
          + SearchSql.FOLD_PRE
          + "CONCAT(u.firstName, ' ', u.lastName)"
          + SearchSql.FOLD_POST
          + " LIKE CAST(:search AS string) "
          + "OR EXISTS (SELECT 1 FROM u.organizations so WHERE "
          + SearchSql.FOLD_PRE
          + "so.name"
          + SearchSql.FOLD_POST
          + " LIKE CAST(:search AS string)))";

  // Ver el comentario en OrganizationRepository.search: sin el CAST, un search null hace fallar
  // la query en Postgres con "function lower(bytea) does not exist".
  @Query("SELECT u FROM User u WHERE " + SEARCH_CONDITION)
  Page<User> search(@Param("search") String search, Pageable pageable);

  // Variante acotada a un conjunto de organizaciones (aislamiento multi-tenant): usuarios que
  // pertenecen a ALGUNA de ellas. Usada por UserService.list para todo rol que no sea PRISMA_ADMIN.
  @Query(
      "SELECT DISTINCT u FROM User u JOIN u.organizations o WHERE o.id IN :orgIds AND "
          + SEARCH_CONDITION)
  Page<User> searchByOrganizationIdIn(
      @Param("orgIds") Collection<UUID> orgIds, @Param("search") String search, Pageable pageable);

  // Usada por EvaluationService para mostrar "qué auditor está asignado" en la grilla de
  // evaluaciones: usuarios con rol AUDITOR que tienen alguna de estas organizaciones. Ahora que
  // cualquier rol puede tener organizaciones, hay que filtrar explícitamente por el rol.
  @Query(
      "SELECT DISTINCT u FROM User u JOIN u.organizations o JOIN u.roles r "
          + "WHERE o.id IN :orgIds AND r = uy.edu.prisma.domain.entity.UserRole.AUDITOR")
  List<User> findAuditorsForOrganizations(@Param("orgIds") Collection<UUID> orgIds);

  // Datos que referencian a un usuario por FK (sin ON DELETE): si hay alguno, la fila NO se puede
  // borrar sin perder el historial (ver UserService.delete) -- se deshabilita en su lugar.
  @Query("SELECT COUNT(e) FROM Evaluation e WHERE e.createdBy.id = :userId")
  long countEvaluationsCreatedBy(@Param("userId") UUID userId);

  @Query("SELECT COUNT(r) FROM EvaluationResponse r WHERE r.respondedBy.id = :userId")
  long countResponsesBy(@Param("userId") UUID userId);

  @Query("SELECT COUNT(e) FROM Evidence e WHERE e.uploadedBy.id = :userId")
  long countEvidenceUploadedBy(@Param("userId") UUID userId);

  @Query("SELECT COUNT(o) FROM AuditObservation o WHERE o.createdBy.id = :userId")
  long countAuditObservationsBy(@Param("userId") UUID userId);

  @Query("SELECT COUNT(o) FROM Organization o WHERE o.responsible.id = :userId")
  long countOrganizationsResponsibleFor(@Param("userId") UUID userId);

  // Usuarios que pertenecen a la organización dada (cualquier rol; la membresía es M:N).
  @Query("SELECT DISTINCT u FROM User u JOIN u.organizations o WHERE o.id = :organizationId")
  List<User> findByOrganizationId(@Param("organizationId") UUID organizationId);

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
