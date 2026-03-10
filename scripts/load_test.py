"""
ZCloud Load Testing Framework

Evaluates system performance under high load using concurrent threads:
  - Scales from 10% to 200% of target RPS (2× peak load)
  - Tests all three services: health_service, auth_service, data_service
  - Collects per-request: latency, status code, thread id, timestamps
  - Computes: throughput, p50/p95/p99 latency, error rate, success rate
  - Identifies bottlenecks: p95 latency > 500ms OR error rate > 5%
  - Validates system stability for the specified duration
  - Generates a JSON performance metrics report
  - Prints PASS/FAIL summary: PASS if success rate ≥ 95% and no critical bottlenecks

Usage:
    python scripts/load_test.py [--base-url http://localhost] [--target-rps 50]
                                [--duration 120] [--workers 10]
                                [--username admin] [--password secret]
                                [--output reports/load_test_report.json]
"""

import argparse
import json
import logging
import math
import os
import statistics
import sys
import threading
import time
from datetime import datetime, timezone
from typing import Optional

try:
    import requests
    from requests.exceptions import RequestException
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

# Performance thresholds
P95_LATENCY_THRESHOLD_MS = 500
ERROR_RATE_THRESHOLD_PCT = 5.0
SUCCESS_RATE_MIN_PCT = 95.0

# Load scaling phases: list of (phase_name, rps_fraction_of_peak)
LOAD_PHASES = [
    ("warmup_10pct", 0.10),
    ("ramp_20pct", 0.20),
    ("ramp_40pct", 0.40),
    ("ramp_60pct", 0.60),
    ("ramp_80pct", 0.80),
    ("peak_100pct", 1.00),
    ("over_120pct", 1.20),
    ("over_140pct", 1.40),
    ("over_160pct", 1.60),
    ("over_180pct", 1.80),
    ("double_200pct", 2.00),
]


# ---------------------------------------------------------------------------
# Request result
# ---------------------------------------------------------------------------

class RequestResult:
    __slots__ = ("endpoint", "method", "status_code", "latency_ms", "start_ts", "thread_id", "error")

    def __init__(self, endpoint: str, method: str, status_code: Optional[int],
                 latency_ms: float, start_ts: float, thread_id: int, error: Optional[str] = None):
        self.endpoint = endpoint
        self.method = method
        self.status_code = status_code
        self.latency_ms = latency_ms
        self.start_ts = start_ts
        self.thread_id = thread_id
        self.error = error

    def is_success(self) -> bool:
        return self.status_code is not None and 200 <= self.status_code < 400


# ---------------------------------------------------------------------------
# Scenario runners
# ---------------------------------------------------------------------------

def run_health_check(base_url: str, port: int = 8000, results: list = None,
                     lock: threading.Lock = None) -> RequestResult:
    """GET /health on health_service."""
    url = f"{base_url}:{port}/health"
    start = time.monotonic()
    ts = time.time()
    try:
        resp = requests.get(url, timeout=5)
        latency_ms = (time.monotonic() - start) * 1000
        r = RequestResult("health_service:/health", "GET", resp.status_code, latency_ms,
                          ts, threading.get_ident())
    except RequestException as exc:
        latency_ms = (time.monotonic() - start) * 1000
        r = RequestResult("health_service:/health", "GET", None, latency_ms,
                          ts, threading.get_ident(), error=str(exc))
    if results is not None and lock is not None:
        with lock:
            results.append(r)
    return r


