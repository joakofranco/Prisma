package uy.edu.prisma.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.SessionSettings;

public interface SessionSettingsRepository extends JpaRepository<SessionSettings, Short> {}
