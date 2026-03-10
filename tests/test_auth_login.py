"""
Unit tests for POST /api/auth/login

Acceptance criteria covered:
  - Test successful login returns valid token and user information
  - Ensure invalid credentials return 401 error with correct message
"""

import json
import uuid
from unittest.mock import MagicMock, patch

import bcrypt
import pytest

from tests.conftest import make_user, TEST_JWT_SECRET


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _post_login(client, username, password):
    return client.post(
        "/api/auth/login",
        data=json.dumps({"username": username, "password": password}),
        content_type="application/json",
    )


# ---------------------------------------------------------------------------
# Successful login
# ---------------------------------------------------------------------------

class TestLoginSuccess:
    def test_returns_200_on_valid_credentials(self, auth_client_with_session):
        """POST /api/auth/login with correct credentials returns HTTP 200."""
        client, session_factory = auth_client_with_session
        user = make_user(username="alice", password="Secret99!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "alice", "Secret99!")
        assert response.status_code == 200

    def test_response_contains_token(self, auth_client_with_session):
        """Successful login response includes a non-empty 'token' field."""
        client, session_factory = auth_client_with_session
        user = make_user(username="bob", password="MyPass1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "bob", "MyPass1!")
        data = json.loads(response.data)
        assert "token" in data
        assert isinstance(data["token"], str)
        assert len(data["token"]) > 0

    def test_response_contains_user_id(self, auth_client_with_session):
        """Successful login response includes the user's UUID as 'user_id'."""
        client, session_factory = auth_client_with_session
        user = make_user(username="carol", password="Pass1234!")

        db = session_factory()
        db.add(user)
        db.commit()
        user_id = str(user.id)
        db.close()

        response = _post_login(client, "carol", "Pass1234!")
        data = json.loads(response.data)
        assert "user_id" in data
        assert data["user_id"] == user_id

    def test_response_contains_roles(self, auth_client_with_session):
        """Successful login response includes the user's roles list."""
        client, session_factory = auth_client_with_session
        user = make_user(username="dave", password="Roles123!", roles="ROLE_USER,ROLE_ADMIN")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "dave", "Roles123!")
        data = json.loads(response.data)
        assert "roles" in data
        assert "ROLE_USER" in data["roles"]
        assert "ROLE_ADMIN" in data["roles"]

    def test_issued_token_is_valid_jwt(self, auth_client_with_session):
        """The token returned by login decodes to a valid JWT with expected claims."""
        import jwt as pyjwt

        client, session_factory = auth_client_with_session
        user = make_user(username="eve", password="ValidJwt1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "eve", "ValidJwt1!")
        data = json.loads(response.data)
        token = data["token"]

        claims = pyjwt.decode(
            token,
            TEST_JWT_SECRET,
            algorithms=["HS256"],
            options={"verify_aud": False},
        )
        assert claims["sub"] == str(user.id)
        assert claims["email"] == user.email
        assert "exp" in claims
        assert "iat" in claims

    def test_token_contains_subject_equal_to_user_id(self, auth_client_with_session):
        """JWT 'sub' claim equals the authenticated user's ID."""
        import jwt as pyjwt

        client, session_factory = auth_client_with_session
        user = make_user(username="frank", password="SubTest1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "frank", "SubTest1!")
        data = json.loads(response.data)
        claims = pyjwt.decode(
            data["token"],
            TEST_JWT_SECRET,
            algorithms=["HS256"],
            options={"verify_aud": False},
        )
        assert claims["sub"] == data["user_id"]

    def test_session_created_in_db_after_login(self, auth_client_with_session):
        """A new active session record is persisted in the database upon login."""
        from services.auth_service.models import Session

        client, session_factory = auth_client_with_session
        user = make_user(username="grace", password="Session1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        _post_login(client, "grace", "Session1!")

        db = session_factory()
        sessions = db.query(Session).filter(Session.user_id == user.id).all()
        db.close()
        assert len(sessions) == 1
        assert sessions[0].is_active is True

    def test_response_content_type_is_json(self, auth_client_with_session):
        """Successful login returns Content-Type: application/json."""
        client, session_factory = auth_client_with_session
        user = make_user(username="henry", password="Ct1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "henry", "Ct1!")
        assert "application/json" in response.content_type


# ---------------------------------------------------------------------------
# Invalid credentials — 401
# ---------------------------------------------------------------------------

class TestLoginInvalidCredentials:
    def test_returns_401_for_wrong_password(self, auth_client_with_session):
        """POST /api/auth/login with wrong password returns HTTP 401."""
        client, session_factory = auth_client_with_session
        user = make_user(username="ivan", password="RealPass1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "ivan", "WrongPassword!")
        assert response.status_code == 401

    def test_returns_401_for_unknown_username(self, auth_client_with_session):
        """POST /api/auth/login with a nonexistent username returns HTTP 401."""
        client, _ = auth_client_with_session
        response = _post_login(client, "nonexistent_user", "AnyPass1!")
        assert response.status_code == 401

    def test_error_message_on_invalid_credentials(self, auth_client_with_session):
        """401 response body contains 'Invalid username or password' message."""
        client, session_factory = auth_client_with_session
        user = make_user(username="julia", password="Correct1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "julia", "Wrong!")
        data = json.loads(response.data)
        assert "error" in data
        assert "Invalid username or password" in data["error"]

    def test_returns_401_unknown_user_same_error_message(self, auth_client_with_session):
        """The error message for unknown user matches that for wrong password (no enumeration)."""
        client, _ = auth_client_with_session

        response = _post_login(client, "ghost_user", "AnyPassword!")
        data = json.loads(response.data)
        assert "Invalid username or password" in data["error"]

    def test_returns_400_for_missing_username(self, auth_client_with_session):
        """POST /api/auth/login without username field returns HTTP 400."""
        client, _ = auth_client_with_session
        response = client.post(
            "/api/auth/login",
            data=json.dumps({"password": "SomePass1!"}),
            content_type="application/json",
        )
        assert response.status_code == 400

    def test_returns_400_for_missing_password(self, auth_client_with_session):
        """POST /api/auth/login without password field returns HTTP 400."""
        client, _ = auth_client_with_session
        response = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "someone"}),
            content_type="application/json",
        )
        assert response.status_code == 400

    def test_returns_400_for_empty_body(self, auth_client_with_session):
        """POST /api/auth/login with empty body returns HTTP 400."""
        client, _ = auth_client_with_session
        response = client.post(
            "/api/auth/login",
            data="",
            content_type="application/json",
        )
        assert response.status_code == 400

    def test_returns_400_for_non_json_body(self, auth_client_with_session):
        """POST /api/auth/login with non-JSON content-type returns HTTP 400."""
        client, _ = auth_client_with_session
        response = client.post(
            "/api/auth/login",
            data="username=foo&password=bar",
            content_type="application/x-www-form-urlencoded",
        )
        assert response.status_code == 400

    def test_returns_403_for_disabled_account(self, auth_client_with_session):
        """POST /api/auth/login for a disabled account returns HTTP 403."""
        client, session_factory = auth_client_with_session
        user = make_user(username="karen", password="Disabled1!", is_active=False)

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "karen", "Disabled1!")
        assert response.status_code == 403

    def test_disabled_account_error_message(self, auth_client_with_session):
        """Disabled account response body contains 'disabled' message."""
        client, session_factory = auth_client_with_session
        user = make_user(username="leo", password="Disabled2!", is_active=False)

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = _post_login(client, "leo", "Disabled2!")
        data = json.loads(response.data)
        assert "error" in data
        assert "disabled" in data["error"].lower()

    def test_empty_username_returns_400(self, auth_client_with_session):
        """POST /api/auth/login with whitespace-only username returns 400."""
        client, _ = auth_client_with_session
        response = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "   ", "password": "Pass1!"}),
            content_type="application/json",
        )
        assert response.status_code == 400
