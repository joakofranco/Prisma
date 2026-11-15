-- RF-PLN-02: comentarios libres del ítem del plan de mejora. Nullable para que los ítems que ya
-- existen (y los que genera RF-PLN-01 a partir de las brechas) sigan siendo válidos sin comentario.
ALTER TABLE prisma.improvement_plans
    ADD COLUMN comments TEXT;
