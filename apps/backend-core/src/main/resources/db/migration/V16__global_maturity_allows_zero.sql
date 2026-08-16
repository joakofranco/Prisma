-- =====================================================================
-- V16__global_maturity_allows_zero.sql
-- evaluations.global_maturity solo aceptaba 1-5 (o NULL para "todavía sin
-- evaluar"), así que un resultado global que promediaba por debajo de 1
-- se guardaba como NULL -- indistinguible en la UI de "nunca se calculó".
-- Nivel 0 es un resultado real y distinto de "no evaluado" (ver
-- EvaluationService.calculateMaturity), así que pasa a ser un valor
-- válido, igual que ya lo es en maturity_results.current_level.
-- =====================================================================

ALTER TABLE prisma.evaluations DROP CONSTRAINT evaluations_global_maturity_check;
ALTER TABLE prisma.evaluations
    ADD CONSTRAINT evaluations_global_maturity_check CHECK (global_maturity >= 0 AND global_maturity <= 5);
