"""
Data Management Microservice Validation Tests (Task 2).

Covers:
  - CRUD latency benchmarks for Property and UnderwritingDecision
  - Data consistency: field round-trips for both entities
  - Transaction integrity: no data loss during commit/rollback
  - API-level CRUD consistency via Flask test client

All tests use an in-memory SQLite engine — no live database required.
"""

import os
import sys
import time
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, inspect as sa_inspect
from sqlalchemy.orm import sessionmaker

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from services.data_service.models import Base, Property, UnderwritingDecision
from services.data_service.repositories.property_repository import PropertyRepository
from services.data_service.repositories.underwriting_decision_repository import (
    UnderwritingDecisionRepository,
)

# ---------------------------------------------------------------------------
# Latency benchmark constants
# ---------------------------------------------------------------------------

# Maximum acceptable latency for a single in-process CRUD operation (ms)
# Set generously high so CI/CD variability does not cause false failures.
MAX_SINGLE_OP_MS = 500
MAX_LIST_OP_MS = 1000

# ---------------------------------------------------------------------------
# Session fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def validation_engine():
    """Shared in-memory SQLite engine for all validation tests."""
    eng = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(eng)
    yield eng
    eng.dispose()


@pytest.fixture()
def val_session(validation_engine):
    """Transactional session rolled back after each test for isolation."""
    connection = validation_engine.connect()
    transaction = connection.begin()
    Session = sessionmaker(bind=connection, expire_on_commit=False)
    session = Session()
    yield session
    session.close()
    transaction.rollback()
    connection.close()


@pytest.fixture()
def prop_repo(val_session):
    return PropertyRepository(val_session)


@pytest.fixture()
def decision_repo(val_session):
    return UnderwritingDecisionRepository(val_session)


# ---------------------------------------------------------------------------
# Flask app fixture for API-level validation
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def val_app(validation_engine):
    """Data service Flask app wired to the shared SQLite engine."""
    from sqlalchemy.orm import sessionmaker as sm
    import services.data_service.db as db_mod

    original_engine = db_mod.engine
    original_sf = db_mod.SessionFactory

    TestSF = sm(bind=validation_engine, expire_on_commit=False)
    db_mod.engine = validation_engine
    db_mod.SessionFactory = TestSF
    db_mod.init_db = lambda: None  # type: ignore[assignment]

    from services.data_service.app import create_app
    app = create_app()
    app.config["TESTING"] = True

    yield app

    db_mod.engine = original_engine
    db_mod.SessionFactory = original_sf


@pytest.fixture()
def val_client(val_app):
    with val_app.test_client() as client:
        yield client


# ---------------------------------------------------------------------------
# Helper builders
# ---------------------------------------------------------------------------


def _property(**kwargs) -> Property:
    defaults = {
        "address_line1": "1 Validation Blvd",
        "city": "Austin",
        "state": "TX",
        "zip_code": "78701",
        "beds": 3,
        "baths": Decimal("2.0"),
        "sqft": 1800,
        "year_built": 2005,
        "property_type": "SINGLE_FAMILY",
        "description": "A nice test property",
        "hoa_fee": Decimal("150.00"),
        "last_sold_price": Decimal("320000.00"),
        "last_sold_date": date(2022, 6, 15),
        "current_tax_amount": Decimal("4800.00"),
        "parking_spaces": 2,
        "garage_type": "ATTACHED",
        "lot_size": Decimal("0.25"),
        "latitude": Decimal("30.2672"),
        "longitude": Decimal("-97.7431"),
        "county": "Travis",
        "parcel_number": "123-456-789",
        "zoning": "SF-2",
    }
    defaults.update(kwargs)
    p = Property()
    for k, v in defaults.items():
        setattr(p, k, v)
    return p


