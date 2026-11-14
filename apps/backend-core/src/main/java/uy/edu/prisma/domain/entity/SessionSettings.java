package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/** Configuración global del cierre de sesión por inactividad (fila única, id = 1). */
@Entity
@Table(name = "session_settings", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionSettings {

  public static final short SINGLETON_ID = 1;

  @Id @Builder.Default private Short id = SINGLETON_ID;

  @Column(name = "idle_timeout_minutes", nullable = false)
  private int idleTimeoutMinutes;

  @Column(name = "warning_seconds", nullable = false)
  private int warningSeconds;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
