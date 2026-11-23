-- Hito 5 de la IA (WP7 / C8): modo investigacion y trazas de razonamiento.

-- 1) Modo investigacion por organizacion. Lo enciende solo PRISMA_ADMIN; apagado (por defecto) no
-- se registra ninguna traza (D2).
ALTER TABLE prisma.organizations
    ADD COLUMN research_mode BOOLEAN NOT NULL DEFAULT FALSE;

-- 2) Traza de una llamada a la IA. A proposito SIN organizacion ni usuario (como los datasets
-- anonimos de feedback): lo que dice a quien pertenece es ai_trace_link. Para tareas con evidencia
-- (citas, clasificacion) solo se guardan ids y puntajes en retrieval, nunca texto del archivo (D15).
CREATE TABLE prisma.ai_trace (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('GUIDE', 'CITATIONS', 'CLASSIFY')),
    seed        BIGINT,
    config      JSONB NOT NULL DEFAULT '{}'::jsonb,
    prompt      TEXT,
    response    TEXT,
    thinking    TEXT,
    retrieval   JSONB NOT NULL DEFAULT '[]'::jsonb,
    metrics     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_trace_created ON prisma.ai_trace(created_at);

-- 3) A que recurso corresponde cada traza. La organizacion se guarda solo para controlar el acceso
-- al consultarla; la exportacion JSONL no toca esta tabla.
CREATE TABLE prisma.ai_trace_link (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id        UUID NOT NULL REFERENCES prisma.ai_trace(id) ON DELETE CASCADE,
    resource_type   VARCHAR(30) NOT NULL
                    CHECK (resource_type IN ('IMPROVEMENT_PLAN', 'CITATION', 'OBSERVATION', 'CLASSIFICATION')),
    resource_id     UUID NOT NULL,
    organization_id UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (trace_id, resource_type, resource_id)
);
CREATE INDEX idx_ai_trace_link_resource ON prisma.ai_trace_link(resource_type, resource_id);

-- 4) La guia recuerda su traza hasta que se usa en una accion (ahi se enlaza al plan).
ALTER TABLE prisma.ai_generated_guide ADD COLUMN trace_id UUID REFERENCES prisma.ai_trace(id) ON DELETE SET NULL;
