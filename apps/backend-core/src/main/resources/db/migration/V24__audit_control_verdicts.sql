-- Dictamen del auditor por control dentro de una evaluacion (RF-AUD-06, PRI-296).
-- Convive con audit_observations: las observaciones siguen siendo el registro de hallazgos
-- (tipo + ciclo de vida); el dictamen es el resultado de la revision de cada control.
-- Un control tiene a lo sumo un dictamen por evaluacion y puede tener varias observaciones.
CREATE TABLE prisma.audit_control_verdicts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id    UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    verdict       VARCHAR(30) NOT NULL
        CHECK (verdict IN ('VALIDATED', 'NOT_VALIDATED', 'NEEDS_MORE_EVIDENCE')),
    set_by        UUID REFERENCES prisma.users(id),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, control_id)
);

CREATE INDEX idx_audit_verdicts_eval ON prisma.audit_control_verdicts(evaluation_id);
