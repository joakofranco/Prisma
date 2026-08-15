-- =====================================================================
-- V4: Siembra el usuario admin en prisma.users, en espejo del usuario ya
-- sembrado en Keycloak (infra/keycloak/realm-prisma.json: admin@prisma.local).
--
-- Keycloak es la fuente de verdad para AUTENTICACION (login), pero
-- prisma.users es la fuente de verdad para AUTORIZACION: tenant_id (para
-- el aislamiento multi-tenant) y roles. Sin esta fila, CurrentUserService
-- no puede resolver ningun usuario para el JWT de admin@prisma.local (el
-- email no matchea ninguna fila), y el aislamiento por tenant deniega por
-- defecto para ese usuario al no poder determinar su rol/tenant.
--
-- Nota: crear un usuario nuevo via POST /api/users solo lo crea en esta
-- tabla, todavia NO lo provisiona en Keycloak (no puede loguearse) ni
-- viceversa. Ese puente (alta conjunta / auto-provision) queda pendiente.
-- =====================================================================

INSERT INTO prisma.users (id, email, first_name, last_name, tenant_id, enabled) VALUES
('b0000000-0000-0000-0000-000000000001', 'admin@prisma.local', 'Admin', 'PRISMA', NULL, true);

INSERT INTO prisma.user_roles (user_id, role) VALUES
('b0000000-0000-0000-0000-000000000001', 'PRISMA_ADMIN');
