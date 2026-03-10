"""
Unit tests for password encryption and verification logic (bcrypt).

Acceptance criteria covered:
  - Check password encryption and decryption logic
"""

import bcrypt
import pytest

from tests.conftest import make_user


# ---------------------------------------------------------------------------
# bcrypt hashing behaviour
# ---------------------------------------------------------------------------

class TestPasswordEncryption:
    def test_password_hash_differs_from_plaintext(self):
        """The stored password_hash must not equal the plaintext password."""
        user = make_user(password="PlainSecret1!")
        assert user.password_hash != "PlainSecret1!"

    def test_password_hash_is_valid_bcrypt(self):
        """The stored password_hash starts with the bcrypt identifier prefix."""
        user = make_user(password="BcryptCheck1!")
        # bcrypt hashes begin with $2b$ or $2a$
        assert user.password_hash.startswith("$2")

    def test_correct_password_verifies_successfully(self):
        """bcrypt.checkpw returns True when the plaintext matches the stored hash."""
        plaintext = "CorrectPass99!"
        user = make_user(password=plaintext)
        assert bcrypt.checkpw(plaintext.encode(), user.password_hash.encode())

    def test_wrong_password_fails_verification(self):
        """bcrypt.checkpw returns False for an incorrect plaintext password."""
        user = make_user(password="RealPassword1!")
        assert not bcrypt.checkpw("WrongPassword!".encode(), user.password_hash.encode())

    def test_empty_string_does_not_verify_against_real_hash(self):
        """An empty string does not match a hash generated from a non-empty password."""
        user = make_user(password="NonEmptyPass1!")
        assert not bcrypt.checkpw(b"", user.password_hash.encode())

    def test_two_hashes_of_same_password_are_different(self):
        """Hashing the same password twice produces two distinct hashes (salt randomness)."""
        password = "SamePassword99!"
        hash1 = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        hash2 = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        assert hash1 != hash2

    def test_both_hashes_verify_against_original_password(self):
        """Both independently generated hashes verify correctly against the plaintext."""
        password = "BothVerify1!"
        hash1 = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        hash2 = bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()
        assert bcrypt.checkpw(password.encode(), hash1.encode())
        assert bcrypt.checkpw(password.encode(), hash2.encode())

    def test_hash_is_stored_as_string(self):
        """The password_hash attribute on a User is stored as a str, not bytes."""
        user = make_user(password="StringHash1!")
        assert isinstance(user.password_hash, str)

    def test_case_sensitive_password_verification(self):
        """Password matching is case-sensitive: 'Password' != 'password'."""
        user = make_user(password="CaseSensitive1!")
        assert not bcrypt.checkpw("casesensitive1!".encode(), user.password_hash.encode())

    def test_login_endpoint_verifies_password(self, auth_client_with_session):
        """The login endpoint correctly rejects a case-altered version of the password."""
        import json

        client, session_factory = auth_client_with_session
        user = make_user(username="delta", password="UpperPass1!")

        db = session_factory()
        db.add(user)
        db.commit()
        db.close()

        response = client.post(
            "/api/auth/login",
            data=json.dumps({"username": "delta", "password": "upperpass1!"}),
            content_type="application/json",
        )
        assert response.status_code == 401


# ---------------------------------------------------------------------------
# Password hash cost factor
# ---------------------------------------------------------------------------

class TestPasswordHashStrength:
    def test_bcrypt_uses_minimum_cost_factor(self):
        """The bcrypt salt is generated with at least cost factor 10."""
        salt = bcrypt.gensalt()
        # Salt format: $2b$<rounds>$<salt_data>
        parts = salt.decode().split("$")
        rounds = int(parts[2])
        assert rounds >= 10

    def test_make_user_produces_usable_hash(self):
        """make_user helper produces a hash that can be used in auth flow."""
        password = "UsableHash1!"
        user = make_user(password=password)
        # Simulate what the login endpoint does
        result = bcrypt.checkpw(password.encode(), user.password_hash.encode())
        assert result is True
