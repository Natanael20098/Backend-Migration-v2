"""
Shared pytest fixtures for the ZCloud integration test suite.
"""
import os
import sys
import uuid
from datetime import datetime, timezone, timedelta
from unittest.mock import MagicMock, patch

import bcrypt
import jwt
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

AUTH_ENV_DEFAULTS = {
    "JWT_SECRET": "test-secret-key-at-least-32-chars-long!",
    "JWT_EXPIRATION_SECONDS": "3600",
    "FERNET_KEY": "",  # let the service auto-generate one in tests
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
# Auth service test helpers
# ---------------------------------------------------------------------------

TEST_JWT_SECRET = "test-secret-key-at-least-32-chars-long!"
TEST_JWT_ALGORITHM = "HS256"


def make_user(
    username="testuser",
    email="test@example.com",
    password="TestPass123!",
    roles="ROLE_USER",
    is_active=True,
):
    """Create a mock User ORM object with a bcrypt-hashed password."""
    from services.auth_service.models import User

    user = User()
    user.id = uuid.uuid4()
    user.username = username
    user.email = email
    user.password_hash = bcrypt.hashpw(
        password.encode(), bcrypt.gensalt()
    ).decode()
    user.roles = roles
    user.is_active = is_active
    user.created_at = datetime.now(timezone.utc)
    user.updated_at = datetime.now(timezone.utc)
    return user


def make_valid_token(user, secret=TEST_JWT_SECRET, expiry_seconds=3600):
    """Issue a valid signed JWT for the given user."""
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user.id),
        "email": user.email,
        "username": user.username,
        "roles": user.get_roles(),
        "iss": "zcloud-auth-service",
        "aud": "zcloud-platform",
        "iat": now,
        "exp": now + timedelta(seconds=expiry_seconds),
    }
    return jwt.encode(payload, secret, algorithm=TEST_JWT_ALGORITHM)


def make_expired_token(user, secret=TEST_JWT_SECRET):
    """Issue a JWT that has already expired."""
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user.id),
        "email": user.email,
        "username": user.username,
        "roles": user.get_roles(),
        "iss": "zcloud-auth-service",
        "aud": "zcloud-platform",
        "iat": now - timedelta(seconds=7200),
        "exp": now - timedelta(seconds=3600),
    }
    return jwt.encode(payload, secret, algorithm=TEST_JWT_ALGORITHM)


def make_mock_session(user, token, is_active=True):
    """Create a mock Session ORM object."""
    from services.auth_service.models import Session

    session = Session()
    session.id = uuid.uuid4()
    session.user_id = user.id
    session.token = token
    session.is_active = is_active
    session.created_at = datetime.now(timezone.utc)
    session.expires_at = datetime.now(timezone.utc) + timedelta(hours=1)
    return session


# ---------------------------------------------------------------------------
# Flask test client fixture — health service
# ---------------------------------------------------------------------------

@pytest.fixture()
def flask_client(db_env):
    """Return a Flask test client for the health_service app."""
    from services.health_service.app import app

    app.config["TESTING"] = True
    with app.test_client() as client:
        yield client


# ---------------------------------------------------------------------------
# Flask test client fixture — auth service (no real DB)
# ---------------------------------------------------------------------------

@pytest.fixture()
def auth_app(monkeypatch):
    """
    Create the auth service Flask app with the database engine replaced by
    an in-memory SQLite engine so tests run without a real PostgreSQL instance.
    """
    # Patch env vars before any module-level code runs
    monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setenv("JWT_EXPIRATION_SECONDS", "3600")
    monkeypatch.setenv("FERNET_KEY", "")

    # Patch SQLAlchemy engine creation so no real DB is needed
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from services.auth_service import models

    test_engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
    )
    models.Base.metadata.create_all(test_engine)
    TestSessionFactory = sessionmaker(bind=test_engine, expire_on_commit=False)

    import services.auth_service.db as db_module
    monkeypatch.setattr(db_module, "engine", test_engine)
    monkeypatch.setattr(db_module, "SessionFactory", TestSessionFactory)

    # Patch init_db so it doesn't try to reach PostgreSQL on app startup
    monkeypatch.setattr(db_module, "init_db", lambda: None)

    # Also patch config so JWT_SECRET is the test value
    import services.auth_service.config as cfg_module
    monkeypatch.setattr(cfg_module, "JWT_SECRET", TEST_JWT_SECRET)

    import services.auth_service.jwt_filter as jf_module
    monkeypatch.setattr(jf_module, "JWT_SECRET", TEST_JWT_SECRET)
    monkeypatch.setattr(jf_module, "JWT_AUDIENCE", "zcloud-platform")

    from services.auth_service.app import create_app
    app = create_app()
    app.config["TESTING"] = True
    return app, TestSessionFactory


@pytest.fixture()
def auth_client(auth_app):
    """Return a Flask test client for the auth_service app."""
    app, _ = auth_app
    with app.test_client() as client:
        yield client


@pytest.fixture()
def auth_client_with_session(auth_app):
    """Return both the test client and the session factory for the auth service."""
    app, session_factory = auth_app
    with app.test_client() as client:
        yield client, session_factory
