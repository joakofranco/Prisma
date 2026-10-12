package uy.edu.prisma.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
   * Organizaciones a las que este usuario accede (M:N): única fuente de verdad, reemplaza al viejo
   * tenant único y a la lista de organizaciones a auditar. Cuántas corresponden según el rol lo
   * valida UserService.validateOrganizationsForRoles: ORG_RESPONSIBLE / VIEWER exactamente una,
   * AUDITOR al menos una, INTERNAL_EVALUATOR cualquier cantidad (SIN ninguna es un evaluador
   * "general" que accede a todas), PRISMA_ADMIN ninguna (es global).
   */
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "user_organizations",
      schema = "prisma",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "organization_id"))
  @Builder.Default
  private Set<Organization> organizations = new HashSet<>();

  @Column(name = "created_at", nullable = false, updatable = false)
  @Builder.Default
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private OffsetDateTime updatedAt = OffsetDateTime.now();

  /** Ids de las organizaciones del usuario. */
  public Set<UUID> organizationIds() {
    return organizations.stream().map(Organization::getId).collect(Collectors.toSet());
  }

  /**
   * La organización del usuario si tiene EXACTAMENTE una (el caso de ORG_RESPONSIBLE / VIEWER y de
   * un evaluador de una sola organización); {@code null} si no tiene ninguna o tiene varias.
   */
  public UUID singleOrganizationId() {
    return organizations.size() == 1 ? organizations.iterator().next().getId() : null;
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