def run_auth_login(base_url: str, port: int = 8001, username: str = "testuser",
                   password: str = "TestPass123!", results: list = None,
                   lock: threading.Lock = None) -> RequestResult:
    """POST /api/auth/login on auth_service."""
    url = f"{base_url}:{port}/api/auth/login"
    start = time.monotonic()
    ts = time.time()
    try:
        resp = requests.post(url, json={"username": username, "password": password}, timeout=5)
        latency_ms = (time.monotonic() - start) * 1000
        # 401 is expected if credentials are wrong — count as non-error for load test purposes
        r = RequestResult("auth_service:/api/auth/login", "POST", resp.status_code, latency_ms,
                          ts, threading.get_ident())
    except RequestException as exc:
        latency_ms = (time.monotonic() - start) * 1000
        r = RequestResult("auth_service:/api/auth/login", "POST", None, latency_ms,
                          ts, threading.get_ident(), error=str(exc))
    if results is not None and lock is not None:
        with lock:
            results.append(r)
    return r


def run_get_properties(base_url: str, port: int = 8002, token: str = None,
                       results: list = None, lock: threading.Lock = None) -> RequestResult:
    """GET /api/properties on data_service."""
    url = f"{base_url}:{port}/api/properties"
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    start = time.monotonic()
    ts = time.time()
    try:
        resp = requests.get(url, headers=headers, timeout=5)
        latency_ms = (time.monotonic() - start) * 1000
        r = RequestResult("data_service:/api/properties", "GET", resp.status_code, latency_ms,
                          ts, threading.get_ident())
    except RequestException as exc:
        latency_ms = (time.monotonic() - start) * 1000
        r = RequestResult("data_service:/api/properties", "GET", None, latency_ms,
                          ts, threading.get_ident(), error=str(exc))
    if results is not None and lock is not None:
        with lock:
            results.append(r)
    return r


# ---------------------------------------------------------------------------
# Metrics computation
# ---------------------------------------------------------------------------

def compute_metrics(results: list) -> dict:
    """Compute aggregate metrics from a list of RequestResult objects."""
    if not results:
        return {"total_requests": 0, "success_rate_pct": 0.0, "error_rate_pct": 100.0}

    total = len(results)
    success = sum(1 for r in results if r.is_success())
    errors = total - success

    latencies = [r.latency_ms for r in results]
    latencies_sorted = sorted(latencies)

    def percentile(data: list, pct: float) -> float:
        if not data:
            return 0.0
        idx = math.ceil(pct / 100 * len(data)) - 1
        return data[max(0, min(idx, len(data) - 1))]

    # Throughput: requests per second (total / elapsed wall time)
    if len(results) > 1:
        elapsed_s = results[-1].start_ts - results[0].start_ts
        throughput_rps = total / elapsed_s if elapsed_s > 0 else 0.0
    else:
        throughput_rps = 0.0

    # Per-endpoint breakdown
    endpoints: dict = {}
    for r in results:
        ep = r.endpoint
        if ep not in endpoints:
            endpoints[ep] = {"total": 0, "success": 0, "latencies": []}
        endpoints[ep]["total"] += 1
        if r.is_success():
            endpoints[ep]["success"] += 1
        endpoints[ep]["latencies"].append(r.latency_ms)

    endpoint_metrics = {}
    for ep, data in endpoints.items():
        ep_latencies = sorted(data["latencies"])
        ep_error_rate = (data["total"] - data["success"]) / data["total"] * 100
        endpoint_metrics[ep] = {
            "total_requests": data["total"],
            "success_count": data["success"],
            "error_rate_pct": round(ep_error_rate, 2),
            "p50_latency_ms": round(percentile(ep_latencies, 50), 2),
            "p95_latency_ms": round(percentile(ep_latencies, 95), 2),
            "p99_latency_ms": round(percentile(ep_latencies, 99), 2),
            "min_latency_ms": round(min(ep_latencies), 2) if ep_latencies else 0,
            "max_latency_ms": round(max(ep_latencies), 2) if ep_latencies else 0,
        }

    return {
        "total_requests": total,
        "successful_requests": success,
        "failed_requests": errors,
        "success_rate_pct": round(success / total * 100, 2),
        "error_rate_pct": round(errors / total * 100, 2),
        "throughput_rps": round(throughput_rps, 2),
        "p50_latency_ms": round(percentile(latencies_sorted, 50), 2),
        "p95_latency_ms": round(percentile(latencies_sorted, 95), 2),
        "p99_latency_ms": round(percentile(latencies_sorted, 99), 2),
        "min_latency_ms": round(min(latencies), 2),
        "max_latency_ms": round(max(latencies), 2),
        "mean_latency_ms": round(statistics.mean(latencies), 2),
        "per_endpoint": endpoint_metrics,
    }


