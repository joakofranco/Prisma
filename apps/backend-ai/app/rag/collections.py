"""Nombres de colecciones Chroma por modelo de embedding.

Chroma no mezcla dimensiones dentro de una colección (nomic-embed-text: 768, bge-m3: 1024) y los
puntajes de un modelo no valen para otro. Por eso cada colección lleva el modelo en el nombre:

- marco MCU: ``mcu-5.0__bge-m3``
- evidencia de una organización: ``evidence-<org>__bge-m3``

Cambiar de modelo crea colecciones nuevas y NO toca las anteriores, así que se puede volver atrás
(las viejas quedan sin uso, hay que borrarlas a mano cuando ya no hagan falta).
"""

from __future__ import annotations

import re

from app.core.config import Settings

_MCU_BASE = "mcu-5.0"
_EVIDENCE_BASE = "evidence"
_SEPARATOR = "__"


def model_slug(model: str) -> str:
    """Nombre de modelo apto para un nombre de colección de Chroma ([a-zA-Z0-9._-]).

    ``bge-m3`` y ``bge-m3:latest`` son el mismo modelo (Ollama agrega ``:latest`` al listarlo): dan
    el mismo slug. Otras etiquetas se conservan (``bge-m3:v2`` -> ``bge-m3-v2``).
    """
    name = model.strip().lower()
    if name.endswith(":latest"):
        name = name[: -len(":latest")]
    slug = re.sub(r"[^a-z0-9._-]+", "-", name).strip("-._")
    return slug or "default"


def mcu_collection(settings: Settings) -> str:
    return f"{_MCU_BASE}{_SEPARATOR}{model_slug(settings.ollama_embedding_model)}"


def evidence_collection(settings: Settings, organization_id: str) -> str:
    return (
        f"{_EVIDENCE_BASE}-{organization_id}{_SEPARATOR}"
        f"{model_slug(settings.ollama_embedding_model)}"
    )


def is_evidence_collection_of(name: str, organization_id: str) -> bool:
    """¿``name`` es una colección de evidencia de la organización, de CUALQUIER modelo?

    Incluye la colección anterior sin sufijo (``evidence-<org>``). Se usa para borrar una evidencia
    de todos los índices: un modelo anterior no puede dejar copias del archivo.
    """
    prefix = f"{_EVIDENCE_BASE}-{organization_id}"
    return name == prefix or name.startswith(prefix + _SEPARATOR)
