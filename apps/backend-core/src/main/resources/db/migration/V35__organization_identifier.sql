-- El identificador de una organización deja de ser solo un RUT: pasa a llamarse `identifier` y una
-- casilla (`identifier_is_rut`) indica si es un RUT uruguayo, en cuyo caso el servicio valida su
-- formato y su dígito verificador y lo guarda normalizado («AA-BBBBBB-001-D»).
--
-- El control de duplicados no cambia: sigue comparando sin puntos, guiones, espacios ni
-- mayúsculas (OrganizationRepository.findByNormalizedIdentifier), sea RUT o no. La restricción
-- única de la base compara el texto tal cual y solo es la red de seguridad.
ALTER TABLE prisma.organizations RENAME COLUMN rut TO identifier;
ALTER TABLE prisma.organizations RENAME CONSTRAINT organizations_rut_key TO organizations_identifier_key;
ALTER TABLE prisma.organizations ADD COLUMN identifier_is_rut BOOLEAN NOT NULL DEFAULT FALSE;

-- Organizaciones existentes: solo se marcan como RUT (y se guardan normalizadas) las que ya pasan
-- la validación completa. El resto queda como identificador libre, sin tocar su valor.
--   · 12 dígitos tras quitar separadores y un prefijo «UY»
--   · prefijo 01–22, secuencial distinto de 000000, grupo fijo «001»
--   · dígito verificador: (11 - (Σ dígito × peso) mod 11) mod 11 con pesos 4,3,2,9,8,7,6,5,4,3,2
--     sobre los 11 primeros; un resultado de 10 nunca coincide con un dígito (RUT inválido).
WITH normalized AS (
    SELECT id,
           regexp_replace(regexp_replace(upper(identifier), '[.\-\s]', '', 'g'), '^UY', '') AS n
    FROM prisma.organizations
    WHERE identifier IS NOT NULL
),
valid AS (
    SELECT id, n
    FROM normalized
    WHERE n ~ '^[0-9]{12}$'
      AND substr(n, 1, 2) BETWEEN '01' AND '22'
      AND substr(n, 3, 6) <> '000000'
      AND substr(n, 9, 3) = '001'
      AND (11 - (
              substr(n, 1, 1)::int * 4 + substr(n, 2, 1)::int * 3 + substr(n, 3, 1)::int * 2 +
              substr(n, 4, 1)::int * 9 + substr(n, 5, 1)::int * 8 + substr(n, 6, 1)::int * 7 +
              substr(n, 7, 1)::int * 6 + substr(n, 8, 1)::int * 5 + substr(n, 9, 1)::int * 4 +
              substr(n, 10, 1)::int * 3 + substr(n, 11, 1)::int * 2
          ) % 11) % 11 = substr(n, 12, 1)::int
)
UPDATE prisma.organizations o
SET identifier_is_rut = TRUE,
    identifier = substr(v.n, 1, 2) || '-' || substr(v.n, 3, 6) || '-' || substr(v.n, 9, 3) || '-' || substr(v.n, 12, 1)
FROM valid v
WHERE o.id = v.id;