# ---------------------------------------------------------------------------
# Bottleneck detection
# ---------------------------------------------------------------------------

def identify_bottlenecks(phase_metrics: list) -> list:
    """
    Scan per-phase metrics for bottlenecks.
    Flags any endpoint where p95 > 500ms or error rate > 5%.
    Returns a list of bottleneck findings with recommendations.
    """
    bottlenecks = []
    seen = set()

    for phase in phase_metrics:
        per_ep = phase.get("metrics", {}).get("per_endpoint", {})
        for ep, ep_metrics in per_ep.items():
            p95 = ep_metrics.get("p95_latency_ms", 0)
            err_rate = ep_metrics.get("error_rate_pct", 0)

            if p95 > P95_LATENCY_THRESHOLD_MS:
                key = f"{ep}:latency:{phase['phase']}"
                if key not in seen:
                    seen.add(key)
                    bottlenecks.append({
                        "type": "HIGH_LATENCY",
                        "endpoint": ep,
                        "phase": phase["phase"],
                        "rps_fraction": phase["rps_fraction"],
                        "p95_latency_ms": p95,
                        "threshold_ms": P95_LATENCY_THRESHOLD_MS,
                        "recommendation": (
                            f"p95 latency {p95}ms exceeds {P95_LATENCY_THRESHOLD_MS}ms threshold at "
                            f"{phase['rps_fraction']*100:.0f}% load. "
                            "Consider: adding caching, query optimisation, or horizontal scaling."
                        ),
                    })

            if err_rate > ERROR_RATE_THRESHOLD_PCT:
                key = f"{ep}:error:{phase['phase']}"
                if key not in seen:
                    seen.add(key)
                    bottlenecks.append({
                        "type": "HIGH_ERROR_RATE",
                        "endpoint": ep,
                        "phase": phase["phase"],
                        "rps_fraction": phase["rps_fraction"],
                        "error_rate_pct": err_rate,
                        "threshold_pct": ERROR_RATE_THRESHOLD_PCT,
                        "recommendation": (
                            f"Error rate {err_rate}% exceeds {ERROR_RATE_THRESHOLD_PCT}% threshold at "
                            f"{phase['rps_fraction']*100:.0f}% load. "
                            "Consider: circuit breaker, rate limiter tuning, or service autoscaling."
                        ),
                    })

    return bottlenecks


# ---------------------------------------------------------------------------
# Phase runner
# ---------------------------------------------------------------------------

def run_phase(
    phase_name: str,
    rps_fraction: float,
    target_rps: int,
    phase_duration_s: float,
    workers: int,
    base_url: str,
    username: str,
    password: str,
    token: Optional[str],
) -> dict:
    """
    Run a single load phase at the given RPS fraction.
    Returns phase results dict.
    """
    effective_rps = max(1, int(target_rps * rps_fraction))
    logger.info(
        "LOAD_PHASE phase=%s rps_fraction=%.0f%% effective_rps=%s duration=%.1fs workers=%s",
        phase_name, rps_fraction * 100, effective_rps, phase_duration_s, workers
    )

    results: list = []
    lock = threading.Lock()

    phase_start = time.monotonic()
    phase_end = phase_start + phase_duration_s

    # We'll use a semaphore to throttle requests to effective_rps
    interval_between_requests = 1.0 / effective_rps

    def worker_loop():
        """Each thread fires requests until the phase duration expires."""
        while time.monotonic() < phase_end:
            req_start = time.monotonic()

            # Rotate through three scenarios
            scenario = int(req_start * 10) % 3
            if scenario == 0:
                run_health_check(base_url, results=results, lock=lock)
            elif scenario == 1:
                run_auth_login(base_url, username=username, password=password,
                               results=results, lock=lock)
            else:
                run_get_properties(base_url, token=token, results=results, lock=lock)

            # Pace requests
            elapsed = time.monotonic() - req_start
            sleep_time = interval_between_requests - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    threads = []
    for _ in range(min(workers, effective_rps)):
        t = threading.Thread(target=worker_loop, daemon=True)
        t.start()
        threads.append(t)

    for t in threads:
        t.join(timeout=phase_duration_s + 10)

    metrics = compute_metrics(results)
    return {
        "phase": phase_name,
        "rps_fraction": rps_fraction,
        "effective_rps": effective_rps,
        "duration_s": phase_duration_s,
        "metrics": metrics,
    }


