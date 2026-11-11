-- RNF-09: estado del análisis antivirus de cada evidencia. CLEAN = analizada y sin amenazas;
-- QUARANTINED = subida mientras el antivirus no respondía, en espera de ser analizada;
-- INFECTED = el análisis posterior detectó malware. Las evidencias anteriores a este cambio
-- quedan como CLEAN y no se vuelven a analizar.
ALTER TABLE prisma.evidence
    ADD COLUMN scan_status VARCHAR(20) NOT NULL DEFAULT 'CLEAN';
