-- RF-AUD-05: permite al auditor registrar una observación atada a una subcategoría del catálogo,
-- además de (u opcionalmente en vez de) a un control puntual -- antes solo se podía asociar a un
-- control o dejarla general (sin ninguno de los dos).
ALTER TABLE prisma.audit_observations
    ADD COLUMN subcategory_id UUID REFERENCES prisma.catalog_subcategories (id);
