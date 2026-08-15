package uy.edu.prisma.domain.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.CatalogVersion;

public interface CatalogVersionRepository extends JpaRepository<CatalogVersion, UUID> {
  Optional<CatalogVersion> findByVersion(String version);
}
