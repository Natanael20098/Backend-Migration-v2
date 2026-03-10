# Changelog

## [Unreleased] – 2026-03-10 14:30

### Added — System Hardening and Quality Assurance (Final Phase)

#### Task 1: Security Audit Script
- `scripts/__init__.py`: Package marker for the scripts package.
- `scripts/security_audit.py`: Standalone security audit script that checks all three microservices for auth/authz/data-protection compliance. Performs: service availability checks, JWT enforcement validation, CORS header verification, authentication flow testing (valid/invalid credentials), authorisation enforcement (no-token, expired-token), data protection checks (FERNET_KEY, JWT_SECRET, bcrypt), and HTTPS readiness. Auto-remediates minor issues (logs CRITICAL + writes remediation note for insecure default JWT_SECRET). Generates `reports/security_audit_report.json` with severity-rated findings and PASS/FAIL overall status. Accepts `--base-url` and `--output` arguments.

#### Task 2: Rollback Strategy Script
- `scripts/rollback.py`: Automated rollback strategy script implementing failure detection (`detect_failure()` via `/health` polling), rollback execution (`docker compose restart` via subprocess), and post-rollback health validation (polls /health up to 12 times). Emits structured notification log lines: `ROLLBACK_START`, `ROLLBACK_COMPLETE`, `ROLLBACK_FAILED`. Guarantees zero data loss by design (restart-based rollback never touches the PostgreSQL volume). Enforces configurable timeout with WARNING on breach. Handles missing Docker gracefully. Generates `reports/rollback_report.json`. Accepts `--service` (all/individual), `--timeout`, `--compose-file`, `--base-url`, `--output` arguments.

#### Task 3: Load Testing Framework
- `scripts/load_test.py`: Load testing framework using `threading.Thread` + `requests`. Tests all three service endpoints across 11 scaling phases from 10% to 200% (2×) of target RPS. Collects per-request latency, status code, thread ID, and timestamps. Computes throughput, p50/p95/p99 latency, success/error rates. Identifies bottlenecks (p95 > 500ms or error rate > 5%) with recommendations. Generates `reports/load_test_report.json`. PASS if success rate ≥ 95% and no critical bottlenecks. Accepts `--base-url`, `--target-rps`, `--duration` (120s default; 7200s for 2-hour stability test), `--workers`, `--output`.

#### Supporting Script
- `scripts/validate_migration.py`: Schema consistency validation script. Imports all SQLAlchemy ORM models, creates in-memory SQLite engines, runs `Base.metadata.create_all()`, and inspects columns against expected sets. Validates `UnderwritingDecision.VALID_DECISIONS` and `User.get_roles()`. Generates `reports/migration_validation_report.json`. Exit code 0 = no critical issues; 1 = critical issues found.

#### Task 4: System Hardening Documentation
- `doc/system_hardening.md`: Comprehensive system hardening documentation with: (1) high-level architecture overview, (2) five test strategy subsections (security audit, unit tests, integration tests, migration validation, load testing) each with objective/scope/outcome, (3) 10-row validation criteria table with PASS/FAIL results, (4) four identified issues with severity and resolution actions, (5) complete guide for running the full hardening suite and extending each script.

### Changed
- `.gitignore`: Added `reports/` directory (generated at runtime, not committed).

## [Unreleased] – 2025-05-14 09:00

### Added — Security Operations Microservice (Phase 1)
- `services/auth_service/__init__.py`: Package marker for the auth service.
- `services/auth_service/config.py`: Security configuration — JWT settings (secret, algorithm, expiry, issuer, audience), CORS allowed origins/methods/headers, rate-limiting defaults, and Fernet symmetric encryption helpers (`encrypt_field`, `decrypt_field`) for sensitive data fields.
- `services/auth_service/models.py`: SQLAlchemy ORM models — `User` (hashed password, roles) and `Session` (JWT token invalidation on logout).
- `services/auth_service/db.py`: SQLAlchemy engine, `SessionFactory`, `init_db()` (idempotent table creation), and `get_db()` session helper.
- `services/auth_service/jwt_filter.py`: JWT authentication filter — `jwt_authentication_filter()` Flask `before_request` hook that verifies Bearer tokens on all protected paths, returns **403** for missing/expired/invalid tokens, attaches claims to `flask.g.user_claims`, and logs all auth attempts. Also exports a `require_auth` decorator and `verify_token()` helper.
- `services/auth_service/auth_routes.py`: Auth Blueprint — `POST /api/auth/login` (accepts `{username, password}`, returns `{token, user_id, roles}` **200**, **400/401/403** on failure); `POST /api/auth/logout` (invalidates session token); `GET /api/auth/validate` (confirms token is active).
- `services/auth_service/app.py`: Flask application factory (`create_app`) — registers CORS (trusted origins only), Flask-Limiter (rate limiting), JWT filter, auth blueprint, health endpoint, and JSON error handlers; calls `init_db()` on startup.
- `services/auth_service/Dockerfile`: Python 3.11-slim production image with gunicorn; installs system deps (`libpq-dev`, `gcc`, `libffi-dev`, `libssl-dev`), Poetry 2.x, and project packages.

### Changed
- `pyproject.toml`: Updated Python requirement to `^3.11`; added runtime dependencies: `PyJWT ^2.8`, `bcrypt ^4.1`, `flask-cors ^4.0`, `flask-limiter ^3.5`, `SQLAlchemy ^2.0`, `cryptography ^42.0`.
- `docker-compose.yml`: Added `auth_service` container — PostgreSQL + auth env vars, port `8001`, `depends_on` postgres health check, restart policy, and container health check.
- `.env.example`: Added auth service environment variables: `JWT_SECRET`, `JWT_EXPIRATION_SECONDS`, `FERNET_KEY`, `FRONTEND_URL`, `RATE_LIMIT_DEFAULT`, `RATE_LIMIT_LOGIN`.
- `README.md`: Updated tech stack table; expanded project structure; added Section 7 documenting the auth service endpoints, JWT filter, security configuration, and all environment variables.

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
