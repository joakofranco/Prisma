package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "catalog_controls", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CatalogControl {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "requirement_id", nullable = false)
  private CatalogRequirement requirement;

  @Column(nullable = false, length = 30)
  private String code;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String description;

  /**
   * Nivel (1-4) al que pertenece este control dentro de su Requisito -- p.ej. "PL.1-1" es nivel 1,
   * "PL.1-9" es nivel 4. NO es una meta individual de este control: el nivel de madurez alcanzado
   * por una Subcategoría se calcula de forma acumulativa en EvaluationService.calculateMaturity
   * (nivel N alcanzado <=> TODOS los controles de nivel <= N, entre todos los Requisitos de esa
   * Subcategoría, están marcados como cumplidos).
   */
  @Column(name = "target_level", nullable = false)
  private Integer targetLevel;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private Integer sortOrder = 0;

  /**
   * Subcategorías donde este Control fue UBICADO explícitamente (mapeo curado de Agesic, ver
   * V18__control_subcategory_mapping.sql). Vacío para catálogos que no lo traen (5.0 / 5.1). Usar
   * {@link #effectiveSubcategories()}, no este campo directo.
   */
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "catalog_control_subcategories",
      schema = "prisma",
      joinColumns = @JoinColumn(name = "control_id"),
      inverseJoinColumns = @JoinColumn(name = "subcategory_id"))
  @Builder.Default
  private Set<CatalogSubcategory> subcategories = new HashSet<>();

  /**
   * Subcategorías en las que este Control cuenta para la madurez: el mapeo curado control↔
   * subcategoría si el catálogo lo trae; si no, el mapeo Requisito→Subcategoría (comportamiento
   * previo a V18, para catálogos 5.0 / 5.1).
   */
  public Collection<CatalogSubcategory> effectiveSubcategories() {
    return subcategories.isEmpty() ? requirement.getSubcategories() : subcategories;
  }
}
