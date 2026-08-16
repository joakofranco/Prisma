-- catalog_subcategories.name ya se ensancho a TEXT en V9 (los nombres reales del MCU 5.0 son
-- oraciones completas, no encabezados cortos, y superan los 200 caracteres). maturity_results
-- guarda una copia desnormalizada de ese nombre al momento de calcular la madurez
-- (EvaluationService.calculateMaturity) pero se quedo en VARCHAR(200): el insert revienta con
-- "value too long for type character varying(200)" apenas la subcategoria involucrada tiene un
-- nombre largo real (destapado por los tests de integracion, ver PrismaApplicationIT).
ALTER TABLE prisma.maturity_results ALTER COLUMN subcategory_name TYPE TEXT;
