-- Retroalimentacion sobre las guias de mejora generadas con IA (PRI-370).
-- Cada fila es un par (sugerencia original, destino) para armar luego un dataset de fine-tuning:
--   ACCEPTED_UNEDITED: se uso tal cual en la accion.
--   ACCEPTED_EDITED:   se uso y la persona la modifico; edited_text guarda el texto final.
--   REJECTED:          se genero pero no se uso (no se uso, se regenero, se deselecciono o se cerro).
-- Las sugerencias que no llegaron a generarse (fallo de la IA) no se registran.
-- Referencia al CONTROL del catalogo, a proposito sin evaluacion, organizacion ni usuario: el
-- dataset no debe poder rastrearse hasta una organizacion concreta.
CREATE TABLE prisma.ai_suggestion_feedback (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id       UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    current_level    INT  NOT NULL,
    target_level     INT  NOT NULL,
    original_summary TEXT NOT NULL DEFAULT '',
    original_tips    JSONB NOT NULL DEFAULT '[]'::jsonb,
    outcome          VARCHAR(20) NOT NULL
                     CHECK (outcome IN ('ACCEPTED_UNEDITED', 'ACCEPTED_EDITED', 'REJECTED')),
    edited_text      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((outcome = 'ACCEPTED_EDITED') = (edited_text IS NOT NULL))
);

CREATE INDEX idx_ai_feedback_control ON prisma.ai_suggestion_feedback(control_id);
