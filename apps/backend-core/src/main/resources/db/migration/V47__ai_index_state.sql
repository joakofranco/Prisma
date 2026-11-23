-- Cambio de modelos de IA: recuerda con que modelo de embedding se indexo la evidencia, para
-- reindexarla (y descartar los puntajes de citas ya calculados) cuando el modelo configurado en
-- backend-ai cambia. Un renglon por clave; hoy solo 'embedding_model'.
CREATE TABLE prisma.ai_index_state (
    key        VARCHAR(50) PRIMARY KEY,
    value      VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
