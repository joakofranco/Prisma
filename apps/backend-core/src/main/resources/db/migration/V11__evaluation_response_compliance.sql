-- El catálogo real (V9) pasa cada control de "autoevaluación de madurez 1-5" a "ítem de
-- checklist" (cumple / no cumple) dentro de un Requisito graduado por nivel 1-4 -- la madurez
-- de una Subcategoría ahora se calcula de forma acumulativa (nivel alcanzado = el mayor N tal
-- que TODOS los controles de nivel <= N están cumplidos), no como el máximo autoevaluado por
-- control. No hay filas existentes que migrar (evaluation_responses se vació a mano antes de
-- importar el catálogo real, ver commit), así que este cambio de columna es directo.
ALTER TABLE prisma.evaluation_responses DROP COLUMN level;
ALTER TABLE prisma.evaluation_responses ADD COLUMN compliant BOOLEAN NOT NULL DEFAULT FALSE;
