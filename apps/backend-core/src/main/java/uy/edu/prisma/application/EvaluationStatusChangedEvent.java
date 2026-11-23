package uy.edu.prisma.application;

import java.util.UUID;
import uy.edu.prisma.domain.entity.Evaluation;

/** La evaluación cambió de estado (publicado por {@link EvaluationService#updateStatus}). */
public record EvaluationStatusChangedEvent(
    UUID evaluationId, UUID organizationId, Evaluation.Status from, Evaluation.Status to) {}
