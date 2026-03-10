"""
SQLAlchemy ORM models for the ZCloud Auth Service.

Tables:
  users    — platform users with bcrypt-hashed passwords and roles
  sessions — active JWT session tokens (invalidated on logout)
"""

import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, Column, DateTime, String, Text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class User(Base):
    """Platform user account."""

    __tablename__ = "users"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username = Column(String(255), unique=True, nullable=False)
    email = Column(String(255), unique=True, nullable=False)
    # bcrypt hash stored as text
    password_hash = Column(Text, nullable=False)
    # Comma-separated roles, e.g. "ROLE_USER,ROLE_ADMIN"
    roles = Column(String(500), nullable=False, default="ROLE_USER")
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )

    def get_roles(self) -> list[str]:
        """Return roles as a list."""
        return [r.strip() for r in self.roles.split(",") if r.strip()]

    def __repr__(self) -> str:
        return f"<User id={self.id} username={self.username!r}>"


class Session(Base):
    """Active authentication session / JWT token record."""

    __tablename__ = "auth_sessions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    user_id = Column(UUID(as_uuid=True), nullable=False)
    # Stored as the raw JWT string to allow efficient invalidation on logout
    token = Column(Text, unique=True, nullable=False)
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
    expires_at = Column(DateTime(timezone=True), nullable=False)

    def __repr__(self) -> str:
        return f"<Session id={self.id} user_id={self.user_id} active={self.is_active}>"
