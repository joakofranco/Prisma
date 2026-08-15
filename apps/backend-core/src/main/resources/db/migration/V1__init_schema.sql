-- =====================================================================
-- V1__init_schema.sql — Schema inicial de PRISMA (Incremento 1)
-- =====================================================================

-- Organizations (multi-tenant root)
CREATE TABLE prisma.organizations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(200) NOT NULL,
    nit           VARCHAR(20) UNIQUE NOT NULL,
    sector        VARCHAR(100),
    size          VARCHAR(50),
    responsible_id UUID,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Users
CREATE TABLE prisma.users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) UNIQUE NOT NULL,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    tenant_id     UUID REFERENCES prisma.organizations(id),
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- User roles (multiple per user)
CREATE TABLE prisma.user_roles (
    user_id UUID NOT NULL REFERENCES prisma.users(id) ON DELETE CASCADE,
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Add FK for organization responsible
ALTER TABLE prisma.organizations
    ADD CONSTRAINT fk_org_responsible
    FOREIGN KEY (responsible_id) REFERENCES prisma.users(id);

-- Catalog versions
CREATE TABLE prisma.catalog_versions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version    VARCHAR(20) UNIQUE NOT NULL,
    label      VARCHAR(200) NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- MCU5.0 functions
CREATE TABLE prisma.catalog_functions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id  UUID NOT NULL REFERENCES prisma.catalog_versions(id) ON DELETE CASCADE,
    code        VARCHAR(10) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order  INT NOT NULL DEFAULT 0,
    UNIQUE(version_id, code)
);

-- MCU5.0 categories
CREATE TABLE prisma.catalog_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    function_id UUID NOT NULL REFERENCES prisma.catalog_functions(id) ON DELETE CASCADE,
    code        VARCHAR(10) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order  INT NOT NULL DEFAULT 0,
    UNIQUE(function_id, code)
);

-- MCU5.0 subcategories
CREATE TABLE prisma.catalog_subcategories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID NOT NULL REFERENCES prisma.catalog_categories(id) ON DELETE CASCADE,
    code        VARCHAR(20) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order  INT NOT NULL DEFAULT 0,
    UNIQUE(category_id, code)
);

-- MCU5.0 requirements
CREATE TABLE prisma.catalog_requirements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subcategory_id  UUID NOT NULL REFERENCES prisma.catalog_subcategories(id) ON DELETE CASCADE,
    code            VARCHAR(30) NOT NULL,
    description     TEXT NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE(subcategory_id, code)
);

-- MCU5.0 controls
CREATE TABLE prisma.catalog_controls (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requirement_id  UUID NOT NULL REFERENCES prisma.catalog_requirements(id) ON DELETE CASCADE,
    code            VARCHAR(30) NOT NULL,
    description     TEXT NOT NULL,
    target_level    INT NOT NULL CHECK (target_level BETWEEN 1 AND 5),
    sort_order      INT NOT NULL DEFAULT 0,
    UNIQUE(requirement_id, code)
);

-- Evaluations
CREATE TABLE prisma.evaluations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    organization_id   UUID NOT NULL REFERENCES prisma.organizations(id),
    catalog_version   VARCHAR(20) NOT NULL DEFAULT '5.0',
    status            VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    global_maturity   INT CHECK (global_maturity BETWEEN 1 AND 5),
    created_by        UUID REFERENCES prisma.users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_org ON prisma.evaluations(organization_id);
CREATE INDEX idx_eval_status ON prisma.evaluations(status);

-- Evaluation responses
CREATE TABLE prisma.evaluation_responses (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id    UUID NOT NULL REFERENCES prisma.catalog_controls(id),
    level         INT NOT NULL CHECK (level BETWEEN 0 AND 5),
    observations  TEXT,
    responded_by  UUID REFERENCES prisma.users(id),
    responded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(evaluation_id, control_id)
);

CREATE INDEX idx_resp_eval ON prisma.evaluation_responses(evaluation_id);

-- Maturity results (calculated per subcategory)
CREATE TABLE prisma.maturity_results (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id     UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    function_id       UUID NOT NULL,
    function_name     VARCHAR(200) NOT NULL,
    category_id       UUID NOT NULL,
    category_name     VARCHAR(200) NOT NULL,
    subcategory_id    UUID NOT NULL,
    subcategory_name  VARCHAR(200) NOT NULL,
    current_level     INT NOT NULL CHECK (current_level BETWEEN 0 AND 5),
    target_level      INT NOT NULL CHECK (target_level BETWEEN 1 AND 5),
    gap               INT NOT NULL DEFAULT 0,
    UNIQUE(evaluation_id, subcategory_id)
);

CREATE INDEX idx_mat_eval ON prisma.maturity_results(evaluation_id);

-- Evidence (file references)
CREATE TABLE prisma.evidence (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id      UUID REFERENCES prisma.catalog_controls(id),
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT NOT NULL DEFAULT 0,
    file_type       VARCHAR(100),
    storage_key     VARCHAR(500) NOT NULL,
    uploaded_by     UUID REFERENCES prisma.users(id),
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    description     TEXT
);

CREATE INDEX idx_ev_eval ON prisma.evidence(evaluation_id);

-- Audit observations
CREATE TABLE prisma.audit_observations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id      UUID REFERENCES prisma.catalog_controls(id),
    type            VARCHAR(30) NOT NULL DEFAULT 'OBSERVATION',
    description     TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_by      UUID REFERENCES prisma.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Improvement plans
CREATE TABLE prisma.improvement_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id   UUID NOT NULL REFERENCES prisma.evaluations(id) ON DELETE CASCADE,
    control_id      UUID REFERENCES prisma.catalog_controls(id),
    action          TEXT NOT NULL,
    responsible     VARCHAR(200),
    priority        VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    due_date        DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit logs (append-only)
CREATE TABLE audit.audit_logs (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    tenant_id   UUID,
    action      VARCHAR(50) NOT NULL,
    resource    VARCHAR(100) NOT NULL,
    payload     JSONB,
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_tenant_ts ON audit.audit_logs(tenant_id, created_at);
