"""
SQLAlchemy engine and session factory for the ZCloud Data Management Microservice.

Uses connection settings from services.data_service.config (DatabaseConfig module).
Supports connection pooling and logs any connection errors with detailed context.

Migrated from: com.zcloud.platform.config.DatabaseConfig (Java/Spring Boot)
"""

import logging

from sqlalchemy import create_engine, event, text
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import sessionmaker, Session as DbSession

from services.data_service.config import (
    get_database_url,
    POOL_SIZE,
    MAX_OVERFLOW,
    POOL_PRE_PING,
)
from services.data_service.models import Base

logger = logging.getLogger(__name__)


def _create_engine():
    url = get_database_url()
    try:
        eng = create_engine(
            url,
            pool_size=POOL_SIZE,
            max_overflow=MAX_OVERFLOW,
            pool_pre_ping=POOL_PRE_PING,
        )
        return eng
    except Exception as exc:
        logger.error(
            "Failed to create database engine: url=%s error=%s",
            url,
            exc,
        )
        raise


engine = _create_engine()


@event.listens_for(engine, "connect")
def _on_connect(dbapi_connection, connection_record):
    logger.debug("New database connection established")


SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)


def init_db() -> None:
    """Create all tables (idempotent — safe to call on every startup)."""
    try:
        Base.metadata.create_all(engine)
        logger.info("Data service database tables initialised successfully")
    except OperationalError as exc:
        logger.error(
            "Failed to initialise database tables: %s", exc, exc_info=True
        )
        raise


def get_db() -> DbSession:
    """Return a new SQLAlchemy session. Caller is responsible for closing it."""
    return SessionFactory()