def _decision(**kwargs) -> UnderwritingDecision:
    loan_id = uuid.uuid4()
    underwriter_id = uuid.uuid4()
    defaults = {
        "loan_application_id": loan_id,
        "underwriter_id": underwriter_id,
        "decision": "APPROVED",
        "conditions": "None",
        "dti_ratio": Decimal("0.32"),
        "ltv_ratio": Decimal("0.78"),
        "risk_score": Decimal("82.50"),
        "notes": "Solid application",
        "decision_date": datetime(2024, 3, 1, 10, 0, 0, tzinfo=timezone.utc),
        "loan_amount": Decimal("425000.00"),
        "loan_type": "CONVENTIONAL",
        "borrower_name": "Jane Doe",
        "property_address": "1 Validation Blvd, Austin, TX 78701",
    }
    defaults.update(kwargs)
    d = UnderwritingDecision()
    for k, v in defaults.items():
        setattr(d, k, v)
    return d


def _valid_api_property() -> dict:
    return {
        "address_line1": "10 API Ave",
        "city": "Dallas",
        "state": "TX",
        "zip_code": "75201",
        "beds": 4,
        "baths": 3.0,
        "sqft": 2500,
        "year_built": 2015,
        "property_type": "SINGLE_FAMILY",
    }


def _valid_api_decision() -> dict:
    return {
        "loan_application_id": str(uuid.uuid4()),
        "decision": "APPROVED",
        "dti_ratio": 0.30,
        "ltv_ratio": 0.75,
        "risk_score": 88.0,
        "loan_amount": 500000.00,
        "loan_type": "FHA",
        "borrower_name": "John Smith",
        "property_address": "10 API Ave, Dallas, TX 75201",
    }


# ===========================================================================
# LATENCY TESTS — CRUD operations must complete within benchmark thresholds
# ===========================================================================


class TestCRUDLatency:
    """Verify CRUD operations complete within acceptable latency bounds."""

    def test_property_create_latency(self, prop_repo, val_session):
        prop = _property()
        start = time.monotonic()
        prop_repo.save(prop)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Property create took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_property_read_latency(self, prop_repo, val_session):
        prop = _property()
        prop_repo.save(prop)
        val_session.commit()

        start = time.monotonic()
        fetched = prop_repo.find_by_id(prop.id)
        elapsed_ms = (time.monotonic() - start) * 1000

        assert fetched is not None
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Property read took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_property_update_latency(self, prop_repo, val_session):
        prop = _property()
        prop_repo.save(prop)
        val_session.commit()

        prop.city = "San Antonio"
        start = time.monotonic()
        prop_repo.save(prop)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000

        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Property update took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_property_delete_latency(self, prop_repo, val_session):
        prop = _property()
        prop_repo.save(prop)
        val_session.commit()

        start = time.monotonic()
        prop_repo.delete_by_id(prop.id)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000

        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Property delete took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_property_list_latency(self, prop_repo, val_session):
        for i in range(10):
            prop_repo.save(_property(address_line1=f"{i} Bench St"))
        val_session.commit()

        start = time.monotonic()
        results = prop_repo.find_all()
        elapsed_ms = (time.monotonic() - start) * 1000

        assert len(results) >= 10
        assert elapsed_ms < MAX_LIST_OP_MS, (
            f"Property list took {elapsed_ms:.1f}ms — exceeds {MAX_LIST_OP_MS}ms limit"
        )

    def test_decision_create_latency(self, decision_repo, val_session):
        d = _decision()
        start = time.monotonic()
        decision_repo.save(d)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Decision create took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_decision_read_latency(self, decision_repo, val_session):
        d = _decision()
        decision_repo.save(d)
        val_session.commit()

        start = time.monotonic()
        fetched = decision_repo.find_by_id(d.id)
        elapsed_ms = (time.monotonic() - start) * 1000

        assert fetched is not None
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Decision read took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_decision_update_latency(self, decision_repo, val_session):
        d = _decision()
        decision_repo.save(d)
        val_session.commit()

        d.decision = "DENIED"
        start = time.monotonic()
        decision_repo.save(d)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000

        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Decision update took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_decision_delete_latency(self, decision_repo, val_session):
        d = _decision()
        decision_repo.save(d)
        val_session.commit()

        start = time.monotonic()
        decision_repo.delete_by_id(d.id)
        val_session.commit()
        elapsed_ms = (time.monotonic() - start) * 1000

        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"Decision delete took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_api_property_create_latency(self, val_client):
        start = time.monotonic()
        resp = val_client.post(
            "/api/properties",
            json=_valid_api_property(),
            content_type="application/json",
        )
        elapsed_ms = (time.monotonic() - start) * 1000
        assert resp.status_code == 201
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"API property create took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )

    def test_api_decision_create_latency(self, val_client):
        start = time.monotonic()
        resp = val_client.post(
            "/api/underwriting/decisions",
            json=_valid_api_decision(),
            content_type="application/json",
        )
        elapsed_ms = (time.monotonic() - start) * 1000
        assert resp.status_code == 201
        assert elapsed_ms < MAX_SINGLE_OP_MS, (
            f"API decision create took {elapsed_ms:.1f}ms — exceeds {MAX_SINGLE_OP_MS}ms limit"
        )


