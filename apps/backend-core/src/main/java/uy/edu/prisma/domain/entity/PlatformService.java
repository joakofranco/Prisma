package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Servicio de la infraestructura que figura en el lanzador «Servicios» (URL editable). */
@Entity
@Table(name = "platform_services", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformService {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "service_key", nullable = false, unique = true, length = 40)
  private String serviceKey;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(nullable = false, length = 200)
  private String description;

  @Column(nullable = false, length = 30)
  private String category;

  @Column(nullable = false, length = 500)
  private String url;

  @Column(name = "default_url", nullable = false, length = 500)
  private String defaultUrl;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();
}
