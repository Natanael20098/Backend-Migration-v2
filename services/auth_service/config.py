"""
Security configuration for the ZCloud Auth Service.

Mirrors the Java SecurityConfig / CorsConfig settings and defines:
  - JWT settings (secret, algorithm, expiry)
  - Trusted CORS origins
  - Rate-limiting defaults
  - Fernet encryption for sensitive data fields
  - Allowed public (unauthenticated) paths
"""

import os
from cryptography.fernet import Fernet

# ---------------------------------------------------------------------------
# JWT settings
# ---------------------------------------------------------------------------

# HS256 secret — must be at least 32 chars in production.
JWT_SECRET: str = os.environ.get("JWT_SECRET", "change-me-in-production-secret-key-32c")
JWT_ALGORITHM: str = "HS256"
# Token lifetime in seconds (default: 24 hours)
JWT_EXPIRATION_SECONDS: int = int(os.environ.get("JWT_EXPIRATION_SECONDS", "86400"))

# Custom claims added to every issued JWT
JWT_ISSUER: str = "zcloud-auth-service"
JWT_AUDIENCE: str = "zcloud-platform"

# ---------------------------------------------------------------------------
# CORS settings — only trusted origins are permitted
# ---------------------------------------------------------------------------

_frontend_url: str = os.environ.get("FRONTEND_URL", "http://localhost:3000")

CORS_ALLOWED_ORIGINS: list[str] = list(
    {
        "http://localhost:3000",
        _frontend_url,
    }
)

CORS_ALLOWED_METHODS: list[str] = ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"]
CORS_ALLOWED_HEADERS: list[str] = ["Content-Type", "Authorization"]
CORS_EXPOSE_HEADERS: list[str] = ["Authorization"]

# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

# Default limits applied to all auth endpoints (Flask-Limiter format)
RATE_LIMIT_DEFAULT: str = os.environ.get("RATE_LIMIT_DEFAULT", "60 per minute")

# Login endpoint — tighter limit to protect against credential stuffing
RATE_LIMIT_LOGIN: str = os.environ.get("RATE_LIMIT_LOGIN", "10 per minute")

# ---------------------------------------------------------------------------
# Encryption — Fernet symmetric encryption for sensitive data fields
# ---------------------------------------------------------------------------

_fernet_key_env: str = os.environ.get("FERNET_KEY", "")

def _get_fernet() -> Fernet:
    """Return a Fernet instance, generating a key if none is configured."""
    key = _fernet_key_env
    if not key:
        # In production this must always be set; fall back to a generated key
        # only in development/test so the service can still start.
        key = Fernet.generate_key().decode()
    return Fernet(key.encode() if isinstance(key, str) else key)


FERNET: Fernet = _get_fernet()


def encrypt_field(plaintext: str) -> str:
    """Encrypt a sensitive string field using Fernet symmetric encryption."""
    return FERNET.encrypt(plaintext.encode()).decode()


def decrypt_field(ciphertext: str) -> str:
    """Decrypt a Fernet-encrypted field back to plaintext."""
    return FERNET.decrypt(ciphertext.encode()).decode()


# ---------------------------------------------------------------------------
# Public paths — requests to these paths bypass JWT authentication
# ---------------------------------------------------------------------------

PUBLIC_PATH_PREFIXES: tuple[str, ...] = (
    "/api/auth/",
    "/health",
)
