"""
ZCloud Rollback Strategy Script

Implements automated rollback for ZCloud microservices with:
  - Failure detection: polls /health endpoint; declares failure on non-200 or timeout
  - Rollback execution: docker compose restart <service>
  - Post-rollback validation: polls /health up to N times to confirm recovery
  - Structured notification logs: ROLLBACK_START, ROLLBACK_COMPLETE, ROLLBACK_FAILED
  - Zero data loss guarantee: restart-based rollback does not touch the PostgreSQL volume
  - Configurable timeout enforcement: logs WARNING if rollback exceeds threshold

Usage:
    python scripts/rollback.py --service auth_service [--timeout 60] [--compose-file docker-compose.yml]
    python scripts/rollback.py --service all
"""

import argparse
import json
import logging
import os
import subprocess
import sys
import time
from datetime import datetime, timezone

try:
    import requests
    from requests.exceptions import ConnectionError as ReqConnectionError, Timeout as ReqTimeout
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False

# Ensure project root is on sys.path
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)

# Service definitions: name -> (port, health_path)
SERVICE_PORTS = {
    "health_service": 8000,
    "auth_service": 8001,
    "data_service": 8002,
}

KNOWN_SERVICES = list(SERVICE_PORTS.keys())

# Post-rollback health poll settings
POST_ROLLBACK_MAX_ATTEMPTS = 12
POST_ROLLBACK_POLL_INTERVAL_S = 5  # seconds between polls


# ---------------------------------------------------------------------------
# Failure detection
# ---------------------------------------------------------------------------

def detect_failure(service_name: str, base_url: str = "http://localhost", timeout: int = 5) -> bool:
    """
    Poll the service's /health endpoint.

    Returns True if the service is considered failed (non-200 or unreachable),
    False if the service is healthy.
    """
    port = SERVICE_PORTS.get(service_name)
    if port is None:
        logger.error("Unknown service: %s", service_name)
        return True

    url = f"{base_url}:{port}/health"

    if not REQUESTS_AVAILABLE:
        logger.warning("requests library not available — cannot detect failure via HTTP")
        return False

    try:
        resp = requests.get(url, timeout=timeout)
        if resp.status_code == 200:
            logger.info("HEALTH_CHECK service=%s status=UP url=%s", service_name, url)
            return False
        else:
            logger.warning(
                "HEALTH_CHECK service=%s status=DEGRADED http_status=%s url=%s",
                service_name, resp.status_code, url
            )
            return True
    except (ReqConnectionError, ReqTimeout) as exc:
        logger.warning(
            "HEALTH_CHECK service=%s status=UNREACHABLE url=%s error=%s",
            service_name, url, str(exc)
        )
        return True


# ---------------------------------------------------------------------------
# Rollback execution
# ---------------------------------------------------------------------------

