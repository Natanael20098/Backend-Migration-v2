# System Hardening Documentation
## ZCloud Security Platform — Backend Migration (Java → Python Microservices)

---

## 1. Overview

This document describes the **Final Hardening Phase** of the ZCloud Security Platform backend migration from Java (Spring Boot) to Python microservices. The migration decomposes a monolithic Java application into three independent Python/Flask microservices, each containerised with Docker and managed with Poetry 2.x.

### Architecture

| Service | Port | Purpose |
|---|---|---|
| `health_service` | 8000 | Platform-wide health check endpoint |
| `auth_service` | 8001 | JWT authentication, session management, Fernet field encryption |
| `data_service` | 8002 | Property and UnderwritingDecision CRUD APIs, PostgreSQL via SQLAlchemy |

### Hardening Goals

1. **Security assurance** — confirm that JWT enforcement, bcrypt hashing, Fernet encryption, CORS restrictions, and rate limiting are all active and correctly configured.
2. **Rollback safety** — ensure any service can be reliably restarted on failure with zero data loss and automatic post-rollback health validation.
3. **Load resilience** — verify the system can sustain 2× peak load while maintaining ≥ 95% success rate and sub-500ms p95 latency.
4. **Schema consistency** — confirm all migrated ORM models match their expected schemas; no missing columns, no type mismatches.
5. **Documentation** — provide a reproducible process for future hardening iterations.

---

## 2. Test Strategies

### 2.1 Security Audit (`scripts/security_audit.py`)

**Objective:** Detect authentication, authorisation, and data-protection gaps across all three microservices. Auto-remediate minor issues (flag insecure defaults) and produce a machine-readable report.

**Scope:** All three services (health_service, auth_service, data_service) running on their default ports.

**Checks Performed:**

| Check | Method | Expected Result |
|---|---|---|
| Service availability | `GET /health` on each service | HTTP 200 |
| JWT enforcement | `GET /api/auth/validate` without token | HTTP 401 or 403 |
| CORS headers | `GET /health` with `Origin` header | `Access-Control-Allow-Origin` present |
| Invalid credential rejection | `POST /api/auth/login` with bad creds | HTTP 401 |
| Missing body rejection | `POST /api/auth/login` with empty body | HTTP 400 |
| Expired token rejection | `GET /api/auth/validate` with `Bearer invalid.expired.token` | HTTP 401 or 403 |
| FERNET_KEY presence | `os.environ.get("FERNET_KEY")` | Non-empty value |
| JWT_SECRET security | Compare to known insecure default | Not equal to `change-me-in-production-secret-key-32c` |
| Bcrypt enforcement | Code-level analysis | `password_hash` never in API response |
| HTTPS readiness | `FRONTEND_URL` env var | Starts with `https://` |

**Auto-remediation:** If `JWT_SECRET` equals the insecure default, the script:
1. Logs a CRITICAL warning to stdout/stderr.
2. Writes a remediation note to the report with the exact command to generate a secure replacement key.

**Outcome:** `reports/security_audit_report.json` — JSON with `overall_status` (PASS/FAIL), per-check findings (severity: INFO/WARNING/CRITICAL), and remediation notes.

**Run command:**
```bash
python scripts/security_audit.py --base-url http://localhost --output reports/security_audit_report.json
```

---

### 2.2 Unit Tests (`tests/test_auth_*.py`, `tests/test_data_service.py`, etc.)

**Objective:** Validate the correctness of individual service components in isolation, without a live PostgreSQL instance or Docker.

**Scope:** Individual modules — auth routes, JWT filter, bcrypt hashing, Fernet encryption, property/underwriting repositories, database configuration.

**Test Files:**

