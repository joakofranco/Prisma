package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

  // RF-AUD-10: historial de ciclos de auditoría de UNA evaluación puntual (ver
  // AuditLogService.getEvaluationAuditTrail) -- a diferencia de search() de abajo, no pagina (el
  // volumen de transiciones de una sola evaluación es acotado) y va en orden cronológico
  // ascendente para poder numerar los ciclos en el mismo recorrido.
  List<AuditLog> findByResourceAndActionInOrderByCreatedAtAsc(String resource, List<String> action);

  // tenantId null = sin acotar (PRISMA_ADMIN, ve todos los tenants); para el resto de los roles
  // que llegan a AuditLogService.list, AuditLogService siempre lo pasa con el tenant del usuario
  // actual (ver el comentario ahí -- aislamiento multi-tenant, mismo criterio que
  // UserRepository.searchByTenantIdIn). Ver el comentario en OrganizationRepository.search sobre
  // el CAST: sin él, un :search null hace fallar la query en Postgres.
  @Query(
      "SELECT a FROM AuditLog a WHERE "
          + "(:tenantId IS NULL OR a.tenantId = :tenantId) AND "
          + "(:action IS NULL OR a.action = :action) AND "
          + "(:search IS NULL OR LOWER(a.resource) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')) "
          + "OR LOWER(a.action) LIKE LOWER(CONCAT('%',CAST(:search AS string),'%')))")
  Page<AuditLog> search(
      @Param("tenantId") UUID tenantId,
      @Param("action") String action,
      @Param("search") String search,
      Pageable pageable);
}
