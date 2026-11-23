package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Trabajo de la cola de IA. El resultado final no vive acá sino en la tabla de la función (hoy
 * {@link AiGeneratedGuide}, referenciada por {@code resultRef}). Ver V41.
 */
@Entity
@Table(name = "ai_job", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiJob {

  public enum Type {
    GUIDE,
    /** Indexar una evidencia en backend-ai (C7). Trabajo del sistema: sin usuario solicitante. */
    EVIDENCE_INDEX,
    /** Buscar y guardar las citas de los controles con evidencia al pasar a auditoría (C2). */
    CITATIONS_PRECOMPUTE
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED;

    /** QUEUED o RUNNING: el trabajo todavía puede producir un resultado. */
    public boolean isActive() {
      return this == QUEUED || this == RUNNING;
    }
  }

  /** El interactivo (alguien esperando en pantalla) pasa antes que el lote. */
  public static final int PRIORITY_INTERACTIVE = 10;

  public static final int PRIORITY_BATCH = 0;

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private Type type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private Status status = Status.QUEUED;

  @Column(name = "organization_id")
  private UUID organizationId;

  @Column(name = "requested_by")
  private UUID requestedBy;

  @Column(nullable = false)
  @Builder.Default
  private int priority = PRIORITY_INTERACTIVE;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  @Builder.Default
  private Map<String, Object> params = new HashMap<>();

  @Column(name = "dedup_key", nullable = false, length = 64)
  private String dedupKey;

  @Column(name = "result_ref")
  private UUID resultRef;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "max_attempts", nullable = false)
  @Builder.Default
  private int maxAttempts = 2;

  @Column(name = "next_attempt_at", nullable = false)
  @Builder.Default
  private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

  @Column(name = "locked_until")
  private OffsetDateTime lockedUntil;

  @Column(name = "error_code", length = 40)
  private String errorCode;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "queue_wait_ms")
  private Long queueWaitMs;

  @Column(name = "run_ms")
  private Long runMs;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> metrics;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "started_at")
  private OffsetDateTime startedAt;

  @Column(name = "finished_at")
  private OffsetDateTime finishedAt;

  @Column(name = "expires_at")
  private OffsetDateTime expiresAt;
}
