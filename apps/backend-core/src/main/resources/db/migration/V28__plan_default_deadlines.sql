-- RF-PLN-03: plazos por defecto (en meses) con los que se sugiere la fecha límite de cada acción
-- del plan de mejora según su prioridad. Los configura el Administrador de Plataforma; los valores
-- iniciales son los del requerimiento (Alta 3, Media 6, Baja 12 meses).
CREATE TABLE prisma.plan_default_deadlines (
    priority    VARCHAR(10) PRIMARY KEY CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    months      INTEGER     NOT NULL CHECK (months BETWEEN 1 AND 60),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO prisma.plan_default_deadlines (priority, months) VALUES
    ('HIGH', 3),
    ('MEDIUM', 6),
    ('LOW', 12);
