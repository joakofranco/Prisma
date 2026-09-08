-- =====================================================================
-- V18: mapeo curado Control <-> Subcategoría.
-- ---------------------------------------------------------------------
-- Hasta ahora la madurez de una Subcategoría se calculaba metiendo cada Control en
-- TODAS las Subcategorías a las que mapea su Requisito (CatalogRequirementSubcategory).
-- La planilla oficial de Agesic (MCU 5.0) usa una asignación curada: cada Control se
-- ubica en un subconjunto de esas Subcategorías (~1010 pares vs ~1921), y la madurez
-- de la Subcategoría se calcula sólo sobre los Controles ubicados en ella.
--
-- Esta tabla guarda ese mapeo. Es OPCIONAL: si un catálogo no la puebla (5.0 / 5.1),
-- EvaluationService.calculateMaturity cae al mapeo Requisito->Subcategoría
-- (CatalogControl.effectiveSubcategories()).
--
-- Sólo DDL: los datos se cargan con el catálogo (import JSON / scripts/mcu52).
-- =====================================================================
CREATE TABLE prisma.catalog_control_subcategories (
    control_id     UUID NOT NULL REFERENCES prisma.catalog_controls(id)      ON DELETE CASCADE,
    subcategory_id UUID NOT NULL REFERENCES prisma.catalog_subcategories(id) ON DELETE CASCADE,
    PRIMARY KEY (control_id, subcategory_id)
);

CREATE INDEX idx_ccs_subcategory ON prisma.catalog_control_subcategories(subcategory_id);
