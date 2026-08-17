package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.CommunityProfile;

public interface CommunityProfileRepository extends JpaRepository<CommunityProfile, UUID> {

  List<CommunityProfile> findByCatalogVersionOrderByNameAsc(String catalogVersion);
}
