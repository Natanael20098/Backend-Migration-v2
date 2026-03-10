"""
Integration tests for the health_service Flask application.

Tests the GET /health endpoint for both successful database connectivity
and failure scenarios. Uses the Flask test client — no real DB required.
"""
import json
import pytest
from unittest.mock import patch
from psycopg2 import OperationalError


# ---------------------------------------------------------------------------
# Success cases
# ---------------------------------------------------------------------------

class TestHealthEndpointSuccess:
    def test_returns_200_when_database_connected(self, flask_client):
        """GET /health returns HTTP 200 when the database health check passes."""
        with patch(
            "services.health_service.app.check_database_health",
            return_value=True,
        ):
            response = flask_client.get("/health")

        assert response.status_code == 200

    def test_response_body_status_ok(self, flask_client):
        """GET /health response body contains status='ok' on success."""
        with patch(
            "services.health_service.app.check_database_health",
            return_value=True,
        ):
            response = flask_client.get("/health")

        data = json.loads(response.data)
        assert data["status"] == "ok"

    def test_response_body_database_connected(self, flask_client):
        """GET /health response body contains database='connected' on success."""
        with patch(
            "services.health_service.app.check_database_health",
            return_value=True,
        ):
            response = flask_client.get("/health")

        data = json.loads(response.data)
        assert data["database"] == "connected"

    def test_response_content_type_is_json(self, flask_client):
        """GET /health returns application/json content type."""
        with patch(
            "services.health_service.app.check_database_health",
            return_value=True,
        ):
            response = flask_client.get("/health")

        assert "application/json" in response.content_type


# ---------------------------------------------------------------------------
# Failure cases
# ---------------------------------------------------------------------------

class TestHealthEndpointFailure:
    def test_returns_503_when_database_unreachable(self, flask_client):
        """GET /health returns HTTP 503 when database connectivity fails."""
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError("connection refused"),
        ):
            response = flask_client.get("/health")

        assert response.status_code == 503

    def test_response_body_status_error(self, flask_client):
        """GET /health response body contains status='error' on failure."""
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError("connection refused"),
        ):
            response = flask_client.get("/health")

        data = json.loads(response.data)
        assert data["status"] == "error"

    def test_response_body_database_unreachable(self, flask_client):
        """GET /health response body contains database='unreachable' on failure."""
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError("connection refused"),
        ):
            response = flask_client.get("/health")

        data = json.loads(response.data)
        assert data["database"] == "unreachable"

    def test_response_body_includes_error_detail(self, flask_client):
        """GET /health response body includes 'detail' field describing the error."""
        error_message = "could not connect to server: Connection refused"
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError(error_message),
        ):
            response = flask_client.get("/health")

        data = json.loads(response.data)
        assert "detail" in data

    def test_returns_503_on_generic_exception(self, flask_client):
        """GET /health returns HTTP 503 for any unexpected exception."""
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=RuntimeError("unexpected failure"),
        ):
            response = flask_client.get("/health")

        assert response.status_code == 503

    def test_503_response_is_json(self, flask_client):
        """GET /health returns application/json even on failure."""
        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError("timeout"),
        ):
            response = flask_client.get("/health")

        assert "application/json" in response.content_type

    def test_logs_error_on_failure(self, flask_client, caplog):
        """GET /health logs the error when the database is unreachable."""
        import logging

        with patch(
            "services.health_service.app.check_database_health",
            side_effect=OperationalError("timeout"),
        ):
            with caplog.at_level(logging.ERROR, logger="services.health_service.app"):
                flask_client.get("/health")

        assert any("Health check failed" in record.message for record in caplog.records)