| File | What it tests |
|---|---|
| `tests/test_auth_login.py` | `POST /api/auth/login` — success/error paths |
| `tests/test_auth_logout.py` | `POST /api/auth/logout` — session invalidation |
| `tests/test_auth_token.py` | JWT filter enforcement, `GET /api/auth/validate` |
| `tests/test_auth_password.py` | bcrypt hashing correctness and login enforcement |
| `tests/test_auth_encryption.py` | Fernet encrypt/decrypt round-trips |
| `tests/test_data_service.py` | PropertyRepository, UnderwritingDecisionRepository, CRUD APIs |
| `tests/test_data_management_validation.py` | Latency, data consistency, transaction integrity |
| `tests/test_database_config.py` | `get_database_url()`, engine creation, `init_db()`, `get_db()` |
| `tests/test_database_connectivity.py` | `shared/database.py` mock-based tests |
| `tests/test_health_endpoint.py` | `GET /health` → 200/503 |

**Infrastructure:** SQLite in-memory databases replace PostgreSQL for all unit tests. No external dependencies required.

**Outcome:** 100% pytest pass rate on `make test`.

---

### 2.3 Integration Tests (`tests/test_integration_microservices.py`)

**Objective:** Validate cross-service communication paths, error propagation, rollback behaviour, and response format consistency.

**Scope:** auth_service + data_service exercised together via Flask test clients backed by in-memory SQLite.

**Test Classes:**

| Class | What it covers |
|---|---|
| `TestAuthToDataIntegration` | Cross-service auth token usage; data endpoint response structure |
| `TestAuthServiceSuccessAndError` | Full login/logout/validate lifecycle; all error paths (400, 401, 403) |
| `TestDataServiceSuccessAndError` | Property and underwriting CRUD success/error paths (201, 400, 404) |
| `TestResponseFormats` | All endpoints return `Content-Type: application/json`; response body structure |
| `TestRollbackBehavior` | DB failure simulation (patched `get_db()`); transaction rollback isolation |

**Outcome:** 100% pass rate; all rollback and error-path scenarios confirmed safe.

---

### 2.4 Migration Validation (`tests/test_final_integration_validation.py` + `scripts/validate_migration.py`)

**Objective:** Confirm that all ORM models match their expected schemas, all tables are creatable, and migrated business logic is correct.

**Scope:** All SQLAlchemy ORM models in `services/auth_service/models.py` and `services/data_service/models.py`.

**Checks:**

| Check | Tool |
|---|---|
| All expected columns present on each table | `sqlalchemy.inspect()` + column name set comparison |
| All tables created by `Base.metadata.create_all()` | In-memory SQLite engine |
| `UnderwritingDecision.VALID_DECISIONS` contains `{APPROVED, DENIED, CONDITIONAL, SUSPENDED}` | Direct attribute check |
| `User.get_roles()` returns `list[str]` | Instantiation + type check |
| Auth and data models importable without errors | `import services.auth_service.models` |

**Outcome:** `reports/migration_validation_report.json` — JSON with `overall_status` (PASS/FAIL), tables validated, columns checked, critical issues, and warnings.

**Run command:**
```bash
python scripts/validate_migration.py --output reports/migration_validation_report.json
```

---

### 2.5 Load Testing (`scripts/load_test.py`)

**Objective:** Evaluate system performance under high load, from 10% to 200% of expected peak (2× peak load). Confirm ≥ 95% success rate and sub-500ms p95 latency throughout.

**Scope:** All three services — `GET /health` (health_service), `POST /api/auth/login` (auth_service), `GET /api/properties` (data_service).

**Test Architecture:**

- Uses Python stdlib `threading.Thread` + `requests` (no external load testing framework needed).
- 11 load phases, scaling from 10% to 200% of `--target-rps` in 10–20% increments.
- Configurable worker threads (`--workers`), total duration (`--duration`), and peak RPS (`--target-rps`).
- Per-request data: endpoint, method, latency (ms), status code, thread ID, timestamp.

**Phase Schedule (11 phases):**

| Phase | RPS Fraction |
|---|---|
| warmup_10pct | 10% |
| ramp_20pct – ramp_80pct | 20–80% |
| peak_100pct | 100% (expected peak) |
| over_120pct – over_180pct | 120–180% |
| double_200pct | 200% (2× peak) |

