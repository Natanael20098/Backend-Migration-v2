"""
SQLAlchemy ORM models for the ZCloud Data Management Microservice.

Tables:
  properties              — real estate property records
  underwriting_decisions  — underwriting decisions for loan applications

Migrated from Java entities:
  com.zcloud.platform.model.Property
  com.zcloud.platform.model.UnderwritingDecision
"""

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    DateTime,
    Integer,
    Numeric,
    String,
    Text,
    Date,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


class Property(Base):
    """Real estate property entity.

    Migrated from: com.zcloud.platform.model.Property
    """

    __tablename__ = "properties"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    address_line1 = Column(String(255))
    address_line2 = Column(String(255))
    city = Column(String(100))
    state = Column(String(50))
    zip_code = Column(String(10))
    county = Column(String(100))
    latitude = Column(Numeric(10, 7))
    longitude = Column(Numeric(10, 7))
    beds = Column(Integer)
    baths = Column(Numeric(4, 1))
    sqft = Column(Integer)
    lot_size = Column(Numeric(12, 2))
    year_built = Column(Integer)
    property_type = Column(String(100))
    description = Column(Text)
    parking_spaces = Column(Integer)
    garage_type = Column(String(50))
    hoa_fee = Column(Numeric(12, 2))
    zoning = Column(String(50))
    parcel_number = Column(String(100))
    last_sold_price = Column(Numeric(15, 2))
    last_sold_date = Column(Date)
    current_tax_amount = Column(Numeric(12, 2))
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

    def to_dict(self) -> dict:
        return {
            "id": str(self.id),
            "address_line1": self.address_line1,
            "address_line2": self.address_line2,
            "city": self.city,
            "state": self.state,
            "zip_code": self.zip_code,
            "county": self.county,
            "latitude": float(self.latitude) if self.latitude is not None else None,
            "longitude": float(self.longitude) if self.longitude is not None else None,
            "beds": self.beds,
            "baths": float(self.baths) if self.baths is not None else None,
            "sqft": self.sqft,
            "lot_size": float(self.lot_size) if self.lot_size is not None else None,
            "year_built": self.year_built,
            "property_type": self.property_type,
            "description": self.description,
            "parking_spaces": self.parking_spaces,
            "garage_type": self.garage_type,
            "hoa_fee": float(self.hoa_fee) if self.hoa_fee is not None else None,
            "zoning": self.zoning,
            "parcel_number": self.parcel_number,
            "last_sold_price": float(self.last_sold_price) if self.last_sold_price is not None else None,
            "last_sold_date": self.last_sold_date.isoformat() if self.last_sold_date else None,
            "current_tax_amount": float(self.current_tax_amount) if self.current_tax_amount is not None else None,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }

    def __repr__(self) -> str:
        return f"<Property id={self.id} address={self.address_line1!r} city={self.city!r}>"


class UnderwritingDecision(Base):
    """Underwriting decision for a loan application.

    Migrated from: com.zcloud.platform.model.UnderwritingDecision

    Note: loan_application_id and underwriter_id are stored as plain UUIDs
    (foreign-key references to loan/user services — no ORM relationship to avoid
    cross-service coupling in the microservices architecture).
    """

    __tablename__ = "underwriting_decisions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    # FK to loan_applications table (managed by loan service)
    loan_application_id = Column(UUID(as_uuid=True), nullable=False)
    # FK to agents/users table — kept as underwriter_id per original schema
    underwriter_id = Column(UUID(as_uuid=True))
    # Values: APPROVED, DENIED, CONDITIONAL, SUSPENDED
    decision = Column(String(50))
    conditions = Column(Text)
    dti_ratio = Column(Numeric(6, 4))
    ltv_ratio = Column(Numeric(6, 4))
    risk_score = Column(Numeric(6, 2))
    notes = Column(Text)
    decision_date = Column(DateTime(timezone=True))
    loan_amount = Column(Numeric(15, 2))
    loan_type = Column(String(50))
    borrower_name = Column(String(255))
    property_address = Column(String(500))
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

    VALID_DECISIONS = {"APPROVED", "DENIED", "CONDITIONAL", "SUSPENDED"}

    def to_dict(self) -> dict:
        return {
            "id": str(self.id),
            "loan_application_id": str(self.loan_application_id),
            "underwriter_id": str(self.underwriter_id) if self.underwriter_id else None,
            "decision": self.decision,
            "conditions": self.conditions,
            "dti_ratio": float(self.dti_ratio) if self.dti_ratio is not None else None,
            "ltv_ratio": float(self.ltv_ratio) if self.ltv_ratio is not None else None,
            "risk_score": float(self.risk_score) if self.risk_score is not None else None,
            "notes": self.notes,
            "decision_date": self.decision_date.isoformat() if self.decision_date else None,
            "loan_amount": float(self.loan_amount) if self.loan_amount is not None else None,
            "loan_type": self.loan_type,
            "borrower_name": self.borrower_name,
            "property_address": self.property_address,
            "created_at": self.created_at.isoformat() if self.created_at else None,
            "updated_at": self.updated_at.isoformat() if self.updated_at else None,
        }

    def __repr__(self) -> str:
        return (
            f"<UnderwritingDecision id={self.id} "
            f"loan={self.loan_application_id} decision={self.decision!r}>"
        )
