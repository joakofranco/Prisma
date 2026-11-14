package uy.edu.prisma.domain.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.PlatformService;

public interface PlatformServiceRepository extends JpaRepository<PlatformService, UUID> {

  List<PlatformService> findAllByOrderBySortOrderAsc();
}
