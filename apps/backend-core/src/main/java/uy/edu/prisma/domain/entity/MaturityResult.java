package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "maturity_results", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaturityResult {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "evaluation_id", nullable = false)
  private Evaluation evaluation;

  @Column(name = "function_id", nullable = false)
  private UUID functionId;

  @Column(name = "function_name", nullable = false, length = 200)
  private String functionName;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "category_name", nullable = false, length = 200)
  private String categoryName;

  @Column(name = "subcategory_id", nullable = false)
  private UUID subcategoryId;

  @Column(name = "subcategory_name", nullable = false, length = 200)
  private String subcategoryName;

  @Column(name = "current_level", nullable = false)
  private Integer currentLevel;

  @Column(name = "target_level", nullable = false)
  private Integer targetLevel;

  @Column(nullable = false)
  @Builder.Default
  private Integer gap = 0;
}
