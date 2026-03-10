.PHONY: install test test-unit test-integration test-all lint clean

# ---------------------------------------------------------------------------
# Dependency installation
# ---------------------------------------------------------------------------

install:
	poetry install

# ---------------------------------------------------------------------------
# Test targets
# ---------------------------------------------------------------------------

## Run unit tests only (no Docker required — safe for any CI environment)
test-unit:
	poetry run pytest -m "not docker" --cov=services --cov-report=term-missing

## Alias: default test target runs unit tests
test: test-unit

## Run Docker/integration tests only (requires Docker daemon + docker-compose)
test-integration:
	poetry run pytest -m docker -v

## Run the full test suite including Docker integration tests
test-all:
	poetry run pytest --cov=services --cov-report=term-missing

# ---------------------------------------------------------------------------
# Docker helpers
# ---------------------------------------------------------------------------

## Start the full stack in the background
up:
	docker compose up --build -d

## Stop and remove the full stack and its volumes
down:
	docker compose down -v --remove-orphans

## Tail logs for all services
logs:
	docker compose logs -f

## Check the health endpoint on the running stack
health:
	curl -s http://localhost:8000/health | python3 -m json.tool

# ---------------------------------------------------------------------------
# Housekeeping
# ---------------------------------------------------------------------------

clean:
	find . -type d -name "__pycache__" -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete
	rm -rf .coverage htmlcov .pytest_cache
