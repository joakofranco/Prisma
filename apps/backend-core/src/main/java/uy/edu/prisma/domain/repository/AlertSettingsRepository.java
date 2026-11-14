package uy.edu.prisma.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AlertSettings;

public interface AlertSettingsRepository extends JpaRepository<AlertSettings, Short> {}
