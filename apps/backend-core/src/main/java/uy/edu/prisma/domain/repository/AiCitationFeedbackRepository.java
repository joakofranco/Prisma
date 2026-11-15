package uy.edu.prisma.domain.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AiCitationFeedback;

public interface AiCitationFeedbackRepository extends JpaRepository<AiCitationFeedback, UUID> {}
