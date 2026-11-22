package uy.edu.prisma.domain.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.AiJob;
import uy.edu.prisma.domain.entity.AiJob.Status;

public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

  Optional<AiJob> findFirstByDedupKeyAndStatusIn(String dedupKey, Collection<Status> statuses);

  long countByStatus(Status status);

  long countByOrganizationIdAndStatusIn(UUID organizationId, Collection<Status> statuses);

  /**
   * Trabajos sin organización (p. ej. PRISMA_ADMIN sin contexto): el tope se aplica por usuario.
   */
  long countByOrganizationIdIsNullAndRequestedByAndStatusIn(
      UUID requestedBy, Collection<Status> statuses);

  List<AiJob> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy, Pageable pageable);

  /**
   * Reclama hasta {@code limit} trabajos en cola listos para correr, el interactivo primero. {@code
   * FOR UPDATE SKIP LOCKED}: dos réplicas que reclaman a la vez nunca se llevan el mismo trabajo.
   */
  @Query(
      value =
          "SELECT * FROM prisma.ai_job WHERE status = 'QUEUED' AND next_attempt_at <= now()"
              + " ORDER BY priority DESC, created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<AiJob> lockQueued(@Param("limit") int limit);

  @Query("SELECT j FROM AiJob j WHERE j.status = :status AND j.lockedUntil < :now")
  List<AiJob> findExpiredLocks(@Param("status") Status status, @Param("now") OffsetDateTime now);

  /** Posición en la cola (1 = próximo): los de mayor prioridad y los más viejos van antes. */
  @Query(
      "SELECT COUNT(j) FROM AiJob j WHERE j.status = uy.edu.prisma.domain.entity.AiJob.Status.QUEUED"
          + " AND (j.priority > :priority OR (j.priority = :priority AND j.createdAt < :createdAt))")
  long countAhead(@Param("priority") int priority, @Param("createdAt") OffsetDateTime createdAt);

  /** Cancela solo si sigue en cola: evita pisar a un trabajo que otra réplica ya reclamó. */
  @Modifying
  @Query(
      "UPDATE AiJob j SET j.status = uy.edu.prisma.domain.entity.AiJob.Status.CANCELLED,"
          + " j.finishedAt = :now WHERE j.id = :id"
          + " AND j.status = uy.edu.prisma.domain.entity.AiJob.Status.QUEUED")
  int cancelIfQueued(@Param("id") UUID id, @Param("now") OffsetDateTime now);

  @Modifying
  @Query(
      "UPDATE AiJob j SET j.status = uy.edu.prisma.domain.entity.AiJob.Status.EXPIRED"
          + " WHERE j.status = uy.edu.prisma.domain.entity.AiJob.Status.SUCCEEDED"
          + " AND j.expiresAt < :now")
  int expireSucceeded(@Param("now") OffsetDateTime now);

  @Modifying
  @Query("DELETE FROM AiJob j WHERE j.status IN :statuses AND j.finishedAt < :before")
  int deleteFinishedBefore(
      @Param("statuses") Collection<Status> statuses, @Param("before") OffsetDateTime before);
}
