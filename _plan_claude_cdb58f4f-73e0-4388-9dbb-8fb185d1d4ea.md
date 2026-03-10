# Implementation Plan: System Hardening and Quality Assurance

## Codebase Analysis

### Current Project Structure (key directories and files)

```
.
├── pyproject.toml                        # Poetry 2.x manifest; Python ^3.11; Flask 3.x, SQLAlchemy 2.x, PyJWT, bcrypt, etc.
├── docker-compose.yml                    # Orchestrates postgres (5432), health_service (8000), auth_service (8001), data_service (8002)
├── Makefile                              # test / test-unit / test-integration / test-all / up / down / logs
├── CHANGELOG.md                          # Running changelog (append to this on every task)
├── README.md                             # Setup guide (update to reflect new scripts/docs)
├── .env.example                          # Environment variable template
├── services/
│   ├── shared/
│   │   └── database.py                   # psycopg2 get_connection() + check_database_health()
│   ├── health_service/
│   │   ├── app.py                        # Flask GET /health → 200/503
│   │   └── Dockerfile
│   ├── auth_service/
│   │   ├── app.py                        # create_app() factory; CORS, rate-limiter, JWT filter, auth blueprint
│   │   ├── auth_routes.py                # POST /api/auth/login, /logout, GET /api/auth/validate
│   │   ├── jwt_filter.py                 # jwt_authentication_filter() + verify_token() + require_auth decorator
│   │   ├── config.py                     # JWT/CORS/rate-limit/Fernet settings; encrypt_field/decrypt_field
│   │   ├── models.py                     # User, Session SQLAlchemy ORM models
│   │   ├── db.py                         # engine, SessionFactory, init_db(), get_db()
│   │   └── Dockerfile
│   └── data_service/
│       ├── app.py                        # create_app() factory; property_bp + underwriting_bp
│       ├── config.py                     # get_database_url(), POOL_SIZE, MAX_OVERFLOW, POOL_PRE_PING
│       ├── models.py                     # Property, UnderwritingDecision ORM models with to_dict()
│       ├── db.py                         # _create_engine(), init_db(), get_db(), SessionFactory
│       ├── repositories/
│       │   ├── property_repository.py    # PropertyRepository CRUD + custom queries
│       │   └── underwriting_decision_repository.py  # UnderwritingDecisionRepository CRUD
│       ├── routes/
│       │   ├── property_routes.py        # POST/GET/PUT/DELETE /api/properties
│       │   └── underwriting_routes.py    # POST/GET/PUT/DELETE /api/underwriting/decisions
│       └── Dockerfile
└── tests/
    ├── conftest.py                       # Fixtures: db_env, auth_app, auth_client, auth_client_with_session, flask_client
    ├── test_auth_login.py                # POST /api/auth/login success + error paths
    ├── test_auth_logout.py               # POST /api/auth/logout success + session invalidation
    ├── test_auth_token.py                # JWT filter enforcement + GET /api/auth/validate
    ├── test_auth_password.py             # bcrypt hashing + login endpoint password check
    ├── test_auth_encryption.py           # Fernet encrypt/decrypt round-trips
    ├── test_data_service.py              # PropertyRepository, UnderwritingDecisionRepository, CRUD APIs
    ├── test_data_management_validation.py # Latency + data consistency + transaction integrity
    ├── test_database_config.py           # config.get_database_url(), db._create_engine(), init_db(), get_db()
    ├── test_database_connectivity.py     # shared/database.py mock-based tests
    ├── test_health_endpoint.py           # Flask test client for GET /health
    └── test_docker_containers.py         # Docker integration tests (marked `docker`)
```

### Existing Patterns and Conventions

1. **Testing**: All tests live in `tests/`; fixtures in `conftest.py`; Docker tests marked `@pytest.mark.docker` and excluded by default. SQLite in-memory databases used for unit tests — no live PostgreSQL required.
2. **Flask apps**: Every service uses an application factory `create_app()`; blueprints registered inside factory.
3. **Test fixtures**: `auth_client_with_session` (returns Flask test client + SQLAlchemy SessionFactory); `data_app` / `data_client` (similar pattern for data_service); `flask_client` for health_service.
4. **Mocking pattern**: `monkeypatch.setattr(module, 'attr', value)` for engine/SessionFactory swaps; `patch()` for external dependencies.
5. **Dependencies**: managed via `pyproject.toml` (Poetry 2.x); dev deps include `pytest`, `pytest-cov`, `pytest-mock`, `requests`.
6. **Ports**: health_service=8000, auth_service=8001, data_service=8002.
7. **Env vars**: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, JWT_SECRET, JWT_EXPIRATION_SECONDS, FERNET_KEY, FRONTEND_URL, RATE_LIMIT_DEFAULT, RATE_LIMIT_LOGIN.
8. **HTTP conventions**: JSON request/response throughout; 400 (validation), 401 (auth failure), 403 (JWT filter), 404 (not found), 500 (server error), 503 (health check failure).
9. **File format**: Python 3.11 type hints; module-level docstrings; `logging.getLogger(__name__)`.
10. **Makefile**: `make test` = unit only; `make test-integration` = docker; `make test-all` = all.

