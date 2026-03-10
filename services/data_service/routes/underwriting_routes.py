"""
UnderwritingDecision CRUD API routes for the ZCloud Data Management Microservice.

Endpoints:
  POST   /api/underwriting/decisions           — Create a decision (201)
  GET    /api/underwriting/decisions           — List all decisions (200)
  GET    /api/underwriting/decisions/<id>      — Get a decision by ID (200)
  PUT    /api/underwriting/decisions/<id>      — Update a decision (200)
  DELETE /api/underwriting/decisions/<id>      — Delete a decision (200)

Migrated from: com.zcloud.platform.controller.UnderwritingController (Java/Spring Boot)
"""

import logging
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation

from flask import Blueprint, jsonify, request

from services.data_service.db import get_db
from services.data_service.models import UnderwritingDecision
from services.data_service.repositories.underwriting_decision_repository import (
    UnderwritingDecisionRepository,
)

logger = logging.getLogger(__name__)

underwriting_bp = Blueprint(
    "underwriting", __name__, url_prefix="/api/underwriting"
)

_VALID_DECISIONS = UnderwritingDecision.VALID_DECISIONS

_ALLOWED_FIELDS = {
    "loan_application_id",
    "underwriter_id",
    "decision",
    "conditions",
    "dti_ratio",
    "ltv_ratio",
    "risk_score",
    "notes",
    "decision_date",
    "loan_amount",
    "loan_type",
    "borrower_name",
    "property_address",
}

_REQUIRED_CREATE_FIELDS = {"loan_application_id", "decision"}


def _validate_decision_payload(data: dict, require_fields: set = None) -> list:
    """Return a list of validation error messages, or an empty list if valid."""
    errors = []

    if require_fields:
        for field in require_fields:
            if not data.get(field):
                errors.append(f"'{field}' is required.")

    if "decision" in data and data["decision"]:
        if data["decision"] not in _VALID_DECISIONS:
            errors.append(
                f"'decision' must be one of: {sorted(_VALID_DECISIONS)}."
            )

    for uuid_field in ("loan_application_id", "underwriter_id"):
        if uuid_field in data and data[uuid_field] is not None:
            try:
                uuid.UUID(str(data[uuid_field]))
            except ValueError:
                errors.append(f"'{uuid_field}' must be a valid UUID.")

    for decimal_field in ("dti_ratio", "ltv_ratio", "risk_score", "loan_amount"):
        if decimal_field in data and data[decimal_field] is not None:
            try:
                Decimal(str(data[decimal_field]))
            except InvalidOperation:
                errors.append(f"'{decimal_field}' must be a valid number.")

    if "decision_date" in data and data["decision_date"] is not None:
        try:
            datetime.fromisoformat(str(data["decision_date"]))
        except ValueError:
            errors.append(
                "'decision_date' must be a valid ISO datetime string."
            )

    return errors


def _apply_fields(entity: UnderwritingDecision, data: dict) -> None:
    """Apply allowed fields from *data* dict onto *entity*."""
    for field in _ALLOWED_FIELDS:
        if field not in data:
            continue
        value = data[field]
        if field in ("dti_ratio", "ltv_ratio", "risk_score", "loan_amount") and value is not None:
            value = Decimal(str(value))
        elif field in ("loan_application_id", "underwriter_id") and value is not None:
            value = uuid.UUID(str(value))
        elif field == "decision_date" and value is not None:
            value = datetime.fromisoformat(str(value))
        setattr(entity, field, value)


# ---------------------------------------------------------------------------
# POST /api/underwriting/decisions — Create (201)
# ---------------------------------------------------------------------------

@underwriting_bp.route("/decisions", methods=["POST"])
def create_decision():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Request body must be valid JSON."}), 400

    errors = _validate_decision_payload(data, require_fields=_REQUIRED_CREATE_FIELDS)
    if errors:
        return jsonify({"error": "Validation failed.", "details": errors}), 400

    session = get_db()
    try:
        decision = UnderwritingDecision()
        _apply_fields(decision, data)
        repo = UnderwritingDecisionRepository(session)
        saved = repo.save(decision)
        session.commit()
        return jsonify(saved.to_dict()), 201
    except Exception as exc:
        session.rollback()
        logger.error("create_decision failed: %s", exc, exc_info=True)
        return jsonify({"error": "Failed to create underwriting decision.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# GET /api/underwriting/decisions — List all (200)
# ---------------------------------------------------------------------------

@underwriting_bp.route("/decisions", methods=["GET"])
def list_decisions():
    session = get_db()
    try:
        repo = UnderwritingDecisionRepository(session)
        decisions = repo.find_all()
        return jsonify([d.to_dict() for d in decisions]), 200
    except Exception as exc:
        logger.error("list_decisions failed: %s", exc, exc_info=True)
        return jsonify({"error": "Failed to retrieve decisions.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# GET /api/underwriting/decisions/<id> — Read one (200)
# ---------------------------------------------------------------------------

@underwriting_bp.route("/decisions/<string:decision_id>", methods=["GET"])
def get_decision(decision_id: str):
    try:
        did = uuid.UUID(decision_id)
    except ValueError:
        return jsonify({"error": "Invalid decision ID format."}), 400

    session = get_db()
    try:
        repo = UnderwritingDecisionRepository(session)
        decision = repo.find_by_id(did)
        if decision is None:
            return jsonify({"error": "Underwriting decision not found."}), 404
        return jsonify(decision.to_dict()), 200
    except Exception as exc:
        logger.error("get_decision(%s) failed: %s", decision_id, exc, exc_info=True)
        return jsonify({"error": "Failed to retrieve decision.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# PUT /api/underwriting/decisions/<id> — Update (200)
# ---------------------------------------------------------------------------

@underwriting_bp.route("/decisions/<string:decision_id>", methods=["PUT"])
def update_decision(decision_id: str):
    try:
        did = uuid.UUID(decision_id)
    except ValueError:
        return jsonify({"error": "Invalid decision ID format."}), 400

    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Request body must be valid JSON."}), 400

    errors = _validate_decision_payload(data)
    if errors:
        return jsonify({"error": "Validation failed.", "details": errors}), 400

    session = get_db()
    try:
        repo = UnderwritingDecisionRepository(session)
        decision = repo.find_by_id(did)
        if decision is None:
            return jsonify({"error": "Underwriting decision not found."}), 404
        _apply_fields(decision, data)
        saved = repo.save(decision)
        session.commit()
        return jsonify(saved.to_dict()), 200
    except Exception as exc:
        session.rollback()
        logger.error("update_decision(%s) failed: %s", decision_id, exc, exc_info=True)
        return jsonify({"error": "Failed to update decision.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# DELETE /api/underwriting/decisions/<id> — Delete (200)
# ---------------------------------------------------------------------------

@underwriting_bp.route("/decisions/<string:decision_id>", methods=["DELETE"])
def delete_decision(decision_id: str):
    try:
        did = uuid.UUID(decision_id)
    except ValueError:
        return jsonify({"error": "Invalid decision ID format."}), 400

    session = get_db()
    try:
        repo = UnderwritingDecisionRepository(session)
        deleted = repo.delete_by_id(did)
        if not deleted:
            return jsonify({"error": "Underwriting decision not found."}), 404
        session.commit()
        return jsonify({"message": "Underwriting decision deleted successfully."}), 200
    except Exception as exc:
        session.rollback()
        logger.error("delete_decision(%s) failed: %s", decision_id, exc, exc_info=True)
        return jsonify({"error": "Failed to delete decision.", "detail": str(exc)}), 500
    finally:
        session.close()
