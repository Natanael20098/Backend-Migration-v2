"""
Unit tests for services/shared/database.py.

All tests use mocks — no real PostgreSQL instance is required.
"""
import os
import pytest
from unittest.mock import MagicMock, patch, call
from psycopg2 import OperationalError


# ---------------------------------------------------------------------------
# get_connection — success path
# ---------------------------------------------------------------------------

class TestGetConnection:
    def test_returns_connection_on_success(self, db_env):
        """get_connection() returns a psycopg2 connection object when credentials are valid."""
        mock_conn = MagicMock()
        with patch("psycopg2.connect", return_value=mock_conn) as mock_connect:
            from services.shared.database import get_connection

            result = get_connection()

        assert result is mock_conn

    def test_passes_correct_credentials_to_psycopg2(self, db_env):
        """get_connection() forwards env-var credentials to psycopg2.connect."""
        mock_conn = MagicMock()
        with patch("psycopg2.connect", return_value=mock_conn) as mock_connect:
            from services.shared.database import get_connection

            get_connection()

        mock_connect.assert_called_once_with(
            host=db_env["DB_HOST"],
            port=int(db_env["DB_PORT"]),
            dbname=db_env["DB_NAME"],
            user=db_env["DB_USER"],
            password=db_env["DB_PASSWORD"],
            connect_timeout=5,
        )

    def test_uses_default_port_5432(self, monkeypatch):
        """get_connection() defaults to port 5432 when DB_PORT is not set."""
        monkeypatch.setenv("DB_HOST", "localhost")
        monkeypatch.setenv("DB_NAME", "zcloud")
        monkeypatch.setenv("DB_USER", "zcloud")
        monkeypatch.setenv("DB_PASSWORD", "secret")
        monkeypatch.delenv("DB_PORT", raising=False)

        mock_conn = MagicMock()
        with patch("psycopg2.connect", return_value=mock_conn) as mock_connect:
            from services.shared.database import get_connection

            get_connection()

        _, kwargs = mock_connect.call_args
        assert kwargs["port"] == 5432

    # ---------------------------------------------------------------------------
    # get_connection — failure paths
    # ---------------------------------------------------------------------------

    def test_raises_operational_error_on_connection_failure(self, db_env):
        """get_connection() re-raises OperationalError when the DB is unreachable."""
        with patch("psycopg2.connect", side_effect=OperationalError("connection refused")):
            from services.shared.database import get_connection

            with pytest.raises(OperationalError):
                get_connection()

    def test_raises_key_error_when_db_host_missing(self, db_env_missing_host):
        """get_connection() raises KeyError when DB_HOST env var is not set."""
        from services.shared.database import get_connection

        with pytest.raises(KeyError):
            get_connection()

    def test_logs_error_on_connection_failure(self, db_env, caplog):
        """get_connection() logs the failure details when OperationalError is raised."""
        import logging

        with patch(
            "psycopg2.connect",
            side_effect=OperationalError("timeout"),
        ):
            from services.shared import database

            with caplog.at_level(logging.ERROR, logger="services.shared.database"):
                with pytest.raises(OperationalError):
                    database.get_connection()

        assert any("Database connection failed" in record.message for record in caplog.records)


# ---------------------------------------------------------------------------
# check_database_health — success path
# ---------------------------------------------------------------------------

class TestCheckDatabaseHealth:
    def test_returns_true_when_db_responds(self, db_env):
        """check_database_health() returns True when SELECT 1 succeeds."""
        mock_cursor = MagicMock()
        mock_conn = MagicMock()
        mock_conn.cursor.return_value.__enter__ = lambda s: mock_cursor
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        with patch("psycopg2.connect", return_value=mock_conn):
            from services.shared.database import check_database_health

            result = check_database_health()

        assert result is True

    def test_executes_select_1_query(self, db_env):
        """check_database_health() runs 'SELECT 1' to probe the database."""
        mock_cursor = MagicMock()
        mock_conn = MagicMock()
        mock_conn.cursor.return_value.__enter__ = lambda s: mock_cursor
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        with patch("psycopg2.connect", return_value=mock_conn):
            from services.shared.database import check_database_health

            check_database_health()

        mock_cursor.execute.assert_called_once_with("SELECT 1")

    def test_closes_connection_after_success(self, db_env):
        """check_database_health() always closes the connection on success."""
        mock_cursor = MagicMock()
        mock_conn = MagicMock()
        mock_conn.cursor.return_value.__enter__ = lambda s: mock_cursor
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        with patch("psycopg2.connect", return_value=mock_conn):
            from services.shared.database import check_database_health

            check_database_health()

        mock_conn.close.assert_called_once()

    # ---------------------------------------------------------------------------
    # check_database_health — failure paths
    # ---------------------------------------------------------------------------

    def test_raises_when_connection_fails(self, db_env):
        """check_database_health() propagates OperationalError from get_connection."""
        with patch("psycopg2.connect", side_effect=OperationalError("unreachable")):
            from services.shared.database import check_database_health

            with pytest.raises(OperationalError):
                check_database_health()

    def test_closes_connection_even_when_query_fails(self, db_env):
        """check_database_health() closes the connection even if SELECT 1 raises."""
        mock_cursor = MagicMock()
        mock_cursor.execute.side_effect = Exception("query error")
        mock_conn = MagicMock()
        mock_conn.cursor.return_value.__enter__ = lambda s: mock_cursor
        mock_conn.cursor.return_value.__exit__ = MagicMock(return_value=False)

        with patch("psycopg2.connect", return_value=mock_conn):
            from services.shared.database import check_database_health

            with pytest.raises(Exception, match="query error"):
                check_database_health()

        mock_conn.close.assert_called_once()
