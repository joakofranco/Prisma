package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "catalog_subcategories", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogSubcategory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id", nullable = false)
  private CatalogCategory category;

  @Column(nullable = false, length = 20)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private Integer sortOrder = 0;

  // Reemplaza la vieja relación directa a CatalogRequirement: un Requisito puede estar enlazado
  // a varias Subcategorías, así que el vínculo pasa por CatalogRequirementSubcategory.
  @OneToMany(
      mappedBy = "subcategory",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = FetchType.LAZY)
  @OrderBy("sortOrder ASC")
  @Builder.Default
  private List<CatalogRequirementSubcategory> requirementLinks = new ArrayList<>();

  /** Conveniencia para no romper los call sites que antes iteraban {@code requirements}. */
  public List<CatalogRequirement> getRequirements() {
    return requirementLinks.stream().map(CatalogRequirementSubcategory::getRequirement).toList();
  }
}
