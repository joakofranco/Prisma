-- Hito 2 de la IA (WP4 / F0): aceptar una cita valida el control (C4a, version reducida) y
-- «Registrar como observacion» deja feedback de la diferencia (C4b).

-- Origen del dictamen: MANUAL (lo fijo el auditor con el selector) o CITATION (se acepto una cita
-- de la IA). source_ref guarda la cita que lo respalda (evidencia, archivo, ubicacion, puntaje).
ALTER TABLE prisma.audit_control_verdicts
    ADD COLUMN source     VARCHAR(10) NOT NULL DEFAULT 'MANUAL'
                          CHECK (source IN ('MANUAL', 'CITATION')),
    ADD COLUMN source_ref JSONB,
    ADD CONSTRAINT chk_verdict_source_ref CHECK ((source = 'CITATION') = (source_ref IS NOT NULL));

-- Feedback ANONIMO (patron V32/V33): que parte del texto precargado desde la cita conserva el
-- auditor, que tipo de hallazgo elige y cuando reescribe. Sin organizacion, evaluacion ni usuario.
-- Se escribe SOLO al guardar la observacion creada desde una cita.
CREATE TABLE prisma.ai_citation_validation_feedback (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id   UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    file_name    VARCHAR(255),
    location     VARCHAR(255),
    snippet      TEXT NOT NULL,
    score        DOUBLE PRECISION,
    prefill_text TEXT NOT NULL,
    final_text   TEXT NOT NULL,
    final_type   VARCHAR(20) NOT NULL
                 CHECK (final_type IN ('OBSERVATION', 'NON_CONFORMITY', 'RECOMMENDATION')),
    outcome      VARCHAR(10) NOT NULL CHECK (outcome IN ('UNEDITED', 'EDITED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_citation_validation_control ON prisma.ai_citation_validation_feedback(control_id);
