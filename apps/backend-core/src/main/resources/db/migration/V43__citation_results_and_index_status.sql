-- Hito 3 de la IA (WP5 / F2): citas persistidas (C2) e indexacion con estado y reintentos (C7).

-- 1) Tipos de trabajo nuevos en la cola (V41): indexar evidencia y precalcular citas.
ALTER TABLE prisma.ai_job DROP CONSTRAINT ai_job_type_check;
ALTER TABLE prisma.ai_job ADD CONSTRAINT ai_job_type_check
    CHECK (type IN ('GUIDE', 'EVIDENCE_INDEX', 'CITATIONS_PRECOMPUTE'));

-- 2) Estado de indexacion por evidencia: QUEUED, INDEXING, INDEXED, ERROR, NOT_INDEXABLE.
-- ai_indexed se mantiene (lo lee el frontend actual): es true solo con INDEXED.
ALTER TABLE prisma.evidence
    ADD COLUMN index_status VARCHAR(20) NOT NULL DEFAULT 'NOT_INDEXABLE'
        CHECK (index_status IN ('QUEUED', 'INDEXING', 'INDEXED', 'ERROR', 'NOT_INDEXABLE')),
    ADD COLUMN index_error  TEXT,
    ADD COLUMN indexed_at   TIMESTAMPTZ;

-- Evidencias que ya existian: indexadas -> INDEXED; formatos que backend-ai lee y que no quedaron
-- indexados (y no estan en cuarentena ni infectados) -> ERROR para que se puedan reintentar; el
-- resto (ZIP, imagenes, cuarentena, infectadas) -> NOT_INDEXABLE.
UPDATE prisma.evidence SET index_status = 'INDEXED', indexed_at = uploaded_at WHERE ai_indexed;
UPDATE prisma.evidence
   SET index_status = 'ERROR',
       index_error = 'No se pudo indexar antes de esta version: usa Reintentar.'
 WHERE NOT ai_indexed
   AND scan_status = 'CLEAN'
   AND file_type IN ('application/pdf',
                     'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                     'text/plain', 'text/markdown');
UPDATE prisma.evidence
   SET index_error = 'En cuarentena: se indexa cuando el analisis la libere.'
 WHERE index_status = 'NOT_INDEXABLE' AND scan_status = 'QUARANTINED';

-- 3) Citas guardadas (C2). A diferencia de ai_citation_feedback (anonima a proposito), esta tabla
-- SI esta ligada a la evaluacion: es el estado de trabajo del auditor. El id del fragmento lo
-- devuelve backend-ai y es estable entre reindexados (evidencia + posicion).
CREATE TABLE prisma.evidence_citation_result (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id    UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    evidence_id   UUID REFERENCES prisma.evidence(id) ON DELETE CASCADE,
    fragment_id   VARCHAR(200),
    file_name     VARCHAR(255),
    location      VARCHAR(255),
    snippet       TEXT NOT NULL,
    score         DOUBLE PRECISION,
    position      INT NOT NULL,
    -- VALIDATED queda reservado (el doc 02 lo nombra, pero su significado exacto es POR VERIFICAR).
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'VALIDATED')),
    -- La evidencia del control cambio: en el proximo GET se vuelve a buscar y se conservan las
    -- decisiones de los fragmentos que siguen apareciendo.
    stale         BOOLEAN NOT NULL DEFAULT FALSE,
    decided_by    UUID REFERENCES prisma.users(id) ON DELETE SET NULL,
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_citation_result_fragment
    ON prisma.evidence_citation_result(evaluation_id, control_id, fragment_id)
    WHERE fragment_id IS NOT NULL;
CREATE INDEX idx_citation_result_control
    ON prisma.evidence_citation_result(evaluation_id, control_id, position);
