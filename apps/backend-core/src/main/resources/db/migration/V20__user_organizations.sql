-- Membresía usuario <-> organización (M:N), una sola fuente de verdad para "a qué organizaciones
-- accede este usuario". Reemplaza a las dos representaciones anteriores:
--   * users.tenant_id (UNA organización, para ORG_RESPONSIBLE / INTERNAL_EVALUATOR / VIEWER);
--   * user_audited_organizations (VARIAS, solo para AUDITOR; ver V14).
--
-- Reglas de negocio que se validan en UserService (no en la base):
--   * ORG_RESPONSIBLE y VIEWER: exactamente una organización.
--   * AUDITOR: al menos una.
--   * INTERNAL_EVALUATOR: una o varias; SIN ninguna es un evaluador "general" (accede a todas).
--   * PRISMA_ADMIN: global, no necesita ninguna.
--
-- Migración de datos: cada usuario conserva exactamente las organizaciones a las que accedía
-- (su tenant, más las que auditaba). OJO: un usuario con INTERNAL_EVALUATOR y SIN tenant ni
-- organizaciones auditadas -- que antes no accedía a nada -- pasa a ser evaluador general; antes de
-- desplegar conviene revisar que no exista ninguno (SELECT ... FROM prisma.users u JOIN
-- prisma.user_roles r ON r.user_id = u.id AND r.role = 'INTERNAL_EVALUATOR' WHERE u.tenant_id IS
-- NULL AND NOT EXISTS (SELECT 1 FROM prisma.user_audited_organizations a WHERE a.user_id = u.id)).
CREATE TABLE prisma.user_organizations (
    user_id         UUID NOT NULL REFERENCES prisma.users(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES prisma.organizations(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, organization_id)
);

CREATE INDEX idx_user_organizations_org ON prisma.user_organizations(organization_id);

INSERT INTO prisma.user_organizations (user_id, organization_id)
SELECT id, tenant_id FROM prisma.users WHERE tenant_id IS NOT NULL
UNION
SELECT user_id, organization_id FROM prisma.user_audited_organizations;

DROP TABLE prisma.user_audited_organizations;
ALTER TABLE prisma.users DROP COLUMN tenant_id;
