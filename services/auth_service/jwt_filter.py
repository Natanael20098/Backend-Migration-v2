"""
JWT Authentication Filter for the ZCloud Auth Service.

Mirrors Java's JwtAuthenticationFilter (OncePerRequestFilter).

Behaviour:
  - Requests to PUBLIC_PATH_PREFIXES pass through without token validation.
  - All other requests must carry a valid Bearer JWT in the Authorization header.
  - Valid tokens: user claims are attached to Flask's g context.
  - Invalid / expired tokens: 403 Forbidden is returned immediately.
  - Every attempt (success or failure) is logged for audit purposes.
"""

import logging
from functools import wraps
from typing import Callable

import jwt
from flask import g, request, jsonify

from services.auth_service.config import (
    JWT_ALGORITHM,
    JWT_AUDIENCE,
    JWT_SECRET,
    PUBLIC_PATH_PREFIXES,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Core token verification
# ---------------------------------------------------------------------------

def verify_token(token: str) -> dict:
    """
    Decode and verify a JWT string.

    Returns the decoded claims dict on success.
    Raises jwt.PyJWTError (or a subclass) on failure:
      - jwt.ExpiredSignatureError  — token has expired
      - jwt.InvalidTokenError      — token is malformed / signature invalid
    """
    return jwt.decode(
        token,
        JWT_SECRET,
        algorithms=[JWT_ALGORITHM],
        audience=JWT_AUDIENCE,
        options={"require": ["sub", "exp", "iat"]},
    )


# ---------------------------------------------------------------------------
# Request-level helpers
# ---------------------------------------------------------------------------

def _extract_bearer_token() -> str | None:
    """Extract the raw token string from the Authorization header, or None."""
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer "):
        return auth_header[7:].strip()
    return None


def _is_public_path(path: str) -> bool:
    """Return True if the request path is exempt from JWT verification."""
    return any(path.startswith(prefix) for prefix in PUBLIC_PATH_PREFIXES)


# ---------------------------------------------------------------------------
# Flask before_request hook — registered by create_app()
# ---------------------------------------------------------------------------

def jwt_authentication_filter() -> None:
    """
    Flask before_request hook that implements the JWT authentication filter.

    Attaches verified claims to ``flask.g.user_claims`` so downstream route
    handlers can access identity information via ``g.user_claims``.

    Returns a 403 JSON response for requests with a missing, invalid, or
    expired token on protected paths.
    """
    path = request.path

    if _is_public_path(path):
        return None  # pass through — no token required

    token = _extract_bearer_token()

    if not token:
        logger.warning(
            "AUTH_FAILED missing_token path=%s remote=%s",
            path,
            request.remote_addr,
        )
        return (
            jsonify({"error": "Authentication required", "detail": "Bearer token is missing"}),
            403,
        )

    try:
        claims = verify_token(token)
    except jwt.ExpiredSignatureError:
        logger.warning(
            "AUTH_FAILED expired_token path=%s remote=%s",
            path,
            request.remote_addr,
        )
        return (
            jsonify({"error": "Token expired", "detail": "The provided JWT has expired"}),
            403,
        )
    except jwt.PyJWTError as exc:
        logger.warning(
            "AUTH_FAILED invalid_token path=%s remote=%s error=%s",
            path,
            request.remote_addr,
            str(exc),
        )
        return (
            jsonify({"error": "Invalid token", "detail": str(exc)}),
            403,
        )

    # Attach claims to request context
    g.user_claims = claims
    logger.info(
        "AUTH_SUCCESS subject=%s path=%s remote=%s",
        claims.get("sub"),
        path,
        request.remote_addr,
    )
    return None


# ---------------------------------------------------------------------------
# Decorator — use on individual routes when fine-grained control is needed
# ---------------------------------------------------------------------------

def require_auth(f: Callable) -> Callable:
    """
    Route decorator that enforces JWT authentication.

    Use on routes that are NOT already covered by the before_request filter
    (e.g., blueprints mounted before the filter is registered).

    Returns 403 if token is missing, expired, or invalid.
    Attaches claims to ``g.user_claims`` on success.
    """
    @wraps(f)
    def decorated(*args, **kwargs):
        result = jwt_authentication_filter()
        if result is not None:
            return result
        return f(*args, **kwargs)

    return decorated