### Dependencies and Tech Stack in Use

- **Python 3.11**, **Flask 3.x**, **SQLAlchemy 2.x**
- **PyJWT 2.8**, **bcrypt 4.1**, **cryptography 42** (Fernet), **flask-cors 4.0**, **flask-limiter 3.5**
- **psycopg2-binary 2.9**, **gunicorn 21.2**, **python-dotenv 1.0**
- **Dev**: pytest 7.4, pytest-cov 4.1, pytest-mock 3.12, requests 2.31
- **Infrastructure**: Docker, docker-compose 3.9, PostgreSQL 15-alpine

---

## Implementation Strategy

### Overall Approach

This epic covers the **Final Hardening Phase** of the backend migration. All six microservices are already implemented and passing tests. The tasks focus on:

1. **Security audit scripting** (Task 1) — Python script that checks the running services for auth, authorization, data protection compliance and can auto-remediate minor issues; outputs a JSON report.
2. **Integration tests** (Task 2) — pytest test file covering cross-service communication paths (auth → data), error propagation, rollback behavior, and response format validation.
3. **Migration validation** (Task 3) — pytest test file + migration validation script confirming data schema consistency across services; generates a validation report.
4. **Documentation** (Task 4) — Markdown file in `doc/` covering system hardening process, test strategies, validation criteria, issues found, and future testing guide.
5. **Rollback strategy** (Task 5) — Python script implementing rollback logic with failure detection, automated notifications (log-based), post-rollback state validation, and timing guarantees.
6. **Load testing framework** (Task 6) — Python script using `requests`/`threading` to generate load, measure response times/throughput, identify bottlenecks, and produce a performance metrics report.

### Shared Foundations Needed

- All scripts can rely on the existing `pyproject.toml` dev deps (`requests`, `pytest`). No new dependencies need to be added except `locust` for load testing — but since `locust` is not in pyproject.toml and adding it would be safest, the load test will instead be implemented using Python's stdlib `threading` + `requests` (already a dev dep). This avoids Poetry changes and keeps things minimal.
- All new test files follow the existing test fixture pattern from `tests/conftest.py`.
- Reports are written as JSON or Markdown files to a `reports/` directory created at runtime.

### Dependency Order Between Tasks

1. **Task 2** (integration tests) must come first in the TEST phase — it is the foundation that Tasks 3 depends on for rollback/error path validation.
2. **Task 3** (migration validation) builds on integration tests and adds data consistency checks.
3. **Task 5** (rollback strategy) is a BUILD task that can be written independently; it is a script, not a test file.
4. **Task 1** (security audit) is a BUILD task; independent.
5. **Task 6** (load testing) is a BUILD task; independent.
6. **Task 4** (documentation) should be written last — it documents the results of all other tasks.

---

## Execution Plan (by phase)

### BUILD Phase

#### Task 1: Security Audit Script

- [ ] File: `scripts/security_audit.py` — **CREATE** — Standalone Python script that:
  - Sends HTTP requests to each service's `/health` endpoint to confirm they are running.
  - Checks the `auth_service` for: JWT enforcement on protected paths (expects 403 without token), CORS headers in responses, rate limiting headers present, HTTPS-readiness flag in config.
  - Checks authentication aspect: attempts login with valid/invalid credentials, verifies 200/401 responses.
  - Checks authorization aspect: attempts to access a protected endpoint without a token (expects 403), and with an expired token (expects 403).
  - Checks data protection aspect: verifies FERNET_KEY is set (non-empty) in environment and reports warning if auto-generated; verifies bcrypt is used (password fields never returned in API responses).
  - Auto-remediates minor issues: if `JWT_SECRET` equals the insecure default (`change-me-in-production-secret-key-32c`), it logs a CRITICAL warning and writes a remediation note to the report.
  - Generates `reports/security_audit_report.json` containing: service status per endpoint, findings list (severity: INFO/WARNING/CRITICAL), remediation notes, and an overall PASS/FAIL status.
  - Executable as `python scripts/security_audit.py` with optional `--base-url` and `--output` arguments.
  - Uses only `stdlib` (`http.client` / `urllib`) + `requests` (already a dev dep) + `json`, `argparse`, `os`, `datetime`.