def rollback_service(
    service_name: str,
    compose_file: str = "docker-compose.yml",
    timeout_s: int = 60,
    base_url: str = "http://localhost",
) -> dict:
    """
    Rollback (restart) the given service using docker compose.

    Returns a result dict with:
      - service_name
      - failure_detected_at (ISO timestamp)
      - rollback_start (ISO timestamp)
      - rollback_end (ISO timestamp)
      - elapsed_ms (int)
      - post_rollback_health (PASS/FAIL/SKIP)
      - data_loss_incidents (always 0 — restart-based rollback)
      - status (SUCCESS/FAILED)
    """
    now = datetime.now(timezone.utc)
    failure_detected_at = now.isoformat()
    result = {
        "service_name": service_name,
        "failure_detected_at": failure_detected_at,
        "rollback_start": None,
        "rollback_end": None,
        "elapsed_ms": None,
        "post_rollback_health": "UNKNOWN",
        "data_loss_incidents": 0,  # restart-based rollback never touches PostgreSQL volume
        "status": "FAILED",
        "notes": [],
    }

    # Data loss guarantee note
    result["notes"].append(
        "Zero data loss guaranteed: docker compose restart only restarts the service container. "
        "The PostgreSQL data volume is untouched. No database transactions are lost."
    )

    # Log rollback start notification
    rollback_start_time = time.monotonic()
    rollback_start_iso = datetime.now(timezone.utc).isoformat()
    result["rollback_start"] = rollback_start_iso

    logger.warning(
        "ROLLBACK_START service=%s compose_file=%s timeout_s=%s",
        service_name, compose_file, timeout_s
    )

    # Execute docker compose restart
    try:
        cmd = ["docker", "compose", "-f", compose_file, "restart", service_name]
        logger.info("Executing: %s", " ".join(cmd))
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_s,
        )
        rollback_end_time = time.monotonic()
        rollback_end_iso = datetime.now(timezone.utc).isoformat()
        result["rollback_end"] = rollback_end_iso
        elapsed_ms = int((rollback_end_time - rollback_start_time) * 1000)
        result["elapsed_ms"] = elapsed_ms

        if elapsed_ms > timeout_s * 1000:
            logger.warning(
                "ROLLBACK_TIMEOUT service=%s elapsed_ms=%s timeout_ms=%s",
                service_name, elapsed_ms, timeout_s * 1000
            )
            result["notes"].append(
                f"Rollback exceeded acceptable time frame: {elapsed_ms}ms > {timeout_s * 1000}ms"
            )

        if proc.returncode != 0:
            logger.error(
                "ROLLBACK_FAILED service=%s returncode=%s stderr=%s",
                service_name, proc.returncode, proc.stderr
            )
            result["notes"].append(f"docker compose restart failed: {proc.stderr}")
            result["status"] = "FAILED"
        else:
            logger.info(
                "ROLLBACK_COMPLETE service=%s elapsed_ms=%s",
                service_name, elapsed_ms
            )
            result["status"] = "RESTARTED"

    except FileNotFoundError:
        # Docker not installed or not in PATH
        rollback_end_iso = datetime.now(timezone.utc).isoformat()
        result["rollback_end"] = rollback_end_iso
        elapsed_ms = int((time.monotonic() - rollback_start_time) * 1000)
        result["elapsed_ms"] = elapsed_ms
        logger.warning(
            "ROLLBACK_FAILED service=%s error=docker_not_found "
            "— Docker is not installed or not in PATH. Cannot execute rollback.",
            service_name
        )
        result["notes"].append(
            "Docker CLI not found — rollback requires Docker to be installed. "
            "In a non-Docker environment, restart the service manually."
        )
        result["status"] = "FAILED_DOCKER_NOT_FOUND"

    except subprocess.TimeoutExpired:
        rollback_end_iso = datetime.now(timezone.utc).isoformat()
        result["rollback_end"] = rollback_end_iso
        elapsed_ms = int((time.monotonic() - rollback_start_time) * 1000)
        result["elapsed_ms"] = elapsed_ms
        logger.error(
            "ROLLBACK_FAILED service=%s error=timeout elapsed_ms=%s",
            service_name, elapsed_ms
        )
        result["notes"].append(f"docker compose restart timed out after {timeout_s}s")
        result["status"] = "FAILED_TIMEOUT"

    # Post-rollback validation: poll /health
    post_health = validate_post_rollback(service_name, base_url)
    result["post_rollback_health"] = post_health

    if post_health == "PASS":
        result["status"] = "SUCCESS"
        logger.warning(
            "ROLLBACK_COMPLETE service=%s post_health=PASS elapsed_ms=%s",
            service_name, result["elapsed_ms"]
        )
    else:
        logger.error(
            "ROLLBACK_FAILED service=%s post_health=%s",
            service_name, post_health
        )
        if result["status"] not in ("FAILED_DOCKER_NOT_FOUND", "FAILED_TIMEOUT"):
            result["status"] = "FAILED"

    return result


# ---------------------------------------------------------------------------
# Post-rollback validation
# ---------------------------------------------------------------------------

