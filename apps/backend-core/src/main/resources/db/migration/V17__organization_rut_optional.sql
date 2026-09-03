-- Uruguay usa "RUT" (Registro Único Tributario), no "NIT" (Colombia y otros) -- se corrige el
-- nombre del campo. Pasa a ser opcional: no toda organización lo tiene cargado al momento del
-- alta. La UNIQUE constraint sigue vigente (renombrada junto con la columna): Postgres no cuenta
-- NULL contra ella, así que múltiples organizaciones sin RUT conviven sin conflicto.
ALTER TABLE prisma.organizations RENAME COLUMN nit TO rut;
ALTER TABLE prisma.organizations ALTER COLUMN rut DROP NOT NULL;
-- Puramente cosmético (el rename de columna de arriba ya actualiza solo el índice subyacente):
-- sin esto la constraint quedaba con el nombre viejo "organizations_nit_key" aunque ya no exista
-- ninguna columna "nit".
ALTER TABLE prisma.organizations RENAME CONSTRAINT organizations_nit_key TO organizations_rut_key;
