# ZCloud Security Platform — Python Microservices Backend

A Python-based microservices backend for the ZCloud Security Platform, migrated from Java (Spring Boot). Dependencies are managed with [Poetry 2.x](https://python-poetry.org/) and services are containerised with Docker.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Python 3.11 |
| Web Framework | Flask 3.x |
| Database | PostgreSQL 15 |
| DB Driver | psycopg2 / SQLAlchemy 2.x |
| Auth | PyJWT 2.x, bcrypt 4.x |
| Security | Flask-CORS, Flask-Limiter, cryptography (Fernet) |
| Dependency Manager | Poetry 2.x |
| Container Runtime | Docker / docker-compose |

---

## Project Structure

```
.
├── docker-compose.yml          # Orchestration for all microservices + PostgreSQL
├── pyproject.toml              # Poetry project & dependency manifest
├── .env.example                # Environment variable template
├── services/
│   ├── shared/
│   │   └── database.py         # PostgreSQL connection + health check logic
│   ├── health_service/
│   │   ├── app.py              # Flask app — exposes GET /health
│   │   └── Dockerfile          # Python 3.11 container image
│   └── auth_service/
│       ├── app.py              # Flask app factory — wires all components
│       ├── auth_routes.py      # POST /api/auth/login, /logout; GET /api/auth/validate
│       ├── jwt_filter.py       # JWT authentication filter (before_request hook)
│       ├── config.py           # Security config: JWT, CORS, rate limiting, encryption
│       ├── models.py           # SQLAlchemy models: User, Session
│       ├── db.py               # SQLAlchemy engine + session factory
│       └── Dockerfile          # Python 3.11 production container (gunicorn)
```

---

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) ≥ 24.x and [Docker Compose](https://docs.docker.com/compose/install/) plugin
- [Python](https://www.python.org/downloads/) 3.8+ (for local development)
- [Poetry](https://python-poetry.org/docs/#installation) 2.x

---

## 1. Docker Setup — Step-by-Step

### 1.1 Clone the repository

```bash
git clone <repository-url>
cd <repository-root>
```

### 1.2 Create your environment file

```bash
cp .env.example .env
# Edit .env with your preferred values if needed
```

The default `.env.example` values work out-of-the-box with `docker-compose.yml`.

### 1.3 Start all services

```bash
docker-compose up --build
```

This will:
1. Pull/build the `postgres:15-alpine` image and start PostgreSQL.
2. Build the `health_service` container from `services/health_service/Dockerfile`.
3. Start `health_service` only after PostgreSQL passes its health check.

### 1.4 Start a single service

```bash
# Start only PostgreSQL
docker-compose up postgres

# Start only the health_service (also starts postgres due to depends_on)
docker-compose up health_service
```

### 1.5 Verify the health endpoint

```bash
curl http://localhost:8000/health
# Expected: {"database":"connected","status":"ok"}  HTTP 200
```

### 1.6 Verify the auth service

```bash
# Health check
curl http://localhost:8001/health
# Expected: {"service":"auth_service","status":"ok"}  HTTP 200
```

### 1.6 Stop all containers

```bash
docker-compose down
# To also remove the persistent volume:
docker-compose down -v
```

---

---

## 7. Auth Service

The `auth_service` implements the **Security Operations** bounded context migrated from the Java `AuthController`, `SecurityConfig`, and `JwtAuthenticationFilter`.

### 7.1 Endpoints

| Method | Path | Auth required | Description |
|--------|------|--------------|-------------|
| `POST` | `/api/auth/login` | No | Authenticate and receive a JWT |
| `POST` | `/api/auth/logout` | Bearer token | Invalidate current session |
| `GET` | `/api/auth/validate` | Bearer token | Confirm token is valid and active |
| `GET` | `/health` | No | Service health check |

### 7.2 Login

```bash
curl -X POST http://localhost:8001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "s3cr3t"}'

# 200 OK
{
  "token": "<JWT>",
  "user_id": "<uuid>",
  "roles": ["ROLE_USER"]
}

# 401 Unauthorized
{ "error": "Invalid username or password" }
```

### 7.3 Logout

```bash
curl -X POST http://localhost:8001/api/auth/logout \
  -H "Authorization: Bearer <JWT>"

# 200 OK
{ "message": "Logged out successfully" }
```

### 7.4 Validate token

```bash
curl http://localhost:8001/api/auth/validate \
  -H "Authorization: Bearer <JWT>"

# 200 OK
{ "valid": true, "subject": "<uuid>", "roles": ["ROLE_USER"] }

# 401 / 403 on invalid / expired token
```

### 7.5 JWT authentication filter

All non-public endpoints require a valid `Authorization: Bearer <token>` header.

- **403** — token missing, expired, or invalid.
- Claims are attached to `flask.g.user_claims` for downstream handlers.
- Every attempt (success and failure) is logged at `INFO` / `WARNING` level.

### 7.6 Security configuration

| Setting | Value / Source |
|---------|----------------|
| JWT algorithm | HS256 |
| JWT expiry | `JWT_EXPIRATION_SECONDS` env var (default 86400 s) |
| JWT secret | `JWT_SECRET` env var |
| CORS allowed origins | `http://localhost:3000` + `FRONTEND_URL` env var |
| Rate limit (global) | `RATE_LIMIT_DEFAULT` env var (default `60 per minute`) |
| Rate limit (login) | `RATE_LIMIT_LOGIN` env var (default `10 per minute`) |
| Sensitive field encryption | Fernet symmetric encryption, key via `FERNET_KEY` env var |

### 7.7 Environment variables for auth_service

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET` | HMAC-SHA256 signing secret (min 32 chars) | `change-me-in-production-secret-key-32c` |
| `JWT_EXPIRATION_SECONDS` | Token lifetime in seconds | `86400` |
| `FRONTEND_URL` | Additional trusted CORS origin | `http://localhost:3000` |
| `FERNET_KEY` | Fernet key for field encryption | *(auto-generated if unset)* |
| `RATE_LIMIT_DEFAULT` | Global rate limit (Flask-Limiter format) | `60 per minute` |
| `RATE_LIMIT_LOGIN` | Login endpoint rate limit | `10 per minute` |
| `DB_HOST` | PostgreSQL host | `postgres` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `zcloud` |
| `DB_USER` | Database user | `zcloud` |
| `DB_PASSWORD` | Database password | `zcloud_secret` |

---

## 2. Poetry Setup — Dependency Management

### 2.1 Install Poetry 2.x

```bash
pip install "poetry>=2.0,<3.0"
# Verify installation
poetry --version
```

### 2.2 Install project dependencies

```bash
# Creates a virtual environment and installs all dependencies
poetry install
```

Poetry automatically creates an isolated virtual environment. To confirm:

```bash
poetry env info
```

### 2.3 Activate the virtual environment

```bash
poetry shell
```

### 2.4 Add a new dependency

```bash
# Add a runtime dependency
poetry add <package-name>

# Add a development-only dependency
poetry add --group dev <package-name>
```

### 2.5 Update dependencies

```bash
# Update all packages to their latest allowed versions
poetry update

# Update a specific package
poetry update <package-name>
```

### 2.6 Remove a dependency

```bash
poetry remove <package-name>
```

---

## 3. PostgreSQL Connectivity

### 3.1 Environment variables

All database credentials are supplied via environment variables. **Never hard-code credentials.**

| Variable | Description | Default (docker-compose) |
|---|---|---|
| `DB_HOST` | PostgreSQL hostname | `postgres` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `zcloud` |
| `DB_USER` | Database user | `zcloud` |
| `DB_PASSWORD` | Database password | `zcloud_secret` |

For local development outside Docker, copy `.env.example` to `.env` and adjust `DB_HOST` to `localhost`.

### 3.2 Running the health check locally

```bash
# Activate the virtual environment
poetry shell

# Export variables (or use a tool like python-dotenv)
export DB_HOST=localhost DB_PORT=5432 DB_NAME=zcloud DB_USER=zcloud DB_PASSWORD=zcloud_secret

# Run the Flask app
python -m services.health_service.app

# In another terminal:
curl http://localhost:8000/health
```

### 3.3 Health check endpoint reference

```
GET /health

200 OK:
  {"status": "ok", "database": "connected"}

503 Service Unavailable (database unreachable):
  {"status": "error", "database": "unreachable", "detail": "<error message>"}
```

---

## 4. Troubleshooting

### `psycopg2.OperationalError: could not connect to server`

- Confirm PostgreSQL is running: `docker-compose ps`
- Confirm `DB_HOST` matches the service name in `docker-compose.yml` (`postgres` inside Docker, `localhost` outside).
- Confirm PostgreSQL port `5432` is not blocked by a firewall or already in use by another process.

### `docker-compose up` hangs at health check

- The `postgres` container waits up to 50 seconds (10 s × 5 retries) for PostgreSQL to accept connections.
- Check logs: `docker-compose logs postgres`

### `poetry install` fails with Python version mismatch

- Confirm the active Python version: `python --version`
- The project requires Python ^3.8. Use `pyenv` to manage multiple Python versions.
- Tell Poetry which interpreter to use: `poetry env use python3.8`

### `ModuleNotFoundError: No module named 'services'`

- Ensure you are running commands from the repository root directory.
- When running without Docker, activate the Poetry shell: `poetry shell`

### Container rebuild after dependency changes

```bash
# Force a rebuild after changing pyproject.toml
docker-compose up --build
```

### Viewing service logs

```bash
docker-compose logs -f health_service
docker-compose logs -f postgres
```

---

## 5. Service Networking

All containers share the `zcloud_network` bridge network defined in `docker-compose.yml`. Services can reach each other by their service name (e.g., `health_service` can connect to `postgres` using host `postgres`).

---

## 6. Testing

### 6.1 Test structure

```
tests/
├── conftest.py                    # Shared fixtures (Flask client, env-var patches)
├── test_database_connectivity.py  # Unit tests for services/shared/database.py
├── test_health_endpoint.py        # Flask test-client tests for GET /health
└── test_docker_containers.py      # Docker integration tests (require Docker daemon)
```

### 6.2 Install dev dependencies

```bash
poetry install
```

### 6.3 Run unit tests (no Docker required)

```bash
make test
# or directly:
poetry run pytest -m "not docker"
```

### 6.4 Run Docker integration tests

These tests start the full `docker-compose` stack, probe the live `/health` endpoint,
and simulate a database failure by stopping the postgres container.

```bash
make test-integration
# or directly:
poetry run pytest -m docker -v
```

### 6.5 Run the full suite

```bash
make test-all
```

### 6.6 CI pipeline

The `Makefile` provides the canonical entry points used by CI:

| Make target       | What it runs                                       |
|-------------------|----------------------------------------------------|
| `make test`       | Unit tests only — safe for any environment         |
| `make test-integration` | Docker integration tests — requires Docker   |
| `make test-all`   | Full suite including Docker integration tests      |

In a CI environment without Docker (e.g., a plain Python runner), use `make test`
to execute only the unit tests. On a runner with Docker-in-Docker, use `make test-all`.

---

---

## 8. System Hardening Scripts

The `scripts/` package contains standalone tools for the hardening phase. All scripts require `poetry shell` (or an activated virtual environment with dependencies installed).

### 8.1 Security Audit

Audits all microservices for authentication, authorisation, and data-protection compliance. Auto-flags insecure defaults and writes a JSON report.

```bash
python scripts/security_audit.py --base-url http://localhost --output reports/security_audit_report.json
```

### 8.2 Rollback Strategy

Performs automated failure detection and service rollback via `docker compose restart`, with post-rollback health validation and zero data loss guarantee.

```bash
# Roll back all services
python scripts/rollback.py --service all --timeout 60

# Roll back a specific service
python scripts/rollback.py --service auth_service --timeout 60
```

### 8.3 Load Testing

Evaluates system performance under 2× peak load using concurrent threads across 11 scaling phases. Generates a performance metrics report.

```bash
# 120-second representative run
python scripts/load_test.py --target-rps 100 --duration 120 --workers 20

# Full 2-hour stability test
python scripts/load_test.py --target-rps 100 --duration 7200 --workers 20
```

### 8.4 Migration Validation

Validates all ORM model schemas against expected column sets, VALID_DECISIONS values, and method contracts.

```bash
python scripts/validate_migration.py --output reports/migration_validation_report.json
```

### 8.5 Reports

All scripts write JSON reports to `reports/` (created at runtime). The directory is `.gitignore`d — archive reports as CI artefacts.

See `doc/system_hardening.md` for full details on the hardening process, test strategies, validation criteria, and identified issues.

---

## Contributing

1. Fork the repository and create a feature branch.
2. Install dependencies: `poetry install`
3. Run unit tests: `make test`
4. Run integration tests (requires Docker): `make test-integration`
5. Submit a pull request with a clear description of changes.
