"""
ZCloud Data Management Microservice — Flask application factory.

Wires together:
  - DatabaseConfig (connection pooling, env-var credentials)
  - PropertyRepository  (CRUD + custom queries for Property)
  - UnderwritingDecisionRepository  (CRUD + custom queries for UnderwritingDecision)
  - Property CRUD API blueprint  (POST/GET/PUT/DELETE /api/properties)
  - UnderwritingDecision CRUD API blueprint  (POST/GET/PUT/DELETE /api/underwriting/decisions)
  - Health check endpoint (GET /health)

Migrated from: com.zcloud.platform (Java/Spring Boot Data Management layer)
"""

import logging
import sys

from flask import Flask, jsonify
from flask_cors import CORS

sys.path.insert(0, "/app")

from services.data_service.db import init_db
from services.data_service.routes.property_routes import property_bp
from services.data_service.routes.underwriting_routes import underwriting_bp

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


def create_app() -> Flask:
    """Application factory — creates and configures the Flask app."""
    app = Flask(__name__)

    # ------------------------------------------------------------------
    # CORS — allow cross-origin requests from the frontend
    # ------------------------------------------------------------------
    CORS(app, supports_credentials=True)

    # ------------------------------------------------------------------
    # Blueprints
    # ------------------------------------------------------------------
    app.register_blueprint(property_bp)
    app.register_blueprint(underwriting_bp)

    # ------------------------------------------------------------------
    # Health check
    # ------------------------------------------------------------------
    @app.route("/health", methods=["GET"])
    def health():
        return jsonify({"status": "ok", "service": "data_service"}), 200

    # ------------------------------------------------------------------
    # Error handlers — consistent JSON error responses
    # ------------------------------------------------------------------
    @app.errorhandler(404)
    def not_found(e):
        return jsonify({"error": "Not found"}), 404

    @app.errorhandler(405)
    def method_not_allowed(e):
        return jsonify({"error": "Method not allowed"}), 405

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
        except Exception as exc:
            logger.error("Failed to initialise database: %s", exc)

    return app


app = create_app()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8002, debug=False)
