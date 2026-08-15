-- Vincula cada prisma.users con su cuenta en Keycloak (la única fuente de verdad para
-- autenticación). Sin esta columna, backend-core no tiene forma de saber si un usuario dado de
-- alta acá (POST /api/users) ya tiene o no una cuenta que le permita loguearse: hasta ahora
-- crear un usuario solo insertaba esta fila y nunca lo provisionaba en Keycloak, así que
-- cualquier usuario nuevo (p.ej. con rol AUDITOR) quedaba con acceso a la app pero sin poder
-- iniciar sesión nunca.
--
-- NULL significa "no provisionado en Keycloak todavía" -- incluye a los usuarios creados antes
-- de este cambio (como el admin sembrado en V4, que sí puede loguearse porque su cuenta de
-- Keycloak se creó aparte, vía infra/keycloak/realm-prisma.json).
ALTER TABLE prisma.users
    ADD COLUMN keycloak_id UUID;
