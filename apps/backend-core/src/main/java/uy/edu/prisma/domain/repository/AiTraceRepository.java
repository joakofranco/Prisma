package uy.edu.prisma.domain.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.AiTrace;

public interface AiTraceRepository extends JpaRepository<AiTrace, UUID> {

  List<AiTrace> findAllByOrderByCreatedAtAsc();

  @Modifying
  @Query("DELETE FROM AiTrace t WHERE t.createdAt < :before")
  int deleteOlderThan(@Param("before") OffsetDateTime before);
}
