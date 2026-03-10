import logging
import os

import psycopg2
from psycopg2 import OperationalError

logger = logging.getLogger(__name__)


def get_connection():
    """
    Create and return a psycopg2 connection using environment variables for credentials.

    Required environment variables:
        DB_HOST     - PostgreSQL host
        DB_PORT     - PostgreSQL port (default: 5432)
        DB_NAME     - Database name
        DB_USER     - Database user
        DB_PASSWORD - Database password
    """
    host = os.environ["DB_HOST"]
    port = os.environ.get("DB_PORT", "5432")
    dbname = os.environ["DB_NAME"]
    user = os.environ["DB_USER"]
    password = os.environ["DB_PASSWORD"]

    try:
        conn = psycopg2.connect(
            host=host,
            port=int(port),
            dbname=dbname,
            user=user,
            password=password,
            connect_timeout=5,
        )
        return conn
    except OperationalError as exc:
        logger.error(
            "Database connection failed: host=%s port=%s dbname=%s user=%s error=%s",
            host,
            port,
            dbname,
            user,
            str(exc),
        )
        raise


def check_database_health():
    """
    Perform a lightweight health check against the PostgreSQL database.

    Returns True if the database is reachable, raises an exception otherwise.
    """
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        return True
    finally:
        conn.close()
