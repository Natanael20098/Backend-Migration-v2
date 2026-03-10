"""
Unit tests for Fernet field-level encryption/decryption in config.py.

Acceptance criteria covered:
  - Check password encryption and decryption logic (field-level symmetric encryption)
"""

import pytest
from cryptography.fernet import Fernet, InvalidToken


# ---------------------------------------------------------------------------
# encrypt_field / decrypt_field round-trip
# ---------------------------------------------------------------------------

class TestFernetEncryptDecrypt:
    def test_encrypt_returns_non_empty_string(self):
        """encrypt_field() returns a non-empty ciphertext string."""
        from services.auth_service.config import encrypt_field

        ciphertext = encrypt_field("sensitive-data")
        assert isinstance(ciphertext, str)
        assert len(ciphertext) > 0

    def test_ciphertext_differs_from_plaintext(self):
        """encrypt_field() output does not match the original plaintext."""
        from services.auth_service.config import encrypt_field

        plaintext = "my-secret-value"
        ciphertext = encrypt_field(plaintext)
        assert ciphertext != plaintext

    def test_decrypt_recovers_original_plaintext(self):
        """decrypt_field(encrypt_field(x)) == x for any string x."""
        from services.auth_service.config import encrypt_field, decrypt_field

        plaintext = "recover-me-exactly"
        assert decrypt_field(encrypt_field(plaintext)) == plaintext

    def test_encrypt_decrypt_empty_string(self):
        """encrypt/decrypt round-trip works for an empty string."""
        from services.auth_service.config import encrypt_field, decrypt_field

        assert decrypt_field(encrypt_field("")) == ""

    def test_encrypt_decrypt_special_characters(self):
        """encrypt/decrypt round-trip works for strings with special characters."""
        from services.auth_service.config import encrypt_field, decrypt_field

        plaintext = "p@$$w0rd!#&*()<>{}|"
        assert decrypt_field(encrypt_field(plaintext)) == plaintext

    def test_encrypt_decrypt_unicode(self):
        """encrypt/decrypt round-trip works for unicode content."""
        from services.auth_service.config import encrypt_field, decrypt_field

        plaintext = "用户密码Ünïcödé"
        assert decrypt_field(encrypt_field(plaintext)) == plaintext

    def test_two_encryptions_of_same_value_differ(self):
        """Fernet uses a random IV so encrypting the same value twice yields different ciphertexts."""
        from services.auth_service.config import encrypt_field

        plaintext = "same-value"
        c1 = encrypt_field(plaintext)
        c2 = encrypt_field(plaintext)
        assert c1 != c2

    def test_both_ciphertexts_decrypt_to_same_plaintext(self):
        """Both independently encrypted ciphertexts decrypt to the same original value."""
        from services.auth_service.config import encrypt_field, decrypt_field

        plaintext = "same-value-both"
        c1 = encrypt_field(plaintext)
        c2 = encrypt_field(plaintext)
        assert decrypt_field(c1) == plaintext
        assert decrypt_field(c2) == plaintext

    def test_decrypt_raises_on_tampered_ciphertext(self):
        """decrypt_field() raises an error when the ciphertext has been tampered with."""
        from services.auth_service.config import decrypt_field

        with pytest.raises(Exception):
            decrypt_field("this-is-not-valid-fernet-ciphertext")

    def test_decrypt_raises_on_wrong_key(self):
        """decrypt_field() raises when the ciphertext was encrypted with a different key."""
        from cryptography.fernet import Fernet

        other_key = Fernet.generate_key()
        other_fernet = Fernet(other_key)
        # Encrypt with a different key
        foreign_ciphertext = other_fernet.encrypt(b"secret").decode()

        from services.auth_service.config import decrypt_field

        with pytest.raises(Exception):
            decrypt_field(foreign_ciphertext)

    def test_encrypt_returns_str_not_bytes(self):
        """encrypt_field() returns a str, not a bytes object."""
        from services.auth_service.config import encrypt_field

        result = encrypt_field("check-type")
        assert isinstance(result, str)

    def test_decrypt_returns_str_not_bytes(self):
        """decrypt_field() returns a str, not a bytes object."""
        from services.auth_service.config import encrypt_field, decrypt_field

        ciphertext = encrypt_field("check-decrypt-type")
        result = decrypt_field(ciphertext)
        assert isinstance(result, str)


# ---------------------------------------------------------------------------
# Fernet key configuration
# ---------------------------------------------------------------------------

class TestFernetKeyConfiguration:
    def test_fernet_instance_is_created_with_env_key(self, monkeypatch):
        """When FERNET_KEY is set in the environment, it is used for the Fernet instance."""
        import importlib

        test_key = Fernet.generate_key().decode()
        monkeypatch.setenv("FERNET_KEY", test_key)

        # Reload config module to pick up the env var
        import services.auth_service.config as cfg
        importlib.reload(cfg)

        # Verify we can encrypt and decrypt using the reloaded module
        ciphertext = cfg.encrypt_field("env-key-test")
        assert cfg.decrypt_field(ciphertext) == "env-key-test"

    def test_fernet_fallback_when_key_not_set(self, monkeypatch):
        """When FERNET_KEY is empty, a fresh key is generated and encrypt/decrypt still work."""
        import importlib

        monkeypatch.setenv("FERNET_KEY", "")

        import services.auth_service.config as cfg
        importlib.reload(cfg)

        ciphertext = cfg.encrypt_field("fallback-test")
        assert cfg.decrypt_field(ciphertext) == "fallback-test"
