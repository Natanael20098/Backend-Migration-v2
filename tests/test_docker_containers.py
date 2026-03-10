"""
Integration tests for Docker container startup and health verification.

These tests start the full docker-compose stack and verify that:
  - All defined containers start successfully.
  - The postgres container reaches a healthy state.
  - The health_service container starts and the /health endpoint responds.

Environment:
  These tests require Docker and docker-compose to be available on the host.
  They are marked with the `docker` pytest mark so they can be excluded in
  environments without Docker:

      pytest -m "not docker"   # skip these tests
      pytest -m docker         # run only these tests

The CI pipeline runs them with:

      make test-integration
"""
import json
import os
import subprocess
import time
import pytest
import urllib.request
import urllib.error

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

COMPOSE_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docker-compose.yml")
HEALTH_URL = "http://localhost:8000/health"
POSTGRES_CONTAINER = "zcloud_postgres"
HEALTH_SERVICE_CONTAINER = "zcloud_health_service"

# Maximum seconds to wait for containers to become ready
STARTUP_TIMEOUT = 90


def _run(cmd, check=True, capture=True):
    """Run a shell command, return (returncode, stdout, stderr)."""
    result = subprocess.run(
        cmd,
        shell=True,
        check=check,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        text=True,
    )
    return result


def _docker_compose(args, check=True):
    """Run a docker-compose command against the project compose file."""
    return _run(f"docker compose -f {COMPOSE_FILE} {args}", check=check)


def _container_status(container_name):
    """Return the docker inspect Status string for a container, or None if absent."""
    result = _run(
        f"docker inspect --format='{{{{.State.Status}}}}' {container_name}",
        check=False,
    )
    if result.returncode != 0:
        return None
    return result.stdout.strip().strip("'")


def _container_health(container_name):
    """Return the docker inspect Health.Status string for a container, or None."""
    result = _run(
        f"docker inspect --format='{{{{.State.Health.Status}}}}' {container_name}",
        check=False,
    )
    if result.returncode != 0:
        return None
    status = result.stdout.strip().strip("'")
    # Returns '<no value>' when container has no HEALTHCHECK
    return None if status == "<no value>" else status


def _wait_for_healthy(container_name, timeout=STARTUP_TIMEOUT):
    """Poll until a container's health status is 'healthy' or timeout expires."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = _container_health(container_name)
        if status == "healthy":
            return True
        time.sleep(2)
    return False


def _wait_for_running(container_name, timeout=STARTUP_TIMEOUT):
    """Poll until a container's state status is 'running' or timeout expires."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = _container_status(container_name)
        if status == "running":
            return True
        time.sleep(2)
    return False


