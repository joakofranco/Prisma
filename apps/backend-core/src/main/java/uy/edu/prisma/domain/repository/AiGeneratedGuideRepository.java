package uy.edu.prisma.domain.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.edu.prisma.domain.entity.AiGeneratedGuide;

public interface AiGeneratedGuideRepository extends JpaRepository<AiGeneratedGuide, UUID> {

  /** Guías cuyo plazo de 30 días venció: las SHOWN pasan a REJECTED, las GENERATED solo expiran. */
  @Query(
      "SELECT g FROM AiGeneratedGuide g JOIN FETCH g.control WHERE g.status IN"
          + " (uy.edu.prisma.domain.entity.AiGeneratedGuide.Status.GENERATED,"
          + " uy.edu.prisma.domain.entity.AiGeneratedGuide.Status.SHOWN)"
          + " AND g.createdAt < :before")
  List<AiGeneratedGuide> findOpenOlderThan(@Param("before") OffsetDateTime before);
}
