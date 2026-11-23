-- Cola de trabajos de IA y guias persistidas (hito 1 del plan IA asincrona, WP2).
--
-- ai_job: un trabajo por pedido de generacion. El resultado final NO vive aca sino en la tabla de la
-- funcion (hoy ai_generated_guide, referenciada por result_ref). Un trabajo se reclama con
-- SELECT ... FOR UPDATE SKIP LOCKED, asi que es seguro con mas de una replica de backend-core.
--   QUEUED -> RUNNING -> SUCCEEDED | FAILED ; QUEUED -> CANCELLED ; SUCCEEDED -> EXPIRED
CREATE TABLE prisma.ai_job (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(30) NOT NULL CHECK (type IN ('GUIDE')),
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                    CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    organization_id UUID REFERENCES prisma.organizations(id) ON DELETE CASCADE,
    requested_by    UUID,
    -- 10 = interactivo, 0 = lote: el interactivo pasa primero.
    priority        INT  NOT NULL DEFAULT 10,
    params          JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- Idempotencia: mismo tipo + mismos parametros (+ organizacion) mientras el trabajo siga activo.
    dedup_key       VARCHAR(64) NOT NULL,
    result_ref      UUID,
    attempts        INT  NOT NULL DEFAULT 0,
    max_attempts    INT  NOT NULL DEFAULT 2,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Mientras corre: hasta cuando es valido el reclamo. Si vence (la replica murio) se reencola.
    locked_until    TIMESTAMPTZ,
    error_code      VARCHAR(40),
    error_message   TEXT,
    -- Medicion (WP3): espera en cola, ejecucion y tiempos/tokens de Ollama.
    queue_wait_ms   BIGINT,
    run_ms          BIGINT,
    metrics         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ
);

-- Un solo trabajo activo por pedido: la restriccion es la que evita duplicados bajo concurrencia.
CREATE UNIQUE INDEX uq_ai_job_active_dedup
    ON prisma.ai_job(dedup_key) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_ai_job_claim ON prisma.ai_job(status, next_attempt_at, priority DESC, created_at);
CREATE INDEX idx_ai_job_org_status ON prisma.ai_job(organization_id, status);
CREATE INDEX idx_ai_job_requested_by ON prisma.ai_job(requested_by, created_at DESC);

-- Guia de mejora generada (C5). Guarda lo que la IA devolvio para poder registrar el feedback en el
-- servidor: GENERATED (aun no vista) -> SHOWN (la vio quien la pidio) -> USED (se creo la accion
-- con ella) | EXPIRED (a los 30 dias; las SHOWN sin usar cuentan como REJECTED en
-- ai_suggestion_feedback, las nunca mostradas no cuentan). DISCARDED queda reservado.
-- organization_id solo sirve para autorizar el acceso; ai_suggestion_feedback sigue sin organizacion
-- ni usuario (V32).
CREATE TABLE prisma.ai_generated_guide (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id         UUID REFERENCES prisma.ai_job(id) ON DELETE SET NULL,
    organization_id UUID REFERENCES prisma.organizations(id) ON DELETE CASCADE,
    requested_by   UUID,
    control_id     UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    current_level  INT  NOT NULL,
    target_level   INT  NOT NULL,
    model          VARCHAR(100),
    prompt_version VARCHAR(20) NOT NULL DEFAULT 'v1',
    summary        TEXT NOT NULL DEFAULT '',
    tips           JSONB NOT NULL DEFAULT '[]'::jsonb,
    status         VARCHAR(20) NOT NULL DEFAULT 'GENERATED'
                   CHECK (status IN ('GENERATED', 'SHOWN', 'USED', 'DISCARDED', 'EXPIRED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    shown_at       TIMESTAMPTZ,
    used_at        TIMESTAMPTZ
);

CREATE INDEX idx_ai_guide_status ON prisma.ai_generated_guide(status, shown_at);
CREATE INDEX idx_ai_guide_org ON prisma.ai_generated_guide(organization_id);
