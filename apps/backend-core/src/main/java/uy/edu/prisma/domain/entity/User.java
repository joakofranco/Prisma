package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "users", schema = "prisma")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true, length = 255)
  private String email;

  @Column(name = "first_name", nullable = false, length = 100)
  private String firstName;

  @Column(name = "last_name", nullable = false, length = 100)
  private String lastName;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  /**
   * Id del usuario en Keycloak. {@code null} = todavía no provisionado ahí (no puede loguearse).
   */
  @Column(name = "keycloak_id")
  private UUID keycloakId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tenant_id")
  private Organization tenant;

  @Column(nullable = false)
  @Builder.Default
  private Boolean enabled = true;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "user_roles",
      schema = "prisma",
      joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "role")
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Set<UserRole> roles = new HashSet<>();

  /**
   * Organizaciones que este usuario puede auditar -- solo tiene efecto si {@code roles} contiene
   * AUDITOR (ver CurrentUserService.assertOrganizationAccess); UserService exige al menos una acá
   * cuando se asigna ese rol. Distinto de {@code tenant}: un auditor no "pertenece" a una única
   * organización como ORG_RESPONSIBLE/INTERNAL_EVALUATOR/VIEWER, puede auditar varias a la vez.
   */
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_audited_organizations",
      schema = "prisma",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "organization_id"))
  @Builder.Default
  private Set<Organization> auditedOrganizations = new HashSet<>();

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
