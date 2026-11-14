-- Cierre de sesión por inactividad (control SF.4-6 del MCU 5.0): tiempo sin actividad tras el cual
-- el frontend cierra la sesión y segundos de aviso previos. Fila única que configura el
-- Administrador de Plataforma; los valores iniciales son 30 minutos y 60 segundos de aviso.
CREATE TABLE prisma.session_settings (
    id                       SMALLINT    PRIMARY KEY CHECK (id = 1),
    idle_timeout_minutes     INTEGER     NOT NULL CHECK (idle_timeout_minutes BETWEEN 5 AND 120),
    warning_seconds          INTEGER     NOT NULL CHECK (warning_seconds BETWEEN 15 AND 300),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO prisma.session_settings (id, idle_timeout_minutes, warning_seconds) VALUES (1, 30, 60);
