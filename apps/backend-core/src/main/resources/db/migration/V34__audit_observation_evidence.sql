-- La observacion de auditoria queda ligada a la evidencia citada (PRI-360, RF-EVI-15).
-- Antes la relacion con el documento solo existia como texto dentro de la descripcion. Es
-- opcional (una observacion puede no venir de una cita) y, si la evidencia se borra, la
-- observacion se conserva sin el vinculo (la descripcion sigue nombrando el documento).
ALTER TABLE prisma.audit_observations
    ADD COLUMN evidence_id UUID REFERENCES prisma.evidence(id) ON DELETE SET NULL;

CREATE INDEX idx_audit_observations_evidence ON prisma.audit_observations(evidence_id);
