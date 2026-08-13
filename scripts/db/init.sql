-- =====================================================================
-- Inicialización de PostgreSQL para PRISMA
-- Este script se ejecuta automáticamente en el primer arranque del
-- contenedor de Postgres (via /docker-entrypoint-initdb.d/).
-- =====================================================================

-- Extensiones útiles
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Schemas
CREATE SCHEMA IF NOT EXISTS prisma;
CREATE SCHEMA IF NOT EXISTS audit;

-- Permisos para el usuario de la aplicación
DO $$
BEGIN
  -- Dar permisos al usuario prisma sobre los schemas
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'prisma') THEN
    GRANT ALL PRIVILEGES ON SCHEMA prisma TO prisma;
    GRANT ALL PRIVILEGES ON SCHEMA audit TO prisma;
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA prisma TO prisma;
    GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA audit TO prisma;
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA prisma TO prisma;
    GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA audit TO prisma;
    ALTER DEFAULT PRIVILEGES IN SCHEMA prisma GRANT ALL ON TABLES TO prisma;
    ALTER DEFAULT PRIVILEGES IN SCHEMA prisma GRANT ALL ON SEQUENCES TO prisma;
  END IF;

  -- Rol de sólo lectura para dashboards
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'prisma_readonly') THEN
    CREATE ROLE prisma_readonly LOGIN PASSWORD 'change_me_ro';
  END IF;
  GRANT USAGE ON SCHEMA prisma, audit TO prisma_readonly;
  GRANT SELECT ON ALL TABLES IN SCHEMA prisma, audit TO prisma_readonly;
  ALTER DEFAULT PRIVILEGES IN SCHEMA prisma, audit GRANT SELECT ON TABLES TO prisma_readonly;
END
$$;
