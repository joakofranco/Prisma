-- =====================================================================
-- V15__requirement_subcategory_many_to_many.sql
-- Un Requisito puede pertenecer a varias Subcategorías. El catálogo real
-- ya tenía este caso en los hechos: el seed (V9) modela un mismo Requisito
-- repetido bajo cada Subcategoría a la que pertenece (mismo código,
-- p.ej. "GR.1" x5, "GI.2" x9), porque el esquema viejo solo admitía 1
-- Subcategoría por Requisito -- son 47 códigos de Requisito duplicados en
-- MCU 5.0. Esta migración:
--   1) agrega una FK directa de Requisito a Versión (antes solo se
--      llegaba atravesando subcategory->category->function),
--   2) consolida cada grupo de filas duplicadas (mismo version_id+code)
--      en UNA sola fila de catalog_requirements, preservando cada
--      asociación a Subcategoría en la nueva tabla
--      catalog_requirement_subcategories,
--   3) consolida del mismo modo los Controles duplicados que traía cada
--      copia (mismo código bajo el Requisito consolidado) en un solo
--      catalog_controls, y
--   4) reapunta evaluation_responses / evidence / audit_observations /
--      improvement_plans / community_profile_controls, que hoy pueden
--      estar repartidas entre las copias duplicadas de un mismo control,
--      al control consolidado.
--
-- El paso 4 puede encontrar respuestas CONTRADICTORIAS para lo que pasa a
-- ser un único control (evaluado como "Cumple" bajo una copia y "No
-- cumple" bajo otra, porque hoy el asistente las trataba como controles
-- distintos): se resuelve quedándose con la respuesta de responded_at
-- más reciente (mismo criterio de "pisa la anterior" que ya usa
-- EvaluationService.saveResponse para una única respuesta).
-- =====================================================================

-- 1) FK directa de requisito a versión, backfillada desde el camino viejo.
ALTER TABLE prisma.catalog_requirements ADD COLUMN version_id UUID;

UPDATE prisma.catalog_requirements r
SET version_id = f.version_id
FROM prisma.catalog_subcategories s
JOIN prisma.catalog_categories c ON c.id = s.category_id
JOIN prisma.catalog_functions f ON f.id = c.function_id
WHERE s.id = r.subcategory_id;

ALTER TABLE prisma.catalog_requirements ALTER COLUMN version_id SET NOT NULL;
ALTER TABLE prisma.catalog_requirements
    ADD CONSTRAINT fk_catalog_requirements_version
    FOREIGN KEY (version_id) REFERENCES prisma.catalog_versions(id) ON DELETE CASCADE;

-- 2) Mapa de consolidación de Requisitos duplicados: por cada
-- (version_id, code), la fila "canónica" es la de menor id -- no hay un
-- criterio de negocio para elegir una copia sobre otra (misma
-- descripción en el 100% de los casos, ver comentario arriba), así que
-- alcanza con un desempate determinístico.
-- (uuid no tiene función agregada MIN/MAX registrada en Postgres, aunque sí soporta ORDER BY --
-- de ahí el DISTINCT ON en vez de MIN(...) OVER (...) para elegir la copia canónica.)
CREATE TEMP TABLE requirement_map ON COMMIT DROP AS
SELECT r.id AS old_id, pick.canonical_id
FROM prisma.catalog_requirements r
JOIN (
    SELECT DISTINCT ON (version_id, code) version_id, code, id AS canonical_id
    FROM prisma.catalog_requirements
    ORDER BY version_id, code, id
) pick ON pick.version_id = r.version_id AND pick.code = r.code;

-- Asociaciones Requisito <-> Subcategoría: una fila por cada Requisito
-- ORIGINAL (duplicado o no), apuntando siempre al Requisito canónico --
-- esto es lo que reconstruye, para un código repetido como "GR.1", el
-- vínculo a cada una de sus Subcategorías reales.
CREATE TABLE prisma.catalog_requirement_subcategories (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requirement_id UUID NOT NULL REFERENCES prisma.catalog_requirements(id) ON DELETE CASCADE,
    subcategory_id UUID NOT NULL REFERENCES prisma.catalog_subcategories(id) ON DELETE CASCADE,
    sort_order     INT NOT NULL DEFAULT 0,
    UNIQUE (requirement_id, subcategory_id)
);

INSERT INTO prisma.catalog_requirement_subcategories (requirement_id, subcategory_id, sort_order)
SELECT rm.canonical_id, r.subcategory_id, r.sort_order
FROM prisma.catalog_requirements r
JOIN requirement_map rm ON rm.old_id = r.id;

-- 3) Mapa de consolidación de Controles duplicados: agrupa por
-- (requisito CANÓNICO, código de control) -- dos controles con el mismo
-- código bajo dos copias distintas del mismo Requisito son, en los
-- hechos, el mismo control. Canónico = menor id dentro del grupo.
CREATE TEMP TABLE control_req_map ON COMMIT DROP AS
SELECT c.id AS control_id, c.code AS control_code, rm.canonical_id AS canonical_requirement_id
FROM prisma.catalog_controls c
JOIN requirement_map rm ON rm.old_id = c.requirement_id;

