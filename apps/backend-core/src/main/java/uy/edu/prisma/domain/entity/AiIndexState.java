package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/** Estado clave/valor del índice de IA (hoy: el modelo de embedding con que se indexó). V47. */
@Entity
@Table(name = "ai_index_state", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiIndexState {

  public static final String EMBEDDING_MODEL = "embedding_model";

  @Id
  @Column(name = "key", length = 50)
  private String key;

  @Column(nullable = false, length = 200)
  private String value;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