# ---------------------------------------------------------------------------
# Main load test runner
# ---------------------------------------------------------------------------

def run_load_test(
    base_url: str = "http://localhost",
    target_rps: int = 50,
    total_duration_s: int = 120,
    workers: int = 10,
    username: str = "",
    password: str = "",
    output_path: str = "reports/load_test_report.json",
) -> dict:
    """
    Run the full load test across all phases and generate a report.

    The total_duration_s is divided equally across all LOAD_PHASES.
    This allows a 2-hour run (7200s) or a shorter representative run (120s).
    """
    if not REQUESTS_AVAILABLE:
        logger.error("requests library is required for load testing")
        sys.exit(1)

    logger.info(
        "LOAD_TEST_START base_url=%s target_rps=%s duration=%ss workers=%s",
        base_url, target_rps, total_duration_s, workers
    )

    phase_duration_s = total_duration_s / len(LOAD_PHASES)
    token = None  # No pre-auth token for now; auth endpoint will receive 401 from invalid creds

    phase_results = []
    test_start = datetime.now(timezone.utc)

    for phase_name, rps_fraction in LOAD_PHASES:
        phase_result = run_phase(
            phase_name=phase_name,
            rps_fraction=rps_fraction,
            target_rps=target_rps,
            phase_duration_s=phase_duration_s,
            workers=workers,
            base_url=base_url,
            username=username,
            password=password,
            token=token,
        )
        phase_results.append(phase_result)

    test_end = datetime.now(timezone.utc)

    # Aggregate metrics across all phases
    all_results_combined = []
    for phase in phase_results:
        m = phase["metrics"]
        # Reconstruct a simplified aggregate
        all_results_combined.append(m)

    # Compute overall aggregates
    total_requests = sum(p["metrics"]["total_requests"] for p in phase_results)
    total_success = sum(p["metrics"]["successful_requests"] for p in phase_results)
    total_failed = sum(p["metrics"]["failed_requests"] for p in phase_results)
    overall_success_rate = (total_success / total_requests * 100) if total_requests > 0 else 0.0

    all_p95 = [p["metrics"]["p95_latency_ms"] for p in phase_results if p["metrics"]["total_requests"] > 0]
    all_p99 = [p["metrics"]["p99_latency_ms"] for p in phase_results if p["metrics"]["total_requests"] > 0]

    aggregate_metrics = {
        "total_requests": total_requests,
        "successful_requests": total_success,
        "failed_requests": total_failed,
        "overall_success_rate_pct": round(overall_success_rate, 2),
        "overall_error_rate_pct": round((total_failed / total_requests * 100) if total_requests > 0 else 100.0, 2),
        "max_p95_latency_ms": round(max(all_p95), 2) if all_p95 else 0,
        "max_p99_latency_ms": round(max(all_p99), 2) if all_p99 else 0,
    }

    # Identify bottlenecks
    bottlenecks = identify_bottlenecks(phase_results)

    # PASS/FAIL determination
    critical_bottlenecks = [b for b in bottlenecks if b["type"] in ("HIGH_LATENCY", "HIGH_ERROR_RATE")]
    passed = overall_success_rate >= SUCCESS_RATE_MIN_PCT and len(critical_bottlenecks) == 0

    # Build report
    report = {
        "report_type": "load_test",
        "generated_at": test_end.isoformat(),
        "test_started_at": test_start.isoformat(),
        "test_ended_at": test_end.isoformat(),
        "overall_result": "PASS" if passed else "FAIL",
        "parameters": {
            "base_url": base_url,
            "target_rps": target_rps,
            "peak_load": f"{target_rps * 2} RPS (2× target)",
            "total_duration_s": total_duration_s,
            "workers": workers,
            "phases": len(LOAD_PHASES),
            "phase_duration_s": round(phase_duration_s, 1),
        },
        "aggregate_metrics": aggregate_metrics,
        "phase_metrics": phase_results,
        "bottleneck_findings": bottlenecks,
        "recommendations": [b["recommendation"] for b in bottlenecks],
        "thresholds": {
            "min_success_rate_pct": SUCCESS_RATE_MIN_PCT,
            "p95_latency_threshold_ms": P95_LATENCY_THRESHOLD_MS,
            "error_rate_threshold_pct": ERROR_RATE_THRESHOLD_PCT,
        },
    }

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w") as f:
        json.dump(report, f, indent=2)

    logger.info("Load test report written to: %s", output_path)
    return report


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="ZCloud Load Testing Framework — evaluates system under 2× peak load"
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost",
        help="Base URL (scheme + host) for the services (default: http://localhost)",
    )
    parser.add_argument(
        "--target-rps",
        type=int,
        default=50,
        help="Target requests per second at 100%% load (default: 50). "
             "Test scales from 10%% up to 200%% of this value.",
    )
    parser.add_argument(
        "--duration",
        type=int,
        default=120,
        help="Total test duration in seconds across all phases (default: 120). "
             "Use 7200 for a full 2-hour stability test.",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=10,
        help="Number of concurrent worker threads (default: 10)",
    )
    parser.add_argument(
        "--username",
        default=os.environ.get("LOAD_TEST_USERNAME", ""),
        help="Username for auth_service login test (optional)",
    )
    parser.add_argument(
        "--password",
        default=os.environ.get("LOAD_TEST_PASSWORD", ""),
        help="Password for auth_service login test (optional)",
    )
    parser.add_argument(
        "--output",
        default="reports/load_test_report.json",
        help="Output path for the JSON report (default: reports/load_test_report.json)",
    )
    args = parser.parse_args()

    report = run_load_test(
        base_url=args.base_url,
        target_rps=args.target_rps,
        total_duration_s=args.duration,
        workers=args.workers,
        username=args.username,
        password=args.password,
        output_path=args.output,
    )

    result_symbol = "✅ PASS" if report["overall_result"] == "PASS" else "❌ FAIL"
    agg = report["aggregate_metrics"]
    print(f"\n{'='*60}")
    print(f"LOAD TEST RESULT: {result_symbol}")
    print(f"  Total requests    : {agg['total_requests']}")
    print(f"  Success rate      : {agg['overall_success_rate_pct']}%")
    print(f"  Error rate        : {agg['overall_error_rate_pct']}%")
    print(f"  Max p95 latency   : {agg['max_p95_latency_ms']}ms")
    print(f"  Bottlenecks found : {len(report['bottleneck_findings'])}")
    print(f"  Report            : {args.output}")
    print(f"{'='*60}\n")

    if report["bottleneck_findings"]:
        print("Bottleneck findings:")
        for b in report["bottleneck_findings"]:
            print(f"  [{b['type']}] {b['endpoint']} @ {b.get('rps_fraction', 0)*100:.0f}% load")
            print(f"    → {b['recommendation']}")
        print()

    sys.exit(0 if report["overall_result"] == "PASS" else 1)


if __name__ == "__main__":
    main()