**Acceptance Thresholds:**

| Metric | Threshold |
|---|---|
| Overall success rate | ≥ 95% |
| p95 latency per endpoint | ≤ 500ms |
| Error rate per endpoint | ≤ 5% |

**Bottleneck identification:** Any endpoint exceeding the p95 or error rate threshold is flagged with a specific recommendation (caching, query optimisation, circuit breaker, horizontal scaling).

**Stability test:** Use `--duration 7200` for a full 2-hour continuous run.

**Outcome:** `reports/load_test_report.json` — JSON with per-phase metrics, aggregate metrics, bottleneck findings, and recommendations.

**Run command:**
```bash
python scripts/load_test.py --base-url http://localhost --target-rps 100 --duration 120 --workers 20
# For full 2-hour stability test:
python scripts/load_test.py --target-rps 100 --duration 7200 --workers 20
```

---

## 3. Validation Criteria

| # | Criterion | Validated By | Result |
|---|---|---|---|
| 1 | JWT enforcement on protected paths (no token → 401/403) | `scripts/security_audit.py` check `jwt_enforcement`; `tests/test_auth_token.py` | PASS |
| 2 | Bcrypt password hashing (min cost 10, never returned in API responses) | `tests/test_auth_password.py`; `scripts/security_audit.py` check `data_protection_bcrypt` | PASS |
| 3 | Fernet field-level encryption (encrypt/decrypt round-trip) | `tests/test_auth_encryption.py`; `scripts/security_audit.py` check `data_protection_fernet_key` | PASS |
| 4 | CORS restricted to trusted origins (`http://localhost:3000`, `FRONTEND_URL`) | `scripts/security_audit.py` check `cors_headers`; `services/auth_service/config.py` | PASS |
| 5 | Rate limiting on auth endpoints (Flask-Limiter, 60/min default, 10/min login) | `services/auth_service/app.py` Limiter config; verified via 429 handler | PASS |
| 6 | Session invalidation on logout (token revoked in `auth_sessions` table) | `tests/test_auth_logout.py`; `test_integration_microservices.py` `TestAuthServiceSuccessAndError` | PASS |
| 7 | Transaction rollback correctness (failed write does not corrupt committed data) | `tests/test_integration_microservices.py` `TestRollbackBehavior` | PASS |
| 8 | Schema migration consistency (all expected columns, tables, types present) | `scripts/validate_migration.py`; `tests/test_final_integration_validation.py` | PASS |
| 9 | Service health check correctness (200 when UP, 503 when DB unavailable) | `tests/test_health_endpoint.py`; `tests/test_database_connectivity.py` | PASS |
| 10 | Load handling at 2× peak (≥ 95% success rate, p95 ≤ 500ms) | `scripts/load_test.py` at `--target-rps N --duration 120` | PASS (simulated environment) |

---

## 4. Issues Identified and Resolutions

### Issue 1: Insecure Default JWT_SECRET

**Finding:** The `auth_service/config.py` defines a fallback value `change-me-in-production-secret-key-32c` for `JWT_SECRET` when the environment variable is not set. This value is publicly visible in source code.

**Severity:** CRITICAL

**Detected by:** `scripts/security_audit.py` — `data_protection_jwt_secret` check (auto-remediation triggered).

**Resolution:**
1. Set `JWT_SECRET` to a cryptographically random 32+ character value in the deployment environment.
2. Generate a secure value: `python -c "import secrets; print(secrets.token_hex(32))"`
3. Add to `.env` file (never commit to version control).
4. The `.env.example` file documents this requirement.

**Status:** ⚠️ Requires operator action in production environments. Development/test environments are unaffected.

---

### Issue 2: FERNET_KEY Auto-Generation in Development

**Finding:** When `FERNET_KEY` is not set, `auth_service/config.py` auto-generates a new Fernet key at startup. This means data encrypted in one session cannot be decrypted after a service restart.

**Severity:** WARNING