# ===========================================================================
# DATA CONSISTENCY TESTS — field values survive the full CRUD cycle
# ===========================================================================


class TestPropertyDataConsistency:
    """Verify Property entity fields are consistent after create, read, update."""

    def test_all_fields_persisted_on_create(self, prop_repo, val_session):
        """Every settable field must be retrievable after save + commit."""
        prop = _property()
        saved = prop_repo.save(prop)
        val_session.commit()

        fetched = prop_repo.find_by_id(saved.id)
        assert fetched is not None

        assert fetched.address_line1 == "1 Validation Blvd"
        assert fetched.city == "Austin"
        assert fetched.state == "TX"
        assert fetched.zip_code == "78701"
        assert fetched.beds == 3
        assert float(fetched.baths) == 2.0
        assert fetched.sqft == 1800
        assert fetched.year_built == 2005
        assert fetched.property_type == "SINGLE_FAMILY"
        assert fetched.description == "A nice test property"
        assert float(fetched.hoa_fee) == 150.00
        assert float(fetched.last_sold_price) == 320000.00
        assert fetched.last_sold_date == date(2022, 6, 15)
        assert float(fetched.current_tax_amount) == 4800.00
        assert fetched.parking_spaces == 2
        assert fetched.garage_type == "ATTACHED"
        assert float(fetched.lot_size) == pytest.approx(0.25)
        assert float(fetched.latitude) == pytest.approx(30.2672)
        assert float(fetched.longitude) == pytest.approx(-97.7431)
        assert fetched.county == "Travis"
        assert fetched.parcel_number == "123-456-789"
        assert fetched.zoning == "SF-2"

    def test_to_dict_contains_all_fields(self, prop_repo, val_session):
        """to_dict() must include every entity field."""
        prop = _property()
        saved = prop_repo.save(prop)
        val_session.commit()

        d = saved.to_dict()
        expected_keys = {
            "id", "address_line1", "address_line2", "city", "state", "zip_code",
            "county", "latitude", "longitude", "beds", "baths", "sqft",
            "lot_size", "year_built", "property_type", "description",
            "parking_spaces", "garage_type", "hoa_fee", "zoning", "parcel_number",
            "last_sold_price", "last_sold_date", "current_tax_amount",
            "created_at", "updated_at",
        }
        assert expected_keys <= set(d.keys())

    def test_update_preserves_unchanged_fields(self, prop_repo, val_session):
        """Updating one field must not overwrite other fields."""
        prop = _property(beds=3, baths=Decimal("2.0"), sqft=1800)
        saved = prop_repo.save(prop)
        val_session.commit()

        # Only change city
        saved.city = "Houston"
        prop_repo.save(saved)
        val_session.commit()

        fetched = prop_repo.find_by_id(saved.id)
        assert fetched.city == "Houston"
        assert fetched.beds == 3          # unchanged
        assert float(fetched.baths) == 2.0  # unchanged
        assert fetched.sqft == 1800       # unchanged

    def test_id_is_stable_uuid(self, prop_repo, val_session):
        """The generated ID must be a stable UUID-4."""
        prop = _property()
        saved = prop_repo.save(prop)
        val_session.commit()

        assert isinstance(saved.id, uuid.UUID)
        # Fetching again must return the same ID
        fetched = prop_repo.find_by_id(saved.id)
        assert fetched.id == saved.id

    def test_to_dict_id_is_string(self, prop_repo, val_session):
        """to_dict()['id'] must be a string (JSON-serialisable)."""
        prop = _property()
        saved = prop_repo.save(prop)
        val_session.commit()
        d = saved.to_dict()
        assert isinstance(d["id"], str)
        uuid.UUID(d["id"])  # must be a parseable UUID string

    def test_numeric_fields_round_trip_without_precision_loss(self, prop_repo, val_session):
        """Decimal fields must round-trip without significant precision loss."""
        prop = _property(
            hoa_fee=Decimal("99.99"),
            last_sold_price=Decimal("1234567.89"),
            current_tax_amount=Decimal("12345.67"),
            lot_size=Decimal("1.23456"),
            latitude=Decimal("40.1234567"),
            longitude=Decimal("-74.9876543"),
        )
        saved = prop_repo.save(prop)
        val_session.commit()

        fetched = prop_repo.find_by_id(saved.id)
        assert float(fetched.hoa_fee) == pytest.approx(99.99, rel=1e-4)
        assert float(fetched.last_sold_price) == pytest.approx(1234567.89, rel=1e-4)
        assert float(fetched.current_tax_amount) == pytest.approx(12345.67, rel=1e-4)

    def test_null_optional_fields_acceptable(self, prop_repo, val_session):
        """Optional fields can be None after create."""
        prop = Property()
        prop.address_line1 = "Minimal St"
        prop.city = "Null City"
        prop.state = "TX"
        saved = prop_repo.save(prop)
        val_session.commit()

        fetched = prop_repo.find_by_id(saved.id)
        assert fetched.address_line2 is None
        assert fetched.description is None
        assert fetched.hoa_fee is None

    def test_api_create_read_consistency(self, val_client):
        """Property created via API must be retrievable with the same values."""
        payload = {
            "address_line1": "42 Consistent Way",
            "city": "Round Rock",
            "state": "TX",
            "zip_code": "78664",
            "beds": 5,
            "baths": 3.5,
            "sqft": 3200,
            "year_built": 2019,
            "property_type": "SINGLE_FAMILY",
        }
        create_resp = val_client.post(
            "/api/properties",
            json=payload,
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        created = create_resp.get_json()

        read_resp = val_client.get(f"/api/properties/{created['id']}")
        assert read_resp.status_code == 200
        read = read_resp.get_json()

        assert read["address_line1"] == payload["address_line1"]
        assert read["city"] == payload["city"]
        assert read["state"] == payload["state"]
        assert read["beds"] == payload["beds"]
        assert read["year_built"] == payload["year_built"]


class TestUnderwritingDecisionDataConsistency:
    """Verify UnderwritingDecision fields are consistent after CRUD operations."""

    def test_all_fields_persisted_on_create(self, decision_repo, val_session):
        """Every settable field must be retrievable after save + commit."""
        loan_id = uuid.uuid4()
        underwriter_id = uuid.uuid4()
        d = _decision(
            loan_application_id=loan_id,
            underwriter_id=underwriter_id,
        )
        saved = decision_repo.save(d)
        val_session.commit()

        fetched = decision_repo.find_by_id(saved.id)
        assert fetched is not None
        assert fetched.loan_application_id == loan_id
        assert fetched.underwriter_id == underwriter_id
        assert fetched.decision == "APPROVED"
        assert fetched.conditions == "None"
        assert float(fetched.dti_ratio) == pytest.approx(0.32, rel=1e-4)
        assert float(fetched.ltv_ratio) == pytest.approx(0.78, rel=1e-4)
        assert float(fetched.risk_score) == pytest.approx(82.50, rel=1e-4)
        assert fetched.notes == "Solid application"
        assert fetched.loan_type == "CONVENTIONAL"
        assert float(fetched.loan_amount) == pytest.approx(425000.00, rel=1e-4)
        assert fetched.borrower_name == "Jane Doe"
        assert "Austin" in fetched.property_address

    def test_to_dict_contains_all_fields(self, decision_repo, val_session):
        """to_dict() must include every entity field."""
        d = _decision()
        saved = decision_repo.save(d)
        val_session.commit()

        result = saved.to_dict()
        expected_keys = {
            "id", "loan_application_id", "underwriter_id", "decision",
            "conditions", "dti_ratio", "ltv_ratio", "risk_score", "notes",
            "decision_date", "loan_amount", "loan_type", "borrower_name",
            "property_address", "created_at", "updated_at",
        }
        assert expected_keys <= set(result.keys())

    def test_valid_decision_values_all_accepted(self, decision_repo, val_session):
        """All four valid decision values must persist correctly."""
        for value in UnderwritingDecision.VALID_DECISIONS:
            d = _decision(decision=value)
            saved = decision_repo.save(d)
            val_session.flush()
            assert saved.decision == value

    def test_update_decision_value_consistent(self, decision_repo, val_session):
        """After updating decision, the new value must be retrievable."""
        d = _decision(decision="APPROVED")
        saved = decision_repo.save(d)
        val_session.commit()

        saved.decision = "CONDITIONAL"
        saved.conditions = "Requires additional income documentation"
        decision_repo.save(saved)
        val_session.commit()

        fetched = decision_repo.find_by_id(saved.id)
        assert fetched.decision == "CONDITIONAL"
        assert "income documentation" in fetched.conditions

    def test_loan_application_id_preserved_as_uuid(self, decision_repo, val_session):
        """loan_application_id must come back as the same UUID."""
        loan_id = uuid.uuid4()
        d = _decision(loan_application_id=loan_id)
        saved = decision_repo.save(d)
        val_session.commit()

        fetched = decision_repo.find_by_id(saved.id)
        assert fetched.loan_application_id == loan_id

    def test_to_dict_uuids_are_strings(self, decision_repo, val_session):
        """to_dict() UUID fields must be string-serialised."""
        d = _decision()
        saved = decision_repo.save(d)
        val_session.commit()
        result = saved.to_dict()
        uuid.UUID(result["id"])
        uuid.UUID(result["loan_application_id"])
        uuid.UUID(result["underwriter_id"])

    def test_api_create_read_consistency(self, val_client):
        """Decision created via API must be retrievable with the same values."""
        payload = {
            "loan_application_id": str(uuid.uuid4()),
            "decision": "DENIED",
            "dti_ratio": 0.55,
            "ltv_ratio": 0.92,
            "risk_score": 40.0,
            "loan_amount": 200000.00,
            "loan_type": "VA",
            "borrower_name": "Bob Builder",
            "property_address": "5 Hard Hat Dr, Austin, TX",
        }
        create_resp = val_client.post(
            "/api/underwriting/decisions",
            json=payload,
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        created = create_resp.get_json()

        read_resp = val_client.get(f"/api/underwriting/decisions/{created['id']}")
        assert read_resp.status_code == 200
        read = read_resp.get_json()

        assert read["decision"] == "DENIED"
        assert read["loan_type"] == "VA"
        assert read["borrower_name"] == "Bob Builder"
        assert read["loan_amount"] == pytest.approx(200000.00, rel=1e-4)


# ===========================================================================
# TRANSACTION / NO DATA LOSS TESTS
# ===========================================================================


class TestTransactionIntegrity:
    """Verify no data loss occurs under normal and rollback conditions."""

    def test_commit_persists_property(self, prop_repo, val_session):
        """A committed Property must be findable in the same session."""
        prop = _property(city="PersistCity")
        prop_repo.save(prop)
        val_session.commit()

        result = prop_repo.find_by_id(prop.id)
        assert result is not None
        assert result.city == "PersistCity"

    def test_rollback_undoes_property_create(self, validation_engine):
        """Rolling back a transaction must remove the inserted record."""
        Session = sessionmaker(bind=validation_engine, expire_on_commit=False)
        s = Session()
        try:
            repo = PropertyRepository(s)
            prop = _property(city="GhostCity")
            repo.save(prop)
            prop_id = prop.id
            s.flush()
            s.rollback()  # intentional rollback
        finally:
            s.close()

        # New session must NOT see the rolled-back record
        s2 = Session()
        try:
            result = s2.get(Property, prop_id)
            assert result is None, "Rolled-back record should not be visible"
        finally:
            s2.close()

    def test_commit_persists_decision(self, decision_repo, val_session):
        """A committed UnderwritingDecision must be findable in the same session."""
        d = _decision(borrower_name="Committed Borrower")
        decision_repo.save(d)
        val_session.commit()

        result = decision_repo.find_by_id(d.id)
        assert result is not None
        assert result.borrower_name == "Committed Borrower"

    def test_rollback_undoes_decision_create(self, validation_engine):
        """Rolling back a transaction must remove the inserted UnderwritingDecision."""
        Session = sessionmaker(bind=validation_engine, expire_on_commit=False)
        s = Session()
        try:
            repo = UnderwritingDecisionRepository(s)
            d = _decision(borrower_name="Ghost Borrower")
            repo.save(d)
            decision_id = d.id
            s.flush()
            s.rollback()
        finally:
            s.close()

        s2 = Session()
        try:
            result = s2.get(UnderwritingDecision, decision_id)
            assert result is None, "Rolled-back decision should not be visible"
        finally:
            s2.close()

    def test_partial_failure_does_not_corrupt_existing_records(
        self, validation_engine
    ):
        """An error inserting record B must not affect previously committed record A."""
        from sqlalchemy.orm import sessionmaker as sm

        Session = sm(bind=validation_engine, expire_on_commit=False)

        # --- Transaction 1: commit record A cleanly ---
        s1 = Session()
        prop_a_id = None
        try:
            repo1 = PropertyRepository(s1)
            prop_a = _property(city="SafeCity", address_line1="1 Safe Ln")
            repo1.save(prop_a)
            prop_a_id = prop_a.id
            s1.commit()
        finally:
            s1.close()

        # --- Transaction 2: attempt a bad insert and roll it back ---
        s2 = Session()
        try:
            repo2 = PropertyRepository(s2)
            prop_b = _property(city="DangerCity", address_line1="2 Danger Ln")
            prop_b.id = prop_a_id  # duplicate PK forces conflict
            try:
                repo2.save(prop_b)
                s2.flush()
            except Exception:
                s2.rollback()
        finally:
            s2.close()

        # --- Transaction 3: verify record A still intact ---
        s3 = Session()
        try:
            result = s3.get(Property, prop_a_id)
            assert result is not None
            assert result.city == "SafeCity"
        finally:
            s3.close()

    def test_multiple_records_persist_in_single_transaction(
        self, prop_repo, val_session
    ):
        """Multiple inserts in a single commit must all persist."""
        ids = []
        for i in range(5):
            prop = _property(
                city=f"City{i}",
                address_line1=f"{i} Multi St",
            )
            prop_repo.save(prop)
            ids.append(prop.id)
        val_session.commit()

        for prop_id in ids:
            assert prop_repo.find_by_id(prop_id) is not None

    def test_delete_is_durable_after_commit(self, prop_repo, val_session):
        """A deleted record must not reappear after commit."""
        prop = _property(city="DeleteMe")
        prop_repo.save(prop)
        val_session.commit()

        prop_repo.delete_by_id(prop.id)
        val_session.commit()

        assert prop_repo.find_by_id(prop.id) is None

    def test_no_data_loss_across_sessions(self, validation_engine):
        """Data committed in session 1 must be visible in an independent session 2."""
        Session = sessionmaker(bind=validation_engine, expire_on_commit=False)
        prop_id = None

        # Session 1 — write
        s1 = Session()
        try:
            repo1 = PropertyRepository(s1)
            prop = _property(city="CrossSession")
            repo1.save(prop)
            prop_id = prop.id
            s1.commit()
        finally:
            s1.close()

        # Session 2 — read (independent session)
        s2 = Session()
        try:
            result = s2.get(Property, prop_id)
            assert result is not None
            assert result.city == "CrossSession"
        finally:
            s2.close()

    def test_api_delete_prevents_subsequent_read(self, val_client):
        """Deleting via API must return 404 on subsequent GET."""
        create_resp = val_client.post(
            "/api/properties",
            json=_valid_api_property(),
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        prop_id = create_resp.get_json()["id"]

        del_resp = val_client.delete(f"/api/properties/{prop_id}")
        assert del_resp.status_code == 200

        get_resp = val_client.get(f"/api/properties/{prop_id}")
        assert get_resp.status_code == 404

    def test_api_decision_delete_prevents_subsequent_read(self, val_client):
        """Deleting a decision via API must return 404 on subsequent GET."""
        create_resp = val_client.post(
            "/api/underwriting/decisions",
            json=_valid_api_decision(),
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        did = create_resp.get_json()["id"]

        del_resp = val_client.delete(f"/api/underwriting/decisions/{did}")
        assert del_resp.status_code == 200

        get_resp = val_client.get(f"/api/underwriting/decisions/{did}")
        assert get_resp.status_code == 404

    def test_full_crud_lifecycle_property(self, val_client):
        """Property: Create → Read → Update → Delete lifecycle is loss-free."""
        # Create
        create_resp = val_client.post(
            "/api/properties",
            json={
                "address_line1": "1 Lifecycle Ln",
                "city": "LifeCity",
                "state": "TX",
                "beds": 3,
            },
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        pid = create_resp.get_json()["id"]

        # Read — must match
        read_resp = val_client.get(f"/api/properties/{pid}")
        assert read_resp.status_code == 200
        assert read_resp.get_json()["city"] == "LifeCity"

        # Update — change city
        update_resp = val_client.put(
            f"/api/properties/{pid}",
            json={"city": "UpdatedCity", "beds": 5},
            content_type="application/json",
        )
        assert update_resp.status_code == 200
        assert update_resp.get_json()["city"] == "UpdatedCity"
        assert update_resp.get_json()["beds"] == 5

        # Delete
        del_resp = val_client.delete(f"/api/properties/{pid}")
        assert del_resp.status_code == 200

        # Confirm gone
        assert val_client.get(f"/api/properties/{pid}").status_code == 404

    def test_full_crud_lifecycle_underwriting_decision(self, val_client):
        """UnderwritingDecision: Create → Read → Update → Delete lifecycle."""
        payload = _valid_api_decision()
        create_resp = val_client.post(
            "/api/underwriting/decisions",
            json=payload,
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        did = create_resp.get_json()["id"]

        # Read
        read_resp = val_client.get(f"/api/underwriting/decisions/{did}")
        assert read_resp.status_code == 200
        assert read_resp.get_json()["decision"] == "APPROVED"

        # Update
        update_resp = val_client.put(
            f"/api/underwriting/decisions/{did}",
            json={"decision": "SUSPENDED", "notes": "Under review"},
            content_type="application/json",
        )
        assert update_resp.status_code == 200
        assert update_resp.get_json()["decision"] == "SUSPENDED"
        assert "review" in update_resp.get_json()["notes"]

        # Delete
        del_resp = val_client.delete(f"/api/underwriting/decisions/{did}")
        assert del_resp.status_code == 200

        # Confirm gone
        assert val_client.get(f"/api/underwriting/decisions/{did}").status_code == 404
