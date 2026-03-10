"""
Shared pytest fixtures for the ZCloud integration test suite.
"""
import os
import sys
import pytest

# Ensure the project root is on sys.path so that `services.*` imports resolve
# when tests are run from the repo root without an installed package.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)


# ---------------------------------------------------------------------------
# Environment variable fixtures
# ---------------------------------------------------------------------------

DB_ENV_DEFAULTS = {
    "DB_HOST": "localhost",
    "DB_PORT": "5432",
    "DB_NAME": "zcloud",
    "DB_USER": "zcloud",
    "DB_PASSWORD": "zcloud_secret",
}


@pytest.fixture()
def db_env(monkeypatch):
    """Patch all required DB environment variables with valid-looking defaults."""
    for key, value in DB_ENV_DEFAULTS.items():
        monkeypatch.setenv(key, value)
    return dict(DB_ENV_DEFAULTS)


@pytest.fixture()
def db_env_missing_host(monkeypatch):
    """Patch DB env vars but omit DB_HOST to simulate misconfiguration."""
    for key, value in DB_ENV_DEFAULTS.items():
        monkeypatch.setenv(key, value)
    monkeypatch.delenv("DB_HOST", raising=False)


# ---------------------------------------------------------------------------
# Flask test client fixture
# ---------------------------------------------------------------------------

@pytest.fixture()
def flask_client(db_env):
    """Return a Flask test client for the health_service app."""
    from services.health_service.app import app

    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client
