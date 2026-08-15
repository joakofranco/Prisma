package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
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
}
