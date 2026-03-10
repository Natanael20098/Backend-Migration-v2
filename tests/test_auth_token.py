"""
Unit tests for JWT token expiry enforcement and the JWT authentication filter.

Acceptance criteria covered:
  - Verify token expiry is enforced with 403 error
  - Test successful login returns valid token (JWT claims validation)
"""

import json
from datetime import datetime, timezone, timedelta

import jwt as pyjwt
import pytest

from tests.conftest import (
    make_user,
    make_valid_token,
    make_expired_token,
    TEST_JWT_SECRET,
    TEST_JWT_ALGORITHM,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _auth_header(token):
    return {"Authorization": f"Bearer {token}"}


def _get_validate(client, token):
    return client.get("/api/auth/validate", headers=_auth_header(token))


def _get_protected(client, token):
    """Hit a protected path — the JWT filter runs; route doesn't need to exist."""
    return client.get("/api/protected/resource", headers=_auth_header(token))


# ---------------------------------------------------------------------------
# Token expiry enforcement — JWT filter returns 403
# ---------------------------------------------------------------------------

class TestTokenExpiryEnforcement:
    def test_expired_token_returns_403_on_protected_path(self, auth_client_with_session):
        """JWT filter returns HTTP 403 when the bearer token is expired."""
        client, session_factory = auth_client_with_session
        user = make_user(username="tessa", password="Expire1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        expired = make_expired_token(user)
        response = _get_protected(client, expired)
        assert response.status_code == 403

    def test_expired_token_error_message(self, auth_client_with_session):
        """403 response for an expired token includes an expiry-related error message."""
        client, session_factory = auth_client_with_session
        user = make_user(username="uma", password="Expire2!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        expired = make_expired_token(user)
        response = _get_protected(client, expired)
        data = json.loads(response.data)
        assert "error" in data
        # The filter returns "Token expired"
        assert "expired" in data["error"].lower()

    def test_missing_token_returns_403_on_protected_path(self, auth_client_with_session):
        """JWT filter returns HTTP 403 when no Authorization header is supplied."""
        client, _ = auth_client_with_session
        response = client.get("/api/protected/resource")
        assert response.status_code == 403

    def test_missing_token_error_message(self, auth_client_with_session):
        """403 response for missing token includes authentication-required message."""
        client, _ = auth_client_with_session
        response = client.get("/api/protected/resource")
        data = json.loads(response.data)
        assert "error" in data
        assert "authentication required" in data["error"].lower()

    def test_invalid_signature_returns_403(self, auth_client_with_session):
        """JWT filter returns 403 when the token has an invalid signature."""
        client, session_factory = auth_client_with_session
        user = make_user(username="vera", password="BadSig1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        bad_token = make_valid_token(user, secret="completely-different-secret-key!!")
        response = _get_protected(client, bad_token)
        assert response.status_code == 403

    def test_malformed_token_returns_403(self, auth_client_with_session):
        """JWT filter returns 403 for a completely malformed token string."""
        client, _ = auth_client_with_session
        response = _get_protected(client, "this.is.not.a.jwt")
        assert response.status_code == 403

    def test_valid_token_passes_jwt_filter(self, auth_client_with_session):
        """JWT filter passes a valid token through (route may 404, but not 403)."""
        client, session_factory = auth_client_with_session
        user = make_user(username="will", password="PassFilter1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        valid_token = make_valid_token(user)
        response = _get_protected(client, valid_token)
        # The JWT filter passes — Flask returns 404 because the route doesn't exist
        assert response.status_code != 403

    def test_expired_token_returns_403_not_401(self, auth_client_with_session):
        """Expired token is rejected with 403, not 401, matching the Java behaviour."""
        client, session_factory = auth_client_with_session
        user = make_user(username="xena", password="Expire3!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        expired = make_expired_token(user)
        response = _get_protected(client, expired)
        assert response.status_code == 403
        assert response.status_code != 401


# ---------------------------------------------------------------------------
# Token validation endpoint — GET /api/auth/validate
# ---------------------------------------------------------------------------

class TestTokenValidationEndpoint:
    def test_validate_returns_200_for_active_session(self, auth_client_with_session):
        """GET /api/auth/validate returns 200 for a valid, active-session token."""
        client, session_factory = auth_client_with_session
        user = make_user(username="yara", password="Valid1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        login_resp = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "yara", "password": "Valid1!"}),
            content_type="application/json",
        )
        token = json.loads(login_resp.data)["token"]

        response = _get_validate(client, token)
        assert response.status_code == 200

    def test_validate_response_contains_valid_true(self, auth_client_with_session):
        """GET /api/auth/validate response body contains 'valid': true."""
        client, session_factory = auth_client_with_session
        user = make_user(username="zara", password="Valid2!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        login_resp = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "zara", "password": "Valid2!"}),
            content_type="application/json",
        )
        token = json.loads(login_resp.data)["token"]

        response = _get_validate(client, token)
        data = json.loads(response.data)
        assert data.get("valid") is True

    def test_validate_response_contains_subject(self, auth_client_with_session):
        """GET /api/auth/validate response contains 'subject' equal to user ID."""
        client, session_factory = auth_client_with_session
        user = make_user(username="anna", password="Subject1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        login_resp = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "anna", "password": "Subject1!"}),
            content_type="application/json",
        )
        data_login = json.loads(login_resp.data)
        token = data_login["token"]

        response = _get_validate(client, token)
        data = json.loads(response.data)
        assert data["subject"] == data_login["user_id"]

    def test_validate_returns_401_for_expired_token(self, auth_client_with_session):
        """GET /api/auth/validate returns 401 for an already-expired token."""
        client, session_factory = auth_client_with_session
        user = make_user(username="ben", password="ExpVal1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        expired = make_expired_token(user)
        response = _get_validate(client, expired)
        assert response.status_code == 401

    def test_validate_returns_401_when_no_token(self, auth_client_with_session):
        """GET /api/auth/validate without token returns 401."""
        client, _ = auth_client_with_session
        response = client.get("/api/auth/validate")
        assert response.status_code == 401

    def test_validate_response_contains_roles(self, auth_client_with_session):
        """GET /api/auth/validate response includes roles from the token."""
        client, session_factory = auth_client_with_session
        user = make_user(
            username="clara", password="Roles2!", roles="ROLE_USER,ROLE_MANAGER"
        )

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        login_resp = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "clara", "password": "Roles2!"}),
            content_type="application/json",
        )
        token = json.loads(login_resp.data)["token"]

        response = _get_validate(client, token)
        data = json.loads(response.data)
        assert "ROLE_USER" in data["roles"]
        assert "ROLE_MANAGER" in data["roles"]


# ---------------------------------------------------------------------------
# Public paths bypass the JWT filter
# ---------------------------------------------------------------------------

class TestPublicPathsBypassFilter:
    def test_login_path_is_public(self, auth_client_with_session):
        """POST /api/auth/login does not require a token (public path)."""
        client, _ = auth_client_with_session
        # No Authorization header — should not get 403
        response = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "nobody", "password": "nopass"}),
            content_type="application/json",
        )
        assert response.status_code != 403

    def test_health_path_is_public(self, auth_client_with_session):
        """GET /health does not require a token (public path)."""
        client, _ = auth_client_with_session
        response = client.get("/health")
        assert response.status_code == 200