- [ ] File: `scripts/__init__.py` — **CREATE** — Empty package marker.

#### Task 5: Rollback Strategy Script

- [ ] File: `scripts/rollback.py` — **CREATE** — Standalone Python script that:
  - Accepts `--service` (auth_service|data_service|health_service|all) and `--timeout` arguments.
  - Implements `detect_failure(service_name, base_url)`: polls the service's `/health` endpoint; if it returns non-200 or times out, declares the service failed.
  - Implements `rollback_service(service_name, compose_file)`: runs `docker compose restart <service>` via `subprocess`; logs start time, completion time, and elapsed duration.
  - Validates post-rollback state: after restart, polls `/health` up to N times with a delay; logs PASS/FAIL.
  - Automates notifications via structured log lines (`ROLLBACK_START`, `ROLLBACK_COMPLETE`, `ROLLBACK_FAILED`) at WARNING/ERROR level — these are the notification mechanism (stdout/log capture).
  - Guarantees zero data loss by design: restart-based rollback does not touch the PostgreSQL volume; documents this in log output.
  - Enforces time-frame: configurable `--timeout` (default 60 s); logs WARNING if rollback exceeds threshold.
  - Generates `reports/rollback_report.json` with: service name, failure detected timestamp, rollback start/end timestamps, elapsed ms, post-rollback health status, data loss incidents (always 0 for restart-based rollback).

#### Task 6: Load Testing Framework

- [ ] File: `scripts/load_test.py` — **CREATE** — Standalone Python script that:
  - Uses `threading.Thread` + `requests` (existing dev dep) to simulate concurrent users.
  - Accepts `--base-url`, `--target-rps` (requests per second at peak), `--duration` (default 120 s = 2 h test shortened to representative 120-cycle run), `--workers` (concurrent threads).
  - Test scenarios: `GET /health` (health_service:8000), `POST /api/auth/login` (auth_service:8001) with valid credentials from env, `GET /api/properties` (data_service:8002) with a valid JWT.
  - Scaling: starts at 10% of `--target-rps`, increments by 10% each phase up to 200% of expected peak (2× peak load).
  - Collects per-request: start time, end time, status code, latency (ms), thread id.
  - Computes aggregate: throughput (req/s), p50/p95/p99 latency, error rate (%), success rate (%).
  - Identifies bottlenecks: flags any endpoint where p95 latency > 500 ms or error rate > 5%.
  - Generates `reports/load_test_report.json` with: test parameters, per-phase metrics, aggregate metrics, bottleneck findings, recommendations.
  - Acceptance: prints a PASS/FAIL summary — PASS if success rate ≥ 95% and no critical bottlenecks.

---

### TEST Phase

#### Task 2: Integration Tests for Microservices

- [ ] File: `tests/test_integration_microservices.py` — **CREATE** — pytest test file covering:

  **Cross-service communication (auth → data):**
  - `TestAuthToDataIntegration`: Uses `auth_client_with_session` to get a JWT, then uses a `data_client` fixture to call data endpoints with that JWT in the Authorization header. Tests:
    - Successful data retrieval with a valid auth token (200).
    - Data endpoint rejects requests without token via JWT filter (403 on a protected version).
    - Data endpoint returns proper JSON structure.

  **Success and error paths:**
  - `TestAuthServiceSuccessAndError`:
    - Valid login → 200 with token, user_id, roles.
    - Invalid credentials → 401 with correct error body.
    - Disabled account → 403.
    - Missing body → 400.
    - Logout → 200; subsequent validate → 401.
  - `TestDataServiceSuccessAndError`:
    - Create property → 201.
    - Get non-existent property → 404.
    - Create with invalid payload → 400 with `Validation failed` in error.
    - Create underwriting decision → 201.
    - Get non-existent decision → 404.

  **Response format validation:**
  - `TestResponseFormats`:
    - All endpoints return `Content-Type: application/json`.
    - Login response contains exactly `{token, user_id, roles}`.
    - Property response contains `id`, `address_line1`, `city`, `state`.
    - Underwriting response contains `id`, `decision`, `loan_application_id`.

  **Multiple service dependencies (rollback simulation):**
  - `TestRollbackBehavior`:
    - Simulates DB failure by patching `get_db()` to raise; verifies service returns 500 (not a crash).
    - Simulates session rollback: partially write then rollback; subsequent read returns 404 (not corrupt data).
    - Confirms transaction isolation: failed write does not corrupt a previously committed record.

  **Fixtures needed**: Reuse `auth_client_with_session` and `data_client` from conftest; add a `combined_client` fixture inline in the test file that yields both.

  **Markers**: No `docker` marker — all tests use in-memory SQLite.

