package uy.edu.prisma.domain.repository;

import java.util.Collection;
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

  // Plegado de tildes y mayúsculas para comparar «lo que se ve» con lo que escribe quien busca.
  String FOLD_FROM = "'áéíóúüñÁÉÍÓÚÜÑ'";
  String FOLD_TO = "'aeiouunAEIOUUN'";

  // tenantId null = sin acotar (PRISMA_ADMIN, ve todos los tenants); para el resto de los roles
  // que llegan a AuditLogService.list, AuditLogService siempre lo pasa con el tenant del usuario
  // actual (ver el comentario ahí -- aislamiento multi-tenant, mismo criterio que
  // UserRepository.searchByTenantIdIn). Ver el comentario en OrganizationRepository.search sobre
  // el CAST: sin él, un :search null hace fallar la query en Postgres.
  //
  // PRI-368: :search es el patrón ya plegado (minúsculas, sin tildes) y con los %. Busca tanto en
  // lo que muestra la pantalla (email y nombre del usuario, organización, acción en español,
  // tipo y nombre del recurso afectado, IP) como en los códigos internos (código de acción,
  // «tipo:uuid» del recurso, uuid del usuario y de la organización). Los uuid se comparan contra
  // las columnas de la propia bitácora, así que siguen encontrando filas aunque el usuario o el
  // recurso ya se hayan eliminado. :actionCodes y :resourceTypes los calcula el servicio a partir
  // de las etiquetas en español (nunca vacíos, para que el IN sea válido).
  @Query(
      "SELECT a FROM AuditLog a WHERE "
          + "(:tenantId IS NULL OR a.tenantId = :tenantId) AND "
          + "(:action IS NULL OR a.action = :action) AND "
          + "(:search IS NULL "
          + "OR LOWER(a.action) LIKE CAST(:search AS string) "
          + "OR a.action IN :actionCodes "
          + "OR LOWER(a.resource) LIKE CAST(:search AS string) "
          + "OR CASE WHEN LOCATE(':', a.resource) > 0 "
          + "THEN SUBSTRING(a.resource, 1, LOCATE(':', a.resource) - 1) "
          + "ELSE a.resource END IN :resourceTypes "
          + "OR LOWER(CAST(a.userId AS string)) LIKE CAST(:search AS string) "
          + "OR LOWER(CAST(a.tenantId AS string)) LIKE CAST(:search AS string) "
          + "OR LOWER(COALESCE(a.ipAddress, '')) LIKE CAST(:search AS string) "
          // Actor (columna «Usuario»): email o «Nombre Apellido».
          + "OR EXISTS (SELECT 1 FROM User u WHERE u.id = a.userId AND ("
          + "LOWER(FUNCTION('translate', u.email, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string) "
          + "OR LOWER(FUNCTION('translate', CONCAT(u.firstName, ' ', u.lastName), "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string))) "
          // Organización (columna «Organización»).
          + "OR EXISTS (SELECT 1 FROM Organization t WHERE t.id = a.tenantId AND "
          + "LOWER(FUNCTION('translate', t.name, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string)) "
          // Recurso afectado (columna «Recurso Afectado»): nombre de la entidad referenciada.
          + "OR EXISTS (SELECT 1 FROM User ru WHERE a.resource = CONCAT('user:', CAST(ru.id AS string)) AND ("
          + "LOWER(FUNCTION('translate', ru.email, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string) "
          + "OR LOWER(FUNCTION('translate', CONCAT(ru.firstName, ' ', ru.lastName), "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string))) "
          + "OR EXISTS (SELECT 1 FROM Organization ro WHERE a.resource = CONCAT('organization:', CAST(ro.id AS string)) AND "
          + "LOWER(FUNCTION('translate', ro.name, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string)) "
          + "OR EXISTS (SELECT 1 FROM Evaluation re WHERE a.resource = CONCAT('evaluation:', CAST(re.id AS string)) AND "
          + "LOWER(FUNCTION('translate', re.name, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string)) "
          + "OR EXISTS (SELECT 1 FROM CatalogVersion rc WHERE a.resource = CONCAT('catalog_version:', CAST(rc.id AS string)) AND "
          + "LOWER(FUNCTION('translate', rc.label, "
          + FOLD_FROM
          + ", "
          + FOLD_TO
          + ")) LIKE CAST(:search AS string)))")
  Page<AuditLog> search(
      @Param("tenantId") UUID tenantId,
      @Param("action") String action,
      @Param("search") String search,
      @Param("actionCodes") Collection<String> actionCodes,
      @Param("resourceTypes") Collection<String> resourceTypes,
      Pageable pageable);
}
