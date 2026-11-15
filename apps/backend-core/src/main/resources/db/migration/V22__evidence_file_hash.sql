-- RF-EVI-05: hash SHA-256 del contenido real de cada evidencia, calculado al subirla,
-- para poder detectar alteraciones posteriores del archivo almacenado.
ALTER TABLE prisma.evidence
    ADD COLUMN file_hash VARCHAR(64);
