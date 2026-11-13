"""Test del endpoint de health."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_ping():
    response = client.get("/api/v1/ping")
    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_health_reports_version_and_environment(monkeypatch):
    from app.core.config import get_settings
    from app.version import __version__

    monkeypatch.setenv("APP_ENV", "prod")
    get_settings.cache_clear()
    try:
        body = client.get("/health").json()
    finally:
        monkeypatch.delenv("APP_ENV", raising=False)
        get_settings.cache_clear()

    assert body["version"] == __version__
    # El alias «prod» se informa como «production».
    assert body["environment"] == "production"


def test_unset_environment_is_reported_as_development(monkeypatch):
    from app.core.config import get_settings

    monkeypatch.delenv("APP_ENV", raising=False)
    get_settings.cache_clear()
    try:
        body = client.get("/health").json()
    finally:
        get_settings.cache_clear()

    assert body["environment"] == "development"
