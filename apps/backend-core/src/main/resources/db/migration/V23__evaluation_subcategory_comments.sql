-- Comentario del evaluador por subcategoria dentro de una evaluacion (RF-EVA-06, PRI-265).
-- Convive con evaluation_responses.observations, que es el comentario por control.
CREATE TABLE prisma.evaluation_subcategory_comments (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id  UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    subcategory_id UUID NOT NULL REFERENCES prisma.catalog_subcategories(id),
    comment        TEXT NOT NULL,
    updated_by     UUID REFERENCES prisma.users(id),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (evaluation_id, subcategory_id)
);

CREATE INDEX idx_subcat_comments_eval ON prisma.evaluation_subcategory_comments(evaluation_id);
