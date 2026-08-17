-- Marca si una evidencia fue indexada correctamente en el vector store de backend-ai
-- (colección aislada por organización, ver EvidenceStore en backend-ai). false por defecto:
-- una evidencia recién subida, o cuya indexación falló/backend-ai estaba caído, queda en false
-- hasta que se reintenta con éxito vía POST /api/evidence/{id}/index.
ALTER TABLE prisma.evidence
    ADD COLUMN ai_indexed BOOLEAN NOT NULL DEFAULT FALSE;
