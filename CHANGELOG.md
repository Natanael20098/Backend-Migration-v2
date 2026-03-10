# Changelog

## [Unreleased] – 2025-05-13 10:30

### Added
- `tests/__init__.py`: Package marker for the integration test suite.
- `tests/conftest.py`: Shared pytest fixtures — Flask test client, `db_env` env-var patching, `db_env_missing_host` misconfiguration fixture.
- `tests/test_database_connectivity.py`: Unit tests for `services/shared/database.py` — covers `get_connection` and `check_database_health` success and failure paths, credential forwarding, default port, missing env var, error logging, and guaranteed connection close.
- `tests/test_health_endpoint.py`: Flask test-client integration tests for `GET /health` — covers HTTP 200/503 responses, response body shape, content type, error detail field, and error logging.
- `tests/test_docker_containers.py`: Docker integration tests (marked `docker`) — verify all containers start, postgres reaches healthy state, live `/health` returns 200 with correct JSON, and `/health` returns 503 when postgres is stopped.
- `Makefile`: `test` / `test-unit` / `test-integration` / `test-all` targets for local and CI execution; Docker helper targets.
- `pyproject.toml`: Added `pytest-mock` dev dependency; added `[tool.pytest.ini_options]` with testpaths, default flags, and the `docker` marker definition.

## [Unreleased] – 2025-01-31 12:00

### Added
- `pyproject.toml`: Poetry 2.x project manifest with Flask, psycopg2-binary, gunicorn, python-dotenv, and dev dependencies (pytest, pytest-cov, requests).
- `docker-compose.yml`: Orchestrates `postgres` (PostgreSQL 15-alpine) and `health_service` containers on a shared `zcloud_network` bridge network, with a postgres health check and `depends_on` wiring.
- `services/health_service/Dockerfile`: Python 3.8-slim image; installs system dependencies (`libpq-dev`, `gcc`), Poetry 2.x, and project packages via `poetry install --only main`.
- `services/health_service/app.py`: Flask application exposing `GET /health` — queries PostgreSQL via `check_database_health()` and returns `200 OK` on success or `503` on failure.
- `services/shared/database.py`: `get_connection()` and `check_database_health()` using psycopg2; reads credentials from environment variables; logs `OperationalError` with full connection details.
- `.env.example`: Environment variable template for database credentials.
- `.dockerignore`: Excludes non-essential files from the Docker build context.
- `.gitignore`: Covers Python, Poetry, Java legacy artefacts, and Node modules.
- `README.md`: Step-by-step guide for Docker setup, Poetry dependency management, PostgreSQL connectivity, and troubleshooting tips for new contributors.
