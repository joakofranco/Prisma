-- Marca "para revisar" por control dentro de una evaluacion (PRI-352).
-- La marca es independiente de la respuesta: un control puede estar marcado sin haberse
-- respondido todavia, asi que no vive como columna de evaluation_responses. Que exista la fila
-- significa "marcado"; desmarcar la borra. La ven todos los roles que acceden a la evaluacion
-- (evaluadores y auditor), no solo quien la puso.
CREATE TABLE prisma.evaluation_control_flags (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id    UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    flagged_by    UUID REFERENCES prisma.users(id),
    flagged_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, control_id)
);

CREATE INDEX idx_control_flags_eval ON prisma.evaluation_control_flags(evaluation_id);
