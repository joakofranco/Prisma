package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

/**
 * Asociación explícita Requisito &lt;-&gt; Subcategoría: un mismo Requisito (con sus Controles)
 * puede pertenecer a varias Subcategorías (p.ej. "GR.1" referenciado desde "GV.RM-07", "GV.RM-06",
 * "GV.RM-03", "GV.SC-01", etc.) -- ver CatalogService.importCatalog para cómo se arma esta
 * asociación al importar sin duplicar el Requisito ni sus Controles, y EvaluationService.
 * calculateMaturity para cómo un mismo Control aporta al cálculo de nivel de cada Subcategoría a
 * la que llega por esta tabla.
 */
@Entity
@Table(name = "catalog_requirement_subcategories", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogRequirementSubcategory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // cascade PERSIST/MERGE: a diferencia de subcategory (que ya llega persistida por el camino
  // version->funciones->categorias->subcategorias, cascade=ALL), el Requisito ya no cuelga de
  // ningun coleccion cascade=ALL del arbol principal -- este es el unico camino por el que
  // CatalogService.importCatalog logra persistirlo (y, en cascada desde el, sus controles) al
  // guardar unicamente la CatalogVersion raiz.
  @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
  @JoinColumn(name = "requirement_id", nullable = false)
  private CatalogRequirement requirement;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "subcategory_id", nullable = false)
  private CatalogSubcategory subcategory;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private Integer sortOrder = 0;
}