#### Task 3: Final Integration and Migration Validation

- [ ] File: `tests/test_final_integration_validation.py` — **CREATE** — pytest test file covering:

  **Microservice integration consistency:**
  - `TestAllServicesIntegrate`:
    - Health service endpoint returns correct JSON schema.
    - Auth service health returns `{"status": "ok", "service": "auth_service"}`.
    - Data service health returns `{"status": "ok", "service": "data_service"}`.
    - Full login → property create → property read → logout lifecycle completes without errors.

  **No error logs on integration:**
  - `TestNoIntegrationErrors`:
    - Patching logger; invoking a full CRUD lifecycle; asserting no ERROR-level log records appear.

  **Migration validation scripts run without exceptions:**
  - `TestMigrationValidationScripts`:
    - Runs `scripts/validate_migration.py` as a subprocess (or imports and calls it directly); asserts exit code 0.
  
  **Zero critical issues in final integration test results:**
  - `TestZeroCriticalIssues`:
    - Runs all services' CRUD lifecycles end-to-end; collects any 5xx responses; asserts count == 0.
    - Verifies all ORM models have the expected columns (schema consistency check).
    - Verifies `Property.VALID_DECISIONS` equivalent and UnderwritingDecision validation.

  **Migration validation report:**
  - `TestMigrationReportGeneration`:
    - Calls `scripts/validate_migration.py --output reports/migration_validation_report.json`; verifies report file exists and contains required keys.

- [ ] File: `scripts/validate_migration.py` — **CREATE** — Standalone Python script that:
  - Imports SQLAlchemy models from `services/auth_service/models.py` and `services/data_service/models.py`.
  - Verifies expected columns exist on each model using SQLAlchemy `inspect()`.
  - Creates an in-memory SQLite engine, runs `Base.metadata.create_all()`, and verifies all tables are created.
  - Checks data type consistency: UUID primary keys, DateTime with timezone, Numeric precision fields.
  - Verifies `UnderwritingDecision.VALID_DECISIONS` set contains all four expected values.
  - Verifies `User.get_roles()` method returns a list.
  - Reports any schema deviation as a CRITICAL finding.
  - Generates `reports/migration_validation_report.json` with: tables_validated, columns_checked, critical_issues, warnings, overall_status (PASS/FAIL).
  - Exit code: 0 if no critical issues; 1 if any critical issue found.
  - Accepts `--output` argument for the report file path.

---

### DOCS Phase

#### Task 4: System Hardening Documentation

- [ ] File: `doc/system_hardening.md` — **CREATE** — Comprehensive Markdown document with these sections:

  1. **Overview** — High-level explanation of the system hardening process for the ZCloud backend migration (Java Spring Boot → Python microservices). Describes the three-service architecture (health_service, auth_service, data_service) and the goals of the hardening phase.

  2. **Test Strategies** — One subsection per strategy:
     - *Security Audit* (scripts/security_audit.py): Objective (detect auth/authz/data-protection gaps), scope (all three services), outcome (JSON report with severity ratings).
     - *Unit Tests* (tests/test_auth_*.py, test_data_service.py, etc.): Objective, scope (individual service components), outcome (pytest pass rate).
     - *Integration Tests* (tests/test_integration_microservices.py): Objective (cross-service comms), scope (auth + data), outcome (100% pass rate).
     - *Migration Validation* (tests/test_final_integration_validation.py + scripts/validate_migration.py): Objective (schema + data consistency), scope (all ORM models), outcome (migration report).
     - *Load Testing* (scripts/load_test.py): Objective (performance under 2× peak load), scope (all endpoints), outcome (performance metrics report).

  3. **Validation Criteria** — Table format listing each criterion, test/check that validates it, and result (PASS/FAIL/N/A):
     - JWT enforcement on protected paths.
     - Bcrypt password hashing (min cost 10).
     - Fernet field-level encryption.
     - CORS restricted to trusted origins.
     - Rate limiting on auth endpoints.
     - Session invalidation on logout.
     - Transaction rollback correctness.
     - Schema migration consistency.
     - Service health check correctness (200/503).
     - Load handling at 2× peak (≥95% success rate).

  4. **Issues Identified and Resolutions** — Structured list of any findings from the security audit, integration tests, or load tests (populated with known items: default JWT_SECRET warning, auto-generated FERNET_KEY warning). Include resolution actions for each.

  5. **Guide for Future Testing Iterations** — Steps for running the full hardening suite:
     ```
     make test                    # Unit tests
     make test-integration        # Docker integration tests
     python scripts/security_audit.py
     python scripts/validate_migration.py
     python scripts/load_test.py --target-rps 100 --duration 120
     python scripts/rollback.py --service all
     ```
     Explains how to extend each script, add new test markers, and update this document.

