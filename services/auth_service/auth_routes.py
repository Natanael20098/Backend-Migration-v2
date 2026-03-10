"""
AuthController equivalent — Flask Blueprint for authentication endpoints.

Endpoints:
  POST /api/auth/login     — authenticate with username + password; returns JWT
  POST /api/auth/logout    — invalidate the current session token
  GET  /api/auth/validate  — validate the current session token

All endpoints are public (no JWT required on /api/auth/*) per the JWT filter
configuration in jwt_filter.py. The /logout and /validate endpoints do their
own token extraction and validation internally.
"""

import logging
from datetime import datetime, timezone, timedelta

import bcrypt
import jwt
from flask import Blueprint, g, jsonify, request

from services.auth_service.config import (
    JWT_ALGORITHM,
    JWT_AUDIENCE,
    JWT_EXPIRATION_SECONDS,
    JWT_ISSUER,
    JWT_SECRET,
)
from services.auth_service.db import get_db
from services.auth_service.jwt_filter import verify_token, _extract_bearer_token
from services.auth_service.models import Session, User

logger = logging.getLogger(__name__)

auth_bp = Blueprint("auth", __name__, url_prefix="/api/auth")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _issue_token(user: User) -> str:
    """Generate a signed JWT for the given user with custom claims."""
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(user.id),
        "email": user.email,
        "username": user.username,
        "roles": user.get_roles(),
        "iss": JWT_ISSUER,
        "aud": JWT_AUDIENCE,
        "iat": now,
        "exp": now + timedelta(seconds=JWT_EXPIRATION_SECONDS),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _error(message: str, status: int) -> tuple:
    return jsonify({"error": message}), status


# ---------------------------------------------------------------------------
# POST /api/auth/login
# ---------------------------------------------------------------------------

@auth_bp.route("/login", methods=["POST"])
def login():
    """
    Authenticate a user with username + password.

    Request body:
        { "username": str, "password": str }

    Responses:
        200 { "token": str, "user_id": str, "roles": list[str] }
        400 missing or invalid request body
        401 invalid credentials
        403 account disabled
    """
    body = request.get_json(silent=True)
    if not body:
        logger.warning("LOGIN_FAILED bad_request remote=%s", request.remote_addr)
        return _error("Request body must be valid JSON", 400)

    username: str = (body.get("username") or "").strip()
    password: str = body.get("password") or ""

    if not username or not password:
        logger.warning("LOGIN_FAILED missing_credentials remote=%s", request.remote_addr)
        return _error("username and password are required", 400)

    db = get_db()
    try:
        user: User | None = db.query(User).filter(User.username == username).first()

        if user is None or not bcrypt.checkpw(password.encode(), user.password_hash.encode()):
            logger.warning(
                "AUTH_FAILED invalid_credentials username=%s remote=%s",
                username,
                request.remote_addr,
            )
            return _error("Invalid username or password", 401)

        if not user.is_active:
            logger.warning(
                "AUTH_FAILED account_disabled username=%s remote=%s",
                username,
                request.remote_addr,
            )
            return _error("Account is disabled", 403)

        token = _issue_token(user)
        expires_at = datetime.now(timezone.utc) + timedelta(seconds=JWT_EXPIRATION_SECONDS)

        session = Session(
            user_id=user.id,
            token=token,
            is_active=True,
            expires_at=expires_at,
        )
        db.add(session)
        db.commit()

        logger.info(
            "AUTH_SUCCESS login username=%s remote=%s",
            username,
            request.remote_addr,
        )

        return jsonify({
            "token": token,
            "user_id": str(user.id),
            "roles": user.get_roles(),
        }), 200

    finally:
        db.close()


# ---------------------------------------------------------------------------
# POST /api/auth/logout
# ---------------------------------------------------------------------------

@auth_bp.route("/logout", methods=["POST"])
def logout():
    """
    Invalidate the current session token.

    The token is extracted from the Authorization header (Bearer scheme).

    Responses:
        200 { "message": "Logged out successfully" }
        401 token missing or invalid
    """
    token = _extract_bearer_token()
    if not token:
        return _error("Bearer token is required", 401)

    try:
        claims = verify_token(token)
    except jwt.PyJWTError as exc:
        logger.warning(
            "LOGOUT_FAILED invalid_token remote=%s error=%s",
            request.remote_addr,
            str(exc),
        )
        return _error("Invalid or expired token", 401)

    db = get_db()
    try:
        session: Session | None = (
            db.query(Session)
            .filter(Session.token == token, Session.is_active == True)
            .first()
        )
        if session:
            session.is_active = False
            db.commit()

        logger.info(
            "AUTH_SUCCESS logout subject=%s remote=%s",
            claims.get("sub"),
            request.remote_addr,
        )
        return jsonify({"message": "Logged out successfully"}), 200

    finally:
        db.close()


# ---------------------------------------------------------------------------
# GET /api/auth/validate
# ---------------------------------------------------------------------------

@auth_bp.route("/validate", methods=["GET"])
def validate():
    """
    Validate the current session token and return user claims.

    Responses:
        200 { "valid": true, "subject": str, "roles": list[str] }
        401 token missing, expired, or revoked
    """
    token = _extract_bearer_token()
    if not token:
        return _error("Bearer token is required", 401)

    try:
        claims = verify_token(token)
    except jwt.ExpiredSignatureError:
        return _error("Token has expired", 401)
    except jwt.PyJWTError as exc:
        return _error(f"Invalid token: {exc}", 401)

    # Confirm session is still active (not logged out)
    db = get_db()
    try:
        session: Session | None = (
            db.query(Session)
            .filter(Session.token == token, Session.is_active == True)
            .first()
        )
        if not session:
            logger.warning(
                "VALIDATE_FAILED revoked_token subject=%s remote=%s",
                claims.get("sub"),
                request.remote_addr,
            )
            return _error("Token has been revoked", 401)

        logger.info(
            "AUTH_SUCCESS validate subject=%s remote=%s",
            claims.get("sub"),
            request.remote_addr,
        )
        return jsonify({
            "valid": True,
            "subject": claims.get("sub"),
            "roles": claims.get("roles", []),
        }), 200

    finally:
        db.close()
