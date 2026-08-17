-- Perfiles comunitarios: subconjuntos curados de controles del catálogo (p.ej. "Gobierno",
-- "PYME"), pensados para que una organización evalúe solo los controles relevantes para su
-- sector en vez del catálogo MCU 5.0 completo. Al crear una evaluación se puede elegir uno
-- (opcional -- si no se elige ninguno, la evaluación sigue usando el catálogo completo, como
-- hasta ahora).
CREATE TABLE prisma.community_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    catalog_version VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Qué controles incluye cada perfil (many-to-many). ON DELETE CASCADE en control_id: si el
-- catálogo cambia y un control se elimina, no deja referencias colgando en los perfiles.
CREATE TABLE prisma.community_profile_controls (
    profile_id UUID NOT NULL REFERENCES prisma.community_profiles(id) ON DELETE CASCADE,
    control_id UUID NOT NULL REFERENCES prisma.catalog_controls(id) ON DELETE CASCADE,
    PRIMARY KEY (profile_id, control_id)
);

-- NULL = evaluación sobre el catálogo completo (comportamiento anterior, sigue siendo el
-- default). ON DELETE SET NULL: borrar un perfil no debe destruir evaluaciones que ya lo usaron,
-- solo hace que vuelvan a verse como "catálogo completo".
ALTER TABLE prisma.evaluations
    ADD COLUMN community_profile_id UUID REFERENCES prisma.community_profiles(id) ON DELETE SET NULL;

CREATE INDEX idx_cpc_profile ON prisma.community_profile_controls(profile_id);
