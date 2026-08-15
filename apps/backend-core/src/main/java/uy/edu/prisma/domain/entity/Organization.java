package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "organizations", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 200)
  private String name;

  // Opcional (no toda organización tiene uno cargado al momento del alta) -- único sólo entre los
  // valores no nulos, Postgres no cuenta NULL contra una UNIQUE constraint, así que varias
  // organizaciones sin RUT conviven sin problema. Ver V17__organization_rut_optional.sql.
  @Column(unique = true, length = 20)
  private String rut;

  @Column(length = 100)
  private String sector;

  @Column(length = 50)
  private String size;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "responsible_id")
  private User responsible;

  @Column(nullable = false)
  @Builder.Default
  private Boolean enabled = true;

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
