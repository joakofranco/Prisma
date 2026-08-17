package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "evidence", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Evidence {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "control_id")
  private CatalogControl control;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "file_size", nullable = false)
  @Builder.Default
  private Long fileSize = 0L;

  @Column(name = "file_type", length = 100)
  private String fileType;

  @Column(name = "storage_key", nullable = false, length = 500)
  private String storageKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "uploaded_by")
  private User uploadedBy;

  @Column(name = "uploaded_at", nullable = false, updatable = false)
  @Builder.Default
  private OffsetDateTime uploadedAt = OffsetDateTime.now();

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "ai_indexed", nullable = false)
  @Builder.Default
  private boolean aiIndexed = false;
}
