"""Versión y entorno de este servicio.

La versión se declara acá (es la misma que `pom.xml`, `apps/frontend/package.json` y
`sonar-project.properties`: se cambian juntas con `scripts/bump-version.sh`). El entorno viene de la
variable `APP_ENV` (en docker-compose, el `ENV` del `.env`): `development`, `staging` o
`production`. Sin ella se asume `development`: si alguien olvida configurarla en producción, el
error es visible en vez de pasar desapercibido.
"""

__version__ = "0.6.0"

DEVELOPMENT = "development"

_ALIASES = {
    "dev": DEVELOPMENT,
    "develop": DEVELOPMENT,
    "local": DEVELOPMENT,
    "stage": "staging",
    "prod": "production",
}


def normalize_environment(raw: str | None) -> str:
    """Acepta los alias habituales; un valor desconocido se conserva en minúsculas."""
    value = (raw or "").strip().lower()
    if not value:
        return DEVELOPMENT
    return _ALIASES.get(value, value)
