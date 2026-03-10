"""
DatabaseConfig for the ZCloud Data Management Microservice.

Manages PostgreSQL connection settings using environment variables.
Supports connection pooling. Logs connection errors with detailed context.

Migrated from: com.zcloud.platform.config.DatabaseConfig (Java/Spring Boot)
"""

import logging
import os

logger = logging.getLogger(__name__)


def get_database_url() -> str:
    """
    Build a PostgreSQL connection URL from environment variables.

    Required environment variables:
        DB_HOST     — PostgreSQL hostname
        DB_PORT     — PostgreSQL port (default: 5432)
        DB_NAME     — Database name
        DB_USER     — Database user
        DB_PASSWORD — Database password
    """
    host = os.environ.get("DB_HOST", "localhost")
    port = os.environ.get("DB_PORT", "5432")
    name = os.environ.get("DB_NAME", "zcloud")
    user = os.environ.get("DB_USER", "zcloud")
    password = os.environ.get("DB_PASSWORD", "zcloud_secret")

    url = f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{name}"
    logger.info(
        "DatabaseConfig: host=%s port=%s dbname=%s user=%s",
        host, port, name, user,
    )
    return url


# Connection pool settings — tunable via environment variables
POOL_SIZE: int = int(os.environ.get("DB_POOL_SIZE", "5"))
MAX_OVERFLOW: int = int(os.environ.get("DB_MAX_OVERFLOW", "10"))
POOL_PRE_PING: bool = True
