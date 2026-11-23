-- Hito 4 de la IA (WP6 / F3-F4): lote de guias y evidencia global.

-- 1) Trabajos: lote (batch_id agrupa los GUIDE de un mismo pedido, con prioridad de lote) y la
-- clasificacion de evidencia global.
ALTER TABLE prisma.ai_job DROP CONSTRAINT ai_job_type_check;
ALTER TABLE prisma.ai_job ADD CONSTRAINT ai_job_type_check
    CHECK (type IN ('GUIDE', 'EVIDENCE_INDEX', 'CITATIONS_PRECOMPUTE', 'EVIDENCE_CLASSIFY'));
ALTER TABLE prisma.ai_job ADD COLUMN batch_id UUID;
CREATE INDEX idx_ai_job_batch ON prisma.ai_job(batch_id) WHERE batch_id IS NOT NULL;

-- 2) Citas: las que salen de evidencia SIN control asignado (global) se marcan.
ALTER TABLE prisma.evidence_citation_result
    ADD COLUMN unassigned BOOLEAN NOT NULL DEFAULT FALSE;

-- 3) Preclasificacion de evidencia global (C1 / D7): sugerencias de control por evidencia, con su
-- puntaje. Son SOLO sugerencias: aceptar una no asigna la evidencia a ese control.
CREATE TABLE prisma.evidence_control_suggestion (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    evidence_id   UUID NOT NULL REFERENCES prisma.evidence(id) ON DELETE CASCADE,
    control_id    UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    score         DOUBLE PRECISION NOT NULL,
    rank          INT NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    decided_by    UUID REFERENCES prisma.users(id) ON DELETE SET NULL,
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evidence_id, control_id)
);
CREATE INDEX idx_evidence_suggestion_eval ON prisma.evidence_control_suggestion(evaluation_id);

-- 4) Feedback ANONIMO (patron V32/V33) de la preclasificacion: control, puntaje, posicion y decision.
-- Sin organizacion, evaluacion, usuario, archivo ni texto (D15: solo ids y puntajes).
CREATE TABLE prisma.ai_evidence_classification_feedback (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    score      DOUBLE PRECISION NOT NULL,
    rank       INT NOT NULL,
    decision   VARCHAR(10) NOT NULL CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_classification_feedback_control
    ON prisma.ai_evidence_classification_feedback(control_id);
