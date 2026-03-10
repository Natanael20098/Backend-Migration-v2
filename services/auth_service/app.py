"""
ZCloud Auth Service — Flask application factory.

Wires together:
  - Security configuration (JWT, CORS, rate limiting, encryption)
  - JWT authentication filter (before_request hook)
  - Auth routes blueprint (POST /api/auth/login, POST /api/auth/logout, GET /api/auth/validate)
  - Health check endpoint (GET /health)
  - PostgreSQL via SQLAlchemy (models created on startup)
"""

import logging
import sys

from flask import Flask, jsonify
from flask_cors import CORS
from flask_limiter import Limiter
from flask_limiter.util import get_remote_address

sys.path.insert(0, "/app")

from services.auth_service.auth_routes import auth_bp
from services.auth_service.config import (
    CORS_ALLOWED_HEADERS,
    CORS_ALLOWED_METHODS,
    CORS_ALLOWED_ORIGINS,
    CORS_EXPOSE_HEADERS,
    RATE_LIMIT_DEFAULT,
)
from services.auth_service.db import init_db
from services.auth_service.jwt_filter import jwt_authentication_filter

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


def create_app() -> Flask:
    """Application factory — creates and configures the Flask app."""
    app = Flask(__name__)

    # ------------------------------------------------------------------
    # CORS — only trusted origins allowed (Task 2: CORS settings)
    # ------------------------------------------------------------------
    CORS(
        app,
        origins=CORS_ALLOWED_ORIGINS,
        methods=CORS_ALLOWED_METHODS,
        allow_headers=CORS_ALLOWED_HEADERS,
        expose_headers=CORS_EXPOSE_HEADERS,
        supports_credentials=True,
    )

    # ------------------------------------------------------------------
    # Rate limiting — protects endpoints from abuse (Task 2: rate limiting)
    # ------------------------------------------------------------------
    Limiter(
        get_remote_address,
        app=app,
        default_limits=[RATE_LIMIT_DEFAULT],
        storage_uri="memory://",
    )

    # ------------------------------------------------------------------
    # JWT authentication filter — intercepts all non-public requests
    # (Task 4: JWT authentication filter)
    # ------------------------------------------------------------------
    app.before_request(jwt_authentication_filter)

    # ------------------------------------------------------------------
    # Blueprints
    # ------------------------------------------------------------------
    app.register_blueprint(auth_bp)

    # ------------------------------------------------------------------
    # Health check
    # ------------------------------------------------------------------
    @app.route("/health", methods=["GET"])
    def health():
        return jsonify({"status": "ok", "service": "auth_service"}), 200

    # ------------------------------------------------------------------
    # Error handlers — consistent JSON error responses
    # ------------------------------------------------------------------
    @app.errorhandler(404)
    def not_found(e):
        return jsonify({"error": "Not found"}), 404

    @app.errorhandler(405)
    def method_not_allowed(e):
        return jsonify({"error": "Method not allowed"}), 405

    @app.errorhandler(429)
    def rate_limit_exceeded(e):
        return jsonify({"error": "Too many requests. Please slow down."}), 429

    @app.errorhandler(500)
    def internal_error(e):
        logger.exception("Unhandled server error")
        return jsonify({"error": "Internal server error"}), 500

    # ------------------------------------------------------------------
    # Database — create tables on startup
    # ------------------------------------------------------------------
    with app.app_context():
        try:
            init_db()
            logger.info("Database tables initialised successfully")
        except Exception as exc:
            logger.error("Failed to initialise database: %s", exc)

    return app


app = create_app()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8001, debug=False)
