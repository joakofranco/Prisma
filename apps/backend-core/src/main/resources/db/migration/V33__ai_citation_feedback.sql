-- Retroalimentacion del auditor sobre las citas que propone la busqueda asistida (PRI-359).
-- Cada fila es una decision del auditor (aceptar o rechazar) sobre un fragmento citado por la IA,
-- para medir si la busqueda trae fragmentos utiles y armar luego un dataset de fine-tuning.
-- Es solo historial (si cambia de opinion se agrega otra fila; vale la mas reciente).
-- Referencia al CONTROL del catalogo, a proposito sin evaluacion, organizacion ni usuario: el
-- dataset no debe poder rastrearse hasta una organizacion concreta.
CREATE TABLE prisma.ai_citation_feedback (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    file_name  VARCHAR(255),
    location   VARCHAR(255),
    snippet    TEXT NOT NULL,
    score      DOUBLE PRECISION,
    decision   VARCHAR(10) NOT NULL CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_citation_feedback_control ON prisma.ai_citation_feedback(control_id);