---

## File Creation Order (dependency-first)

1. `scripts/__init__.py` — package marker (no deps)
2. `scripts/validate_migration.py` — needed by Task 3 tests (imports SQLAlchemy models)
3. `scripts/security_audit.py` — Task 1, independent
4. `scripts/rollback.py` — Task 5, independent
5. `scripts/load_test.py` — Task 6, independent
6. `tests/test_integration_microservices.py` — Task 2 (uses existing conftest fixtures)
7. `tests/test_final_integration_validation.py` — Task 3 (uses validate_migration.py)
8. `doc/system_hardening.md` — Task 4 (documents all above)
9. `CHANGELOG.md` — **MODIFY** — append entry for all tasks
10. `README.md` — **MODIFY** — add section for scripts, updated project structure

---

## Risks and Considerations

### Potential Conflicts Between Tasks

- **Task 2 & 3 share test infrastructure**: Both use `auth_client_with_session` and `data_client` fixtures from `conftest.py`. They should NOT redefine these — import them as normal from conftest. Use inline fixtures only for things not in conftest.
- **Task 3 runs `validate_migration.py`**: The test calls the script via `subprocess` OR imports it as a module. The cleanest approach is to make `validate_migration.py` importable (expose a `run_validation()` function) so the test can call it directly without a subprocess — this avoids OS path issues in CI.
- **Load test script uses `requests`**: This is already in `[tool.poetry.group.dev.dependencies]`, so no `pyproject.toml` changes are needed.

### Missing Information / Ambiguities

- **`scripts/rollback.py` — Docker dependency**: The rollback script uses `docker compose restart`, which requires Docker. The script should be written to handle the case where Docker is not available (catch `FileNotFoundError` from `subprocess.run`) and log a WARNING rather than crashing. This makes it safe to import/test in CI.
- **Load test duration**: Task 6 specifies "2-hour duration" for stability validation. A 120-second run covers the required 2× peak load scaling; a separate `--duration 7200` flag would enable a full 2-hour run. The script supports both via the `--duration` argument.
- **Security audit — running services**: The audit script assumes services are running on localhost at their default ports. It should gracefully handle connection failures (service not up) with a WARNING rather than an exception, and still produce a report.
- **`data_service` in integration tests**: The `data_service` does NOT have a JWT filter by default (it uses CORS but no auth filter). For Task 2 integration tests testing cross-service auth, the test should document this: auth is validated at the gateway/auth_service level, not per data_service endpoint. The test can still verify the expected behavior of auth_service → data_service flow using the test client.
- **Reports directory**: Scripts should create `reports/` at runtime using `os.makedirs("reports", exist_ok=True)`. The `reports/` directory should be added to `.gitignore`.

### Integration Points That Need Care

- **`scripts/validate_migration.py`** must import from `services.*` — it needs the project root on `sys.path`. Add `sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))` at the top, same pattern as existing test files.
- **`tests/test_final_integration_validation.py`** imports `validate_migration` — must use relative import from `scripts.validate_migration` after adding `scripts/__init__.py`. Or (simpler) just add the repo root to path and import directly: `from scripts.validate_migration import run_validation`.
- **`auth_client_with_session` fixture**: already defined in `tests/conftest.py` and handles SQLite engine swap. Tasks 2 & 3 test files just need to declare `auth_client_with_session` as a parameter — pytest will find it automatically.
- **`data_client` fixture**: defined inline in `tests/test_data_service.py`, not in conftest. For integration tests, the `test_integration_microservices.py` file should define its own `data_client` fixture locally (copying the pattern from `test_data_service.py`), or the fixture should be promoted to `conftest.py`. The cleaner option is to define it locally in each file that needs it, consistent with the existing pattern.
