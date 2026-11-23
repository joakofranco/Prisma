package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Traza del modo investigación (C8). No lleva organización ni usuario a propósito: a quién
 * pertenece lo dice {@link AiTraceLink}. En tareas con evidencia {@code retrieval} guarda solo ids
 * y puntajes, nunca texto del archivo (D15). Ver V45.
 */
@Entity
@Table(name = "ai_trace", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiTrace {

  public enum Kind {
    GUIDE,
    CITATIONS,
    CLASSIFY
  }

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Kind kind;

  private Long seed;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private Map<String, Object> config = new HashMap<>();

  @Column(columnDefinition = "text")
  private String prompt;

  @Column(columnDefinition = "text")
  private String response;

  @Column(columnDefinition = "text")
  private String thinking;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private List<Map<String, Object>> retrieval = new ArrayList<>();

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private Map<String, Object> metrics = new HashMap<>();

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();
}