CREATE TEMP TABLE control_map ON COMMIT DROP AS
SELECT crm.control_id AS old_id, pick.canonical_id
FROM control_req_map crm
JOIN (
    SELECT DISTINCT ON (canonical_requirement_id, control_code) canonical_requirement_id, control_code, control_id AS canonical_id
    FROM control_req_map
    ORDER BY canonical_requirement_id, control_code, control_id
) pick ON pick.canonical_requirement_id = crm.canonical_requirement_id AND pick.control_code = crm.control_code;

-- 4) Reapuntar todo lo que referencia catalog_controls al control
-- canónico, antes de borrar los controles duplicados.

-- evaluation_responses tiene UNIQUE(evaluation_id, control_id): si una
-- evaluación respondió mas de una copia del mismo control, se descartan
-- todas menos la de responded_at mas reciente (empate -> id mayor).
DELETE FROM prisma.evaluation_responses er
USING (
    SELECT er2.id
    FROM prisma.evaluation_responses er2
    JOIN control_map cm ON cm.old_id = er2.control_id
    WHERE er2.id NOT IN (
        SELECT DISTINCT ON (er3.evaluation_id, cm2.canonical_id) er3.id
        FROM prisma.evaluation_responses er3
        JOIN control_map cm2 ON cm2.old_id = er3.control_id
        ORDER BY er3.evaluation_id, cm2.canonical_id, er3.responded_at DESC, er3.id DESC
    )
) losers
WHERE er.id = losers.id;

UPDATE prisma.evaluation_responses er
SET control_id = cm.canonical_id
FROM control_map cm
WHERE er.control_id = cm.old_id AND cm.old_id <> cm.canonical_id;

-- community_profile_controls tiene PK(profile_id, control_id) y no tiene
-- id propio -- más simple reconstruirla entera a partir de los pares
-- (perfil, control canónico) ya deduplicados que quedar remapeando fila
-- por fila y toparse con la PK cuando dos perfiles-control distintos
-- colapsan al mismo control canónico.
CREATE TEMP TABLE cpc_final ON COMMIT DROP AS
SELECT DISTINCT cpc.profile_id, COALESCE(cm.canonical_id, cpc.control_id) AS control_id
FROM prisma.community_profile_controls cpc
LEFT JOIN control_map cm ON cm.old_id = cpc.control_id;

DELETE FROM prisma.community_profile_controls;
INSERT INTO prisma.community_profile_controls (profile_id, control_id)
SELECT profile_id, control_id FROM cpc_final;

-- evidence, audit_observations e improvement_plans no tienen restricción
-- de unicidad sobre control_id: reapuntar es un UPDATE directo.
UPDATE prisma.evidence e
SET control_id = cm.canonical_id
FROM control_map cm
WHERE e.control_id = cm.old_id AND cm.old_id <> cm.canonical_id;

UPDATE prisma.audit_observations ao
SET control_id = cm.canonical_id
FROM control_map cm
WHERE ao.control_id = cm.old_id AND cm.old_id <> cm.canonical_id;

UPDATE prisma.improvement_plans ip
SET control_id = cm.canonical_id
FROM control_map cm
WHERE ip.control_id = cm.old_id AND cm.old_id <> cm.canonical_id;

-- Ya no queda nada apuntando a los controles duplicados: borrarlos.
DELETE FROM prisma.catalog_controls c
USING control_map cm
WHERE c.id = cm.old_id AND cm.old_id <> cm.canonical_id;

-- Los controles que sobreviven reapuntan al Requisito canónico (puede
-- ser distinto del que tenían: un control puede haber sobrevivido desde
-- una copia que no fue elegida como canónica, ver GR.1-4 en el
-- comentario de arriba -- no todas las copias traían el mismo set de
-- controles).
UPDATE prisma.catalog_controls c
SET requirement_id = rm.canonical_id
FROM requirement_map rm
WHERE c.requirement_id = rm.old_id AND rm.old_id <> rm.canonical_id;

-- 5) Ya no queda nada apuntando a los Requisitos duplicados: borrarlos, y
-- reemplazar la relación vieja (1 Requisito : 1 Subcategoría) por la
-- nueva unicidad (código único por versión de catálogo, no por
-- subcategoría).
DELETE FROM prisma.catalog_requirements r
USING requirement_map rm
WHERE r.id = rm.old_id AND rm.old_id <> rm.canonical_id;

ALTER TABLE prisma.catalog_requirements DROP CONSTRAINT catalog_requirements_subcategory_id_fkey;
ALTER TABLE prisma.catalog_requirements DROP CONSTRAINT catalog_requirements_subcategory_id_code_key;
ALTER TABLE prisma.catalog_requirements DROP COLUMN subcategory_id;
ALTER TABLE prisma.catalog_requirements ADD CONSTRAINT catalog_requirements_version_id_code_key UNIQUE (version_id, code);