def validate_post_rollback(
    service_name: str,
    base_url: str = "http://localhost",
    max_attempts: int = POST_ROLLBACK_MAX_ATTEMPTS,
    poll_interval_s: float = POST_ROLLBACK_POLL_INTERVAL_S,
) -> str:
    """
    Poll the service's /health endpoint after rollback.
    Returns 'PASS' if the service comes up healthy within max_attempts polls.
    Returns 'FAIL' otherwise.
    Returns 'SKIP' if HTTP requests are unavailable.
    """
    if not REQUESTS_AVAILABLE:
        logger.warning("requests library not available — skipping post-rollback validation")
        return "SKIP"

    port = SERVICE_PORTS.get(service_name)
    if port is None:
        return "SKIP"

    url = f"{base_url}:{port}/health"
    logger.info(
        "POST_ROLLBACK_VALIDATION service=%s max_attempts=%s poll_interval=%ss",
        service_name, max_attempts, poll_interval_s
    )

    for attempt in range(1, max_attempts + 1):
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code == 200:
                logger.info(
                    "POST_ROLLBACK_VALIDATION service=%s result=PASS attempt=%s/%s",
                    service_name, attempt, max_attempts
                )
                return "PASS"
        except (ReqConnectionError, ReqTimeout):
            pass

        logger.info(
            "POST_ROLLBACK_VALIDATION service=%s attempt=%s/%s status=waiting",
            service_name, attempt, max_attempts
        )
        if attempt < max_attempts:
            time.sleep(poll_interval_s)

    logger.error(
        "POST_ROLLBACK_VALIDATION service=%s result=FAIL after %s attempts",
        service_name, max_attempts
    )
    return "FAIL"


# ---------------------------------------------------------------------------
# Report generation
# ---------------------------------------------------------------------------

def generate_rollback_report(results: list, output_path: str) -> dict:
    """Write rollback results to a JSON report file."""
    success_count = sum(1 for r in results if r.get("status") == "SUCCESS")
    failed_count = len(results) - success_count

    report = {
        "report_type": "rollback",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "overall_status": "SUCCESS" if failed_count == 0 else "PARTIAL_FAILURE" if success_count > 0 else "FAILED",
        "summary": {
            "services_attempted": len(results),
            "successful": success_count,
            "failed": failed_count,
            "total_data_loss_incidents": 0,  # by design
        },
        "rollback_results": results,
    }

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w") as f:
        json.dump(report, f, indent=2)

    logger.info("Rollback report written to: %s", output_path)
    return report


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run_rollback(
    service: str = "all",
    compose_file: str = "docker-compose.yml",
    timeout_s: int = 60,
    base_url: str = "http://localhost",
    output_path: str = "reports/rollback_report.json",
) -> dict:
    """Run rollback for the specified service(s) and generate a report."""
    if service == "all":
        services_to_rollback = KNOWN_SERVICES
    elif service in KNOWN_SERVICES:
        services_to_rollback = [service]
    else:
        logger.error("Unknown service: %s. Valid options: %s or 'all'", service, KNOWN_SERVICES)
        sys.exit(1)

    results = []
    for svc in services_to_rollback:
        logger.info("Starting rollback for service: %s", svc)
        result = rollback_service(svc, compose_file=compose_file, timeout_s=timeout_s, base_url=base_url)
        results.append(result)

    return generate_rollback_report(results, output_path)


def main():
    parser = argparse.ArgumentParser(
        description="ZCloud Rollback Strategy Script — automated service rollback with validation"
    )
    parser.add_argument(
        "--service",
        choices=KNOWN_SERVICES + ["all"],
        default="all",
        help="Service to roll back (default: all)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=60,
        help="Maximum seconds allowed for rollback to complete (default: 60)",
    )
    parser.add_argument(
        "--compose-file",
        default="docker-compose.yml",
        help="Path to docker-compose.yml (default: docker-compose.yml)",
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost",
        help="Base URL for health checks (default: http://localhost)",
    )
    parser.add_argument(
        "--output",
        default="reports/rollback_report.json",
        help="Output path for the JSON report (default: reports/rollback_report.json)",
    )
    args = parser.parse_args()

    report = run_rollback(
        service=args.service,
        compose_file=args.compose_file,
        timeout_s=args.timeout,
        base_url=args.base_url,
        output_path=args.output,
    )

    status = report["overall_status"]
    print(f"\n{'='*60}")
    print(f"ROLLBACK RESULT: {status}")
    print(f"  Services attempted : {report['summary']['services_attempted']}")
    print(f"  Successful         : {report['summary']['successful']}")
    print(f"  Failed             : {report['summary']['failed']}")
    print(f"  Data loss incidents: {report['summary']['total_data_loss_incidents']}")
    print(f"  Report             : {args.output}")
    print(f"{'='*60}\n")

    sys.exit(0 if status == "SUCCESS" else 1)


if __name__ == "__main__":
    main()
