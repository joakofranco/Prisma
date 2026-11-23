package uy.edu.prisma.domain.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import uy.edu.prisma.domain.entity.AiEvidenceClassificationFeedback;

public interface AiEvidenceClassificationFeedbackRepository
    extends JpaRepository<AiEvidenceClassificationFeedback, UUID> {}
