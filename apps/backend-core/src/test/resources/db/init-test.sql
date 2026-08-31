-- =====================================================================
-- init-test.sql — Esquemas necesarios para el contenedor de pruebas
-- (Flyway ejecuta las migraciones V1/V2 dentro de estos esquemas).
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS prisma;
CREATE SCHEMA IF NOT EXISTS audit;