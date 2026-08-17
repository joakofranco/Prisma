package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.*;

/**
 * Subconjunto curado de controles del catálogo (p.ej. "Gobierno", "PYME"), para que una evaluación
 * se acote a los controles relevantes de un sector en vez del catálogo completo.
 */
@Entity
@Table(name = "community_profiles", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunityProfile {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 150)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "catalog_version", nullable = false, length = 20)
  private String catalogVersion;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "community_profile_controls",
      schema = "prisma",
      joinColumns = @JoinColumn(name = "profile_id"),
      inverseJoinColumns = @JoinColumn(name = "control_id"))
  @Builder.Default
  private Set<CatalogControl> controls = new HashSet<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
