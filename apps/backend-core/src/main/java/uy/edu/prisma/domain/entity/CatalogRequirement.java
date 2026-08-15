package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "catalog_requirements", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogRequirement {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // Reemplaza la vieja FK directa a una única CatalogSubcategory: un Requisito puede pertenecer a
  // varias Subcategorías (ver CatalogRequirementSubcategory). El código de Requisito es único por
  // versión de catálogo (antes era único por subcategoría), de ahí esta FK directa a la versión.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "version_id", nullable = false)
  private CatalogVersion version;

  @Column(nullable = false, length = 30)
  private String code;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String description;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private Integer sortOrder = 0;

  @OneToMany(
      mappedBy = "requirement",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("sortOrder ASC")
  @Builder.Default
  private List<CatalogControl> controls = new ArrayList<>();

  @OneToMany(
      mappedBy = "requirement",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("sortOrder ASC")
  @Builder.Default
  private List<CatalogRequirementSubcategory> subcategoryLinks = new ArrayList<>();

  /** Conveniencia para no romper los call sites que antes hacían {@code getSubcategory()}. */
  public List<CatalogSubcategory> getSubcategories() {
    return subcategoryLinks.stream().map(CatalogRequirementSubcategory::getSubcategory).toList();
  }
}
