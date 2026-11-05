-- RF-AUD-09: al aprobar una evaluación (transición a APPROVED) se genera un sello de
-- certificación -- quién la aprobó, cuándo, y una firma HMAC de esos metadatos -- que no se
-- vuelve a recalcular después (la evaluación nunca vuelve a pasar por APPROVED una vez que sale
-- de ahí, ver ALLOWED_TRANSITIONS en EvaluationService).
ALTER TABLE prisma.evaluations
    ADD COLUMN approved_by UUID REFERENCES prisma.users (id),
    ADD COLUMN approved_at TIMESTAMPTZ,
    ADD COLUMN certification_seal VARCHAR(64);