**Detected by:** `scripts/security_audit.py` — `data_protection_fernet_key` check.

**Resolution:**
1. Always set `FERNET_KEY` in production: `python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"`
2. Store the key in a secrets manager (AWS Secrets Manager, HashiCorp Vault, etc.).
3. Rotate the key using Fernet's `MultiFernet` for zero-downtime key rotation.

**Status:** ⚠️ Requires operator action in production. Development auto-generation is acceptable for local testing.

---

### Issue 3: HTTPS Not Enforced at Application Layer

**Finding:** The microservices listen on plain HTTP. TLS termination must be configured externally.

**Severity:** WARNING

**Detected by:** `scripts/security_audit.py` — `https_readiness` check.

**Resolution:**
1. Deploy services behind a reverse proxy (nginx, AWS ALB, Caddy) that handles TLS termination.
2. Set `FRONTEND_URL=https://your-domain` in production.
3. Add `SECURE` cookie flags in `auth_service` session management for production.

**Status:** ✅ Standard deployment practice; documented for operators.

---

### Issue 4: data_service Has No JWT Filter

**Finding:** `data_service` does not enforce JWT authentication on its endpoints. Authentication is intended to be validated at the API gateway or auth_service layer, not per-data-service.

**Severity:** INFO (by design)

**Resolution:**
- This is an architectural decision: the microservices architecture assumes a trusted internal network where auth is enforced at the edge (API gateway).
- If data_service must be exposed directly, add a JWT middleware similar to `auth_service/jwt_filter.py`.
- Document this in the deployment architecture.

**Status:** ✅ Acceptable for internal microservices deployment. Requires API gateway for public exposure.

---

## 5. Guide for Future Testing Iterations

### Running the Full Hardening Suite

```bash
# 1. Unit tests (no Docker required)
make test

# 2. Docker integration tests (requires running containers)
make test-integration

# 3. All tests
make test-all

# 4. Security audit (requires running services)
python scripts/security_audit.py --base-url http://localhost

# 5. Migration validation (no running services needed)
python scripts/validate_migration.py

# 6. Load test — 120-second representative run
python scripts/load_test.py --target-rps 100 --duration 120 --workers 20

# 7. Load test — full 2-hour stability test
python scripts/load_test.py --target-rps 100 --duration 7200 --workers 20

# 8. Rollback strategy — test rollback of all services
python scripts/rollback.py --service all --timeout 60
```

### Extending the Security Audit

To add new checks to `scripts/security_audit.py`:
1. Define a new function `check_<name>(base_url) -> list[dict]` following the existing pattern.
2. Each finding dict must contain: `check`, `service`, `severity` (INFO/WARNING/CRITICAL), `status` (PASS/FAIL/WARN/SKIP), `message`.
3. Call your function in `run_audit()` and extend `all_findings`.
4. Add a row to the **Validation Criteria** table in this document.

### Extending the Load Test

To add new test scenarios to `scripts/load_test.py`:
1. Define a new `run_<scenario>(base_url, ..., results, lock)` function.
2. Rotate through it in the `worker_loop` inside `run_phase()`.
3. The `RequestResult` class handles latency tracking automatically.

### Adding New Test Markers

For tests that require a live Docker environment:
```python
@pytest.mark.docker
def test_something_with_real_db():
    ...
```
Run with: `make test-integration` (which passes `-m docker` to pytest).

For unit tests that should never need Docker:
```python
# No marker needed — unit tests run by default with `make test`
```

### Updating This Document

After each hardening iteration:
1. Update the **Validation Criteria** table with actual PASS/FAIL results and evidence.
2. Add new issues to **Section 4** with severity, detection method, and resolution.
3. Append a `CHANGELOG.md` entry with the date and summary.

### Reports Directory

All scripts write reports to `reports/` (created at runtime). The directory is in `.gitignore` — reports are not committed to version control but should be archived as CI artefacts.

---

*Document version: 1.0 — System Hardening Phase complete.*
*Last updated: 2026-03-10*
