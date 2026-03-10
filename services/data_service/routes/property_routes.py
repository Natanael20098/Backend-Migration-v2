"""
Property CRUD API routes for the ZCloud Data Management Microservice.

Endpoints:
  POST   /api/properties           — Create a property
  GET    /api/properties           — List all properties
  GET    /api/properties/<id>      — Get a property by ID
  PUT    /api/properties/<id>      — Update a property
  DELETE /api/properties/<id>      — Delete a property

Migrated from: com.zcloud.platform.controller.PropertyController (Java/Spring Boot)
"""

import logging
import uuid
from datetime import date
from decimal import Decimal, InvalidOperation

from flask import Blueprint, jsonify, request

from services.data_service.db import get_db
from services.data_service.models import Property
from services.data_service.repositories.property_repository import PropertyRepository

logger = logging.getLogger(__name__)

property_bp = Blueprint("property", __name__, url_prefix="/api/properties")

# Fields allowed on create / update (prevents mass-assignment of system fields)
_ALLOWED_FIELDS = {
    "address_line1",
    "address_line2",
    "city",
    "state",
    "zip_code",
    "county",
    "latitude",
    "longitude",
    "beds",
    "baths",
    "sqft",
    "lot_size",
    "year_built",
    "property_type",
    "description",
    "parking_spaces",
    "garage_type",
    "hoa_fee",
    "zoning",
    "parcel_number",
    "last_sold_price",
    "last_sold_date",
    "current_tax_amount",
}

_REQUIRED_CREATE_FIELDS = {"address_line1", "city", "state"}


def _validate_property_payload(data: dict, require_fields: set = None) -> list:
    """Return a list of validation error messages, or an empty list if valid."""
    errors = []

    if require_fields:
        for field in require_fields:
            if not data.get(field):
                errors.append(f"'{field}' is required.")

    # Numeric validations
    for numeric_field in ("beds", "sqft", "parking_spaces", "year_built"):
        if numeric_field in data and data[numeric_field] is not None:
            try:
                val = int(data[numeric_field])
                if val < 0:
                    errors.append(f"'{numeric_field}' must be a non-negative integer.")
            except (TypeError, ValueError):
                errors.append(f"'{numeric_field}' must be an integer.")

    for decimal_field in (
        "baths", "lot_size", "hoa_fee", "latitude", "longitude",
        "last_sold_price", "current_tax_amount",
    ):
        if decimal_field in data and data[decimal_field] is not None:
            try:
                Decimal(str(data[decimal_field]))
            except InvalidOperation:
                errors.append(f"'{decimal_field}' must be a valid number.")

    if "last_sold_date" in data and data["last_sold_date"] is not None:
        try:
            date.fromisoformat(str(data["last_sold_date"]))
        except ValueError:
            errors.append("'last_sold_date' must be a valid ISO date (YYYY-MM-DD).")

    if "year_built" in data and data["year_built"] is not None:
        try:
            year = int(data["year_built"])
            if year < 1600 or year > 2100:
                errors.append("'year_built' must be between 1600 and 2100.")
        except (TypeError, ValueError):
            pass  # already caught above

    return errors


def _apply_fields(entity: Property, data: dict) -> None:
    """Apply allowed fields from *data* dict onto *entity*."""
    for field in _ALLOWED_FIELDS:
        if field not in data:
            continue
        value = data[field]
        if field in ("beds", "sqft", "parking_spaces", "year_built") and value is not None:
            value = int(value)
        elif field in (
            "baths", "lot_size", "hoa_fee", "latitude", "longitude",
            "last_sold_price", "current_tax_amount",
        ) and value is not None:
            value = Decimal(str(value))
        elif field == "last_sold_date" and value is not None:
            value = date.fromisoformat(str(value))
        setattr(entity, field, value)


# ---------------------------------------------------------------------------
# POST /api/properties — Create
# ---------------------------------------------------------------------------

@property_bp.route("", methods=["POST"])
def create_property():
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Request body must be valid JSON."}), 400

    errors = _validate_property_payload(data, require_fields=_REQUIRED_CREATE_FIELDS)
    if errors:
        return jsonify({"error": "Validation failed.", "details": errors}), 400

    session = get_db()
    try:
        prop = Property()
        _apply_fields(prop, data)
        repo = PropertyRepository(session)
        saved = repo.save(prop)
        session.commit()
        return jsonify(saved.to_dict()), 201
    except Exception as exc:
        session.rollback()
        logger.error("create_property failed: %s", exc, exc_info=True)
        return jsonify({"error": "Failed to create property.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# GET /api/properties — List all
# ---------------------------------------------------------------------------

@property_bp.route("", methods=["GET"])
def list_properties():
    session = get_db()
    try:
        repo = PropertyRepository(session)
        properties = repo.find_all()
        return jsonify([p.to_dict() for p in properties]), 200
    except Exception as exc:
        logger.error("list_properties failed: %s", exc, exc_info=True)
        return jsonify({"error": "Failed to retrieve properties.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# GET /api/properties/<id> — Read one
# ---------------------------------------------------------------------------

@property_bp.route("/<string:property_id>", methods=["GET"])
def get_property(property_id: str):
    try:
        pid = uuid.UUID(property_id)
    except ValueError:
        return jsonify({"error": "Invalid property ID format."}), 400

    session = get_db()
    try:
        repo = PropertyRepository(session)
        prop = repo.find_by_id(pid)
        if prop is None:
            return jsonify({"error": "Property not found."}), 404
        return jsonify(prop.to_dict()), 200
    except Exception as exc:
        logger.error("get_property(%s) failed: %s", property_id, exc, exc_info=True)
        return jsonify({"error": "Failed to retrieve property.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# PUT /api/properties/<id> — Update
# ---------------------------------------------------------------------------

@property_bp.route("/<string:property_id>", methods=["PUT"])
def update_property(property_id: str):
    try:
        pid = uuid.UUID(property_id)
    except ValueError:
        return jsonify({"error": "Invalid property ID format."}), 400

    data = request.get_json(silent=True)
    if not data:
        return jsonify({"error": "Request body must be valid JSON."}), 400

    errors = _validate_property_payload(data)
    if errors:
        return jsonify({"error": "Validation failed.", "details": errors}), 400

    session = get_db()
    try:
        repo = PropertyRepository(session)
        prop = repo.find_by_id(pid)
        if prop is None:
            return jsonify({"error": "Property not found."}), 404
        _apply_fields(prop, data)
        saved = repo.save(prop)
        session.commit()
        return jsonify(saved.to_dict()), 200
    except Exception as exc:
        session.rollback()
        logger.error("update_property(%s) failed: %s", property_id, exc, exc_info=True)
        return jsonify({"error": "Failed to update property.", "detail": str(exc)}), 500
    finally:
        session.close()


# ---------------------------------------------------------------------------
# DELETE /api/properties/<id> — Delete
# ---------------------------------------------------------------------------

@property_bp.route("/<string:property_id>", methods=["DELETE"])
def delete_property(property_id: str):
    try:
        pid = uuid.UUID(property_id)
    except ValueError:
        return jsonify({"error": "Invalid property ID format."}), 400

    session = get_db()
    try:
        repo = PropertyRepository(session)
        deleted = repo.delete_by_id(pid)
        if not deleted:
            return jsonify({"error": "Property not found."}), 404
        session.commit()
        return jsonify({"message": "Property deleted successfully."}), 200
    except Exception as exc:
        session.rollback()
        logger.error("delete_property(%s) failed: %s", property_id, exc, exc_info=True)
        return jsonify({"error": "Failed to delete property.", "detail": str(exc)}), 500
    finally:
        session.close()
