import logging
import sys

from flask import Flask, jsonify

sys.path.insert(0, "/app")
from services.shared.database import check_database_health

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)

app = Flask(__name__)


@app.route("/health", methods=["GET"])
def health_check():
    """
    Health check endpoint.

    Queries the PostgreSQL database and returns 200 OK on success,
    or 503 Service Unavailable with error details on failure.
    """
    try:
        check_database_health()
        return jsonify({"status": "ok", "database": "connected"}), 200
    except Exception as exc:
        logger.error("Health check failed: %s", str(exc))
        return jsonify({"status": "error", "database": "unreachable", "detail": str(exc)}), 503


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000)
