-- =====================================================================
-- V3: Hash local de contraseñas (Argon2id) para usuarios admin locales.
-- No elimina la autenticación vía Keycloak; es un respaldo/trazabilidad.
-- =====================================================================

ALTER TABLE prisma.users ADD COLUMN password_hash VARCHAR(255);