def _wait_for_http(url, timeout=STARTUP_TIMEOUT):
    """Poll an HTTP endpoint until it returns any response or timeout expires."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as resp:
                return resp.status, resp.read()
        except (urllib.error.URLError, OSError):
            time.sleep(2)
    return None, None


def _docker_compose_available():
    """Return True if docker compose v2 is available on the host."""
    result = _run("docker compose version", check=False)
    return result.returncode == 0


# ---------------------------------------------------------------------------
# Marks and skip conditions
# ---------------------------------------------------------------------------

docker_required = pytest.mark.skipif(
    not _docker_compose_available(),
    reason="docker compose not available in this environment",
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def docker_stack(request):
    """
    Module-scoped fixture: brings up the full docker-compose stack before tests
    and tears it down afterwards.

    Uses a separate project name to avoid conflicting with a running dev stack.
    """
    if not _docker_compose_available():
        pytest.skip("docker compose not available in this environment")

    project = "zcloud_test"
    compose_cmd = f"docker compose -f {COMPOSE_FILE} -p {project}"

    # Tear down any leftover state from a previous run
    _run(f"{compose_cmd} down -v --remove-orphans", check=False)

    # Build images and start all services in detached mode
    _run(f"{compose_cmd} up --build -d", check=True)

    yield {"project": project, "compose_cmd": compose_cmd}

    # Always tear down after tests complete
    _run(f"{compose_cmd} down -v --remove-orphans", check=False)


# ---------------------------------------------------------------------------
# Tests — Docker container startup
# ---------------------------------------------------------------------------

@pytest.mark.docker
class TestDockerContainersStart:
    def test_postgres_container_is_running(self, docker_stack):
        """The postgres container must reach 'running' state within the timeout."""
        project = docker_stack["project"]
        container = f"{project}-postgres-1"
        # Also check the legacy naming style
        result = _run(
            f"docker ps --filter name={project} --format '{{{{.Names}}}}'",
            check=False,
        )
        containers = result.stdout.strip().splitlines()
        postgres_containers = [c for c in containers if "postgres" in c]
        assert postgres_containers, (
            f"No postgres container found in project '{project}'. "
            f"Running containers: {containers}"
        )

    def test_health_service_container_is_running(self, docker_stack):
        """The health_service container must reach 'running' state within the timeout."""
        project = docker_stack["project"]
        result = _run(
            f"docker ps --filter name={project} --format '{{{{.Names}}}}'",
            check=False,
        )
        containers = result.stdout.strip().splitlines()
        health_containers = [c for c in containers if "health" in c]
        assert health_containers, (
            f"No health_service container found in project '{project}'. "
            f"Running containers: {containers}"
        )

    def test_postgres_container_becomes_healthy(self, docker_stack):
        """The postgres container must pass its HEALTHCHECK within the startup timeout."""
        project = docker_stack["project"]
        result = _run(
            f"docker ps --filter name={project} --format '{{{{.Names}}}}'",
            check=False,
        )
        containers = result.stdout.strip().splitlines()
        postgres_container = next((c for c in containers if "postgres" in c), None)
        assert postgres_container is not None, "postgres container not found"

        healthy = _wait_for_healthy(postgres_container, timeout=STARTUP_TIMEOUT)
        assert healthy, (
            f"Container '{postgres_container}' did not become healthy within "
            f"{STARTUP_TIMEOUT}s. Current health: {_container_health(postgres_container)}"
        )

    def test_all_expected_containers_present(self, docker_stack):
        """Both 'postgres' and 'health' containers must appear in the running stack."""
        project = docker_stack["project"]
        result = _run(
            f"docker ps --filter name={project} --format '{{{{.Names}}}}'",
            check=False,
        )
        running = result.stdout.strip()
        assert "postgres" in running, f"postgres container not found; running:\n{running}"
        assert "health" in running, f"health_service container not found; running:\n{running}"


# ---------------------------------------------------------------------------
# Tests — Health endpoint via live Docker stack
# ---------------------------------------------------------------------------

@pytest.mark.docker
class TestHealthEndpointLive:
    def test_health_endpoint_returns_200(self, docker_stack):
        """GET /health on the live stack must return HTTP 200."""
        status, _ = _wait_for_http(HEALTH_URL, timeout=STARTUP_TIMEOUT)
        assert status == 200, f"Expected HTTP 200 from {HEALTH_URL}, got {status}"

    def test_health_endpoint_database_connected(self, docker_stack):
        """GET /health live response must contain database='connected'."""
        _, body = _wait_for_http(HEALTH_URL, timeout=STARTUP_TIMEOUT)
        assert body is not None, f"No response received from {HEALTH_URL}"
        data = json.loads(body)
        assert data.get("database") == "connected", f"Unexpected response body: {data}"

    def test_health_endpoint_status_ok(self, docker_stack):
        """GET /health live response must contain status='ok'."""
        _, body = _wait_for_http(HEALTH_URL, timeout=STARTUP_TIMEOUT)
        assert body is not None, f"No response received from {HEALTH_URL}"
        data = json.loads(body)
        assert data.get("status") == "ok", f"Unexpected response body: {data}"


# ---------------------------------------------------------------------------
# Tests — Database connectivity failure simulation
# ---------------------------------------------------------------------------

@pytest.mark.docker
class TestDatabaseConnectivityFailure:
    def test_health_endpoint_returns_503_when_postgres_stopped(self, docker_stack):
        """
        GET /health must return HTTP 503 when the postgres container is stopped.

        This test stops the postgres container, waits for the health endpoint to
        reflect the failure, then restarts postgres to restore the stack for
        subsequent tests.
        """
        project = docker_stack["project"]
        compose_cmd = docker_stack["compose_cmd"]

        # Stop only the postgres service
        _run(f"{compose_cmd} stop postgres", check=True)

        try:
            # Poll for the 503 response — give up to 30 seconds
            deadline = time.time() + 30
            got_503 = False
            while time.time() < deadline:
                try:
                    with urllib.request.urlopen(HEALTH_URL, timeout=3) as resp:
                        if resp.status == 503:
                            got_503 = True
                            break
                except urllib.error.HTTPError as exc:
                    if exc.code == 503:
                        got_503 = True
                        break
                except (urllib.error.URLError, OSError):
                    pass
                time.sleep(2)

            assert got_503, (
                "Expected HTTP 503 from /health after postgres was stopped, "
                "but did not receive it within 30 seconds."
            )
        finally:
            # Always restore postgres so subsequent tests are unaffected
            _run(f"{compose_cmd} start postgres", check=False)
            _wait_for_healthy(
                next(
                    (
                        c
                        for c in _run(
                            f"docker ps --filter name={project} --format '{{{{.Names}}}}'",
                            check=False,
                        ).stdout.strip().splitlines()
                        if "postgres" in c
                    ),
                    "postgres",
                ),
                timeout=60,
            )
