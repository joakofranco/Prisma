package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AiTraceLink;
import uy.edu.prisma.domain.entity.AiTraceLink.ResourceType;

public interface AiTraceLinkRepository extends JpaRepository<AiTraceLink, UUID> {

  List<AiTraceLink> findByTraceId(UUID traceId);

  List<AiTraceLink> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(
      ResourceType resourceType, UUID resourceId);

  boolean existsByTraceIdAndResourceTypeAndResourceId(
      UUID traceId, ResourceType resourceType, UUID resourceId);
}
