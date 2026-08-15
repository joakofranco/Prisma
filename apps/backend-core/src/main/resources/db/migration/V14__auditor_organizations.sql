-- Organizaciones que un auditor puede auditar (M:N). Antes AUDITOR era un rol global sin
-- restricción de tenant (veía y auditaba evaluaciones de CUALQUIER organización); ahora queda
-- acotado a las organizaciones que se le asignen explícitamente acá al crearlo/editarlo -- ver
-- CurrentUserService.assertOrganizationAccess.
--
-- No se restringe a nivel de base de datos a que el usuario tenga el rol AUDITOR (eso lo valida
-- UserService al crear/editar): la tabla solo modela "qué organizaciones puede auditar este
-- usuario, si las tiene asignadas".
CREATE TABLE prisma.user_audited_organizations (
    user_id         UUID NOT NULL REFERENCES prisma.users(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES prisma.organizations(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, organization_id)
);

CREATE INDEX idx_user_audited_organizations_org ON prisma.user_audited_organizations(organization_id);
