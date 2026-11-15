package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

@Entity
@Table(name = "alert_settings", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertSettings {

  @Id private Short id;

  @Column(nullable = false, length = 500)
  private String recipients;

  @Column(name = "smtp_host", nullable = false, length = 255)
  private String smtpHost;

  @Column(name = "smtp_port", nullable = false)
  private int smtpPort;

  @Column(name = "smtp_from", nullable = false, length = 255)
  private String smtpFrom;

  @Column(name = "auth_enabled", nullable = false)
  private boolean authEnabled;

  @Column(name = "smtp_user", length = 255)
  private String smtpUser;

  @Column(name = "password_set", nullable = false)
  private boolean passwordSet;

  @Column(name = "require_tls", nullable = false)
  private boolean requireTls;

  @Column(nullable = false)
  private boolean configured;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
