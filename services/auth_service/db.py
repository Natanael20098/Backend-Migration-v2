"""
Database engine and session factory for the ZCloud Auth Service.

Uses SQLAlchemy 2.x with connection credentials sourced from environment
variables (same variables as services/shared/database.py).
"""

import os

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session as DbSession

from services.auth_service.models import Base


def _build_database_url() -> str:
    host = os.environ.get("DB_HOST", "localhost")
    port = os.environ.get("DB_PORT", "5432")
    name = os.environ.get("DB_NAME", "zcloud")
    user = os.environ.get("DB_USER", "zcloud")
    password = os.environ.get("DB_PASSWORD", "zcloud_secret")
    return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{name}"


engine = create_engine(
    _build_database_url(),
    pool_pre_ping=True,
    pool_size=5,
    max_overflow=10,
)

SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def init_db() -> None:
    """Create all tables (idempotent — safe to call on every startup)."""
    Base.metadata.create_all(engine)


def get_db() -> DbSession:
    """Return a new SQLAlchemy session. Caller is responsible for closing it."""
    return SessionFactory()
