package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Enlaza una traza ({@link AiTrace}) con el recurso al que dio origen. Ver V45. */
@Entity
@Table(name = "ai_trace_link", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTraceLink {

  public enum ResourceType {
    IMPROVEMENT_PLAN,
    CITATION,
    OBSERVATION,
    CLASSIFICATION
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "trace_id", nullable = false)
  private UUID traceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_type", nullable = false, length = 30)
  private ResourceType resourceType;

  @Column(name = "resource_id", nullable = false)
  private UUID resourceId;

  /** Solo para controlar el acceso al consultar la traza. */
  @Column(name = "organization_id")
  private UUID organizationId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
