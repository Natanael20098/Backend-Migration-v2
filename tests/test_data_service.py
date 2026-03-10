"""
Unit tests for the ZCloud Data Management Microservice.

Covers:
  - DatabaseConfig (config.py)
  - PropertyRepository CRUD + custom queries
  - UnderwritingDecisionRepository CRUD + custom queries
  - Property CRUD API endpoints
  - UnderwritingDecision CRUD API endpoints
"""

import os
import sys
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal
from unittest.mock import patch

import pytest
from sqlalchemy import create_engine
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
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture(scope="session")
def in_memory_engine():
    """Create a shared in-memory SQLite engine for the test session."""
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    return engine


@pytest.fixture()
def db_session(in_memory_engine):
    """Yield a transactional session that is rolled back after each test."""
    connection = in_memory_engine.connect()
    transaction = connection.begin()
    Session = sessionmaker(bind=connection, expire_on_commit=False)
    session = Session()
    yield session
    session.close()
    transaction.rollback()
    connection.close()


@pytest.fixture()
def property_repo(db_session):
    return PropertyRepository(db_session)


@pytest.fixture()
def decision_repo(db_session):
    return UnderwritingDecisionRepository(db_session)


def _make_property(**kwargs) -> Property:
    defaults = {
        "address_line1": "100 Test St",
        "city": "Austin",
        "state": "TX",
        "zip_code": "78701",
        "beds": 3,
        "baths": Decimal("2.0"),
        "sqft": 1500,
        "year_built": 2000,
        "property_type": "SINGLE_FAMILY",
    }
    defaults.update(kwargs)
    prop = Property()
    for k, v in defaults.items():
        setattr(prop, k, v)
    return prop


def _make_decision(**kwargs) -> UnderwritingDecision:
    defaults = {
        "loan_application_id": uuid.uuid4(),
        "decision": "APPROVED",
        "dti_ratio": Decimal("0.35"),
        "ltv_ratio": Decimal("0.80"),
        "risk_score": Decimal("75.00"),
        "loan_amount": Decimal("300000.00"),
        "loan_type": "CONVENTIONAL",
        "borrower_name": "Test Borrower",
    }
    defaults.update(kwargs)
    d = UnderwritingDecision()
    for k, v in defaults.items():
        setattr(d, k, v)
    return d


# ---------------------------------------------------------------------------
# Task 4: DatabaseConfig tests
# ---------------------------------------------------------------------------


class TestDatabaseConfig:
    def test_get_database_url_uses_env_vars(self, monkeypatch):
        monkeypatch.setenv("DB_HOST", "myhost")
        monkeypatch.setenv("DB_PORT", "5433")
        monkeypatch.setenv("DB_NAME", "mydb")
        monkeypatch.setenv("DB_USER", "myuser")
        monkeypatch.setenv("DB_PASSWORD", "mypassword")

        # Re-import to pick up new env vars
        from services.data_service import config as cfg

        url = cfg.get_database_url()
        assert "myhost" in url
        assert "5433" in url
        assert "mydb" in url
        assert "myuser" in url
        assert "mypassword" in url

    def test_get_database_url_defaults(self, monkeypatch):
        for var in ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD"):
            monkeypatch.delenv(var, raising=False)

        from services.data_service import config as cfg

        url = cfg.get_database_url()
        assert "localhost" in url
        assert "5432" in url
        assert "zcloud" in url

    def test_pool_settings_from_env(self, monkeypatch):
        monkeypatch.setenv("DB_POOL_SIZE", "8")
        monkeypatch.setenv("DB_MAX_OVERFLOW", "15")

        # Re-read module-level values by re-importing
        import importlib
        import services.data_service.config as cfg_mod
        importlib.reload(cfg_mod)

        assert cfg_mod.POOL_SIZE == 8
        assert cfg_mod.MAX_OVERFLOW == 15


# ---------------------------------------------------------------------------
# Task 2: PropertyRepository tests
# ---------------------------------------------------------------------------


class TestPropertyRepository:
    def test_save_and_find_by_id(self, property_repo, db_session):
        prop = _make_property()
        saved = property_repo.save(prop)
        db_session.commit()

        fetched = property_repo.find_by_id(saved.id)
        assert fetched is not None
        assert fetched.city == "Austin"
        assert fetched.address_line1 == "100 Test St"

    def test_find_by_id_not_found(self, property_repo):
        result = property_repo.find_by_id(uuid.uuid4())
        assert result is None

    def test_find_all(self, property_repo, db_session):
        property_repo.save(_make_property(city="Austin"))
        property_repo.save(_make_property(city="Dallas"))
        db_session.commit()

        all_props = property_repo.find_all()
        assert len(all_props) >= 2

    def test_find_by_city(self, property_repo, db_session):
        property_repo.save(_make_property(city="Houston"))
        property_repo.save(_make_property(city="Houston"))
        property_repo.save(_make_property(city="Dallas"))
        db_session.commit()

        result = property_repo.find_by_city("Houston")
        assert len(result) >= 2
        assert all(p.city == "Houston" for p in result)

    def test_find_by_state(self, property_repo, db_session):
        property_repo.save(_make_property(state="CA"))
        property_repo.save(_make_property(state="CA"))
        db_session.commit()

        result = property_repo.find_by_state("CA")
        assert all(p.state == "CA" for p in result)

    def test_find_by_property_type(self, property_repo, db_session):
        property_repo.save(_make_property(property_type="CONDO"))
        db_session.commit()

        result = property_repo.find_by_property_type("CONDO")
        assert result is not None
        assert result.property_type == "CONDO"

    def test_find_by_property_type_not_found(self, property_repo):
        result = property_repo.find_by_property_type("NONEXISTENT_TYPE_XYZ")
        assert result is None

    def test_find_by_zip_code(self, property_repo, db_session):
        property_repo.save(_make_property(zip_code="90210"))
        property_repo.save(_make_property(zip_code="90210"))
        db_session.commit()

        result = property_repo.find_by_zip_code("90210")
        assert len(result) >= 2
        assert all(p.zip_code == "90210" for p in result)

    def test_find_by_beds_gte(self, property_repo, db_session):
        property_repo.save(_make_property(beds=2))
        property_repo.save(_make_property(beds=4))
        property_repo.save(_make_property(beds=5))
        db_session.commit()

        result = property_repo.find_by_beds_gte(4)
        assert all(p.beds >= 4 for p in result)

    def test_find_by_baths_gte(self, property_repo, db_session):
        property_repo.save(_make_property(baths=Decimal("1.0")))
        property_repo.save(_make_property(baths=Decimal("3.0")))
        db_session.commit()

        result = property_repo.find_by_baths_gte(Decimal("3.0"))
        assert all(p.baths >= Decimal("3.0") for p in result)

    def test_find_by_sqft_between(self, property_repo, db_session):
        property_repo.save(_make_property(sqft=1000))
        property_repo.save(_make_property(sqft=2000))
        property_repo.save(_make_property(sqft=3000))
        db_session.commit()

        result = property_repo.find_by_sqft_between(1500, 2500)
        assert all(1500 <= p.sqft <= 2500 for p in result)

    def test_find_newer_large_properties(self, property_repo, db_session):
        property_repo.save(_make_property(year_built=1990, sqft=1000))
        property_repo.save(_make_property(year_built=2010, sqft=3000))
        property_repo.save(_make_property(year_built=2015, sqft=2500))
        db_session.commit()

        result = property_repo.find_newer_large_properties(year=2005, min_sqft=2000)
        assert all(p.year_built >= 2005 and p.sqft >= 2000 for p in result)

    def test_count_properties_by_city(self, property_repo, db_session):
        property_repo.save(_make_property(city="Miami"))
        property_repo.save(_make_property(city="Miami"))
        property_repo.save(_make_property(city="Denver"))
        db_session.commit()

        count = property_repo.count_properties_by_city("Miami")
        assert count >= 2

    def test_find_distinct_cities_by_state(self, property_repo, db_session):
        property_repo.save(_make_property(city="Abilene", state="TX"))
        property_repo.save(_make_property(city="Abilene", state="TX"))
        property_repo.save(_make_property(city="Beaumont", state="TX"))
        property_repo.save(_make_property(city="Portland", state="OR"))
        db_session.commit()

        cities = property_repo.find_distinct_cities_by_state("TX")
        assert "Abilene" in cities
        assert "Beaumont" in cities
        assert "Portland" not in cities
        assert cities == sorted(cities)  # must be alphabetically sorted

    def test_delete_by_id(self, property_repo, db_session):
        prop = property_repo.save(_make_property())
        db_session.commit()

        deleted = property_repo.delete_by_id(prop.id)
        db_session.commit()

        assert deleted is True
        assert property_repo.find_by_id(prop.id) is None

    def test_delete_by_id_not_found(self, property_repo):
        result = property_repo.delete_by_id(uuid.uuid4())
        assert result is False


# ---------------------------------------------------------------------------
# Task 1: UnderwritingDecisionRepository tests
# ---------------------------------------------------------------------------


class TestUnderwritingDecisionRepository:
    def test_save_and_find_by_id(self, decision_repo, db_session):
        d = _make_decision()
        saved = decision_repo.save(d)
        db_session.commit()

        fetched = decision_repo.find_by_id(saved.id)
        assert fetched is not None
        assert fetched.decision == "APPROVED"

    def test_find_by_id_not_found(self, decision_repo):
        result = decision_repo.find_by_id(uuid.uuid4())
        assert result is None

    def test_find_all(self, decision_repo, db_session):
        decision_repo.save(_make_decision(decision="APPROVED"))
        decision_repo.save(_make_decision(decision="DENIED"))
        db_session.commit()

        all_decisions = decision_repo.find_all()
        assert len(all_decisions) >= 2

    def test_find_by_loan_application_id(self, decision_repo, db_session):
        loan_id = uuid.uuid4()
        other_loan_id = uuid.uuid4()
        decision_repo.save(_make_decision(loan_application_id=loan_id))
        decision_repo.save(_make_decision(loan_application_id=loan_id))
        decision_repo.save(_make_decision(loan_application_id=other_loan_id))
        db_session.commit()

        result = decision_repo.find_by_loan_application_id(loan_id)
        assert len(result) == 2
        assert all(d.loan_application_id == loan_id for d in result)

    def test_find_by_underwriter_id(self, decision_repo, db_session):
        underwriter_id = uuid.uuid4()
        decision_repo.save(_make_decision(underwriter_id=underwriter_id))
        decision_repo.save(_make_decision(underwriter_id=underwriter_id))
        decision_repo.save(_make_decision(underwriter_id=uuid.uuid4()))
        db_session.commit()

        result = decision_repo.find_by_underwriter_id(underwriter_id)
        assert len(result) == 2
        assert all(d.underwriter_id == underwriter_id for d in result)

    def test_find_by_decision(self, decision_repo, db_session):
        decision_repo.save(_make_decision(decision="APPROVED"))
        decision_repo.save(_make_decision(decision="DENIED"))
        decision_repo.save(_make_decision(decision="APPROVED"))
        db_session.commit()

        result = decision_repo.find_by_decision("APPROVED")
        assert len(result) >= 2
        assert all(d.decision == "APPROVED" for d in result)

    def test_delete_by_id(self, decision_repo, db_session):
        d = decision_repo.save(_make_decision())
        db_session.commit()

        deleted = decision_repo.delete_by_id(d.id)
        db_session.commit()

        assert deleted is True
        assert decision_repo.find_by_id(d.id) is None

    def test_delete_by_id_not_found(self, decision_repo):
        result = decision_repo.delete_by_id(uuid.uuid4())
        assert result is False

    def test_update_decision(self, decision_repo, db_session):
        d = decision_repo.save(_make_decision(decision="APPROVED"))
        db_session.commit()

        d.decision = "DENIED"
        d.notes = "Updated after review"
        updated = decision_repo.save(d)
        db_session.commit()

        assert updated.decision == "DENIED"
        assert updated.notes == "Updated after review"


# ---------------------------------------------------------------------------
# Flask app fixture for data_service (in-memory SQLite)
# ---------------------------------------------------------------------------


@pytest.fixture()
def data_app(in_memory_engine, monkeypatch):
    """Create the data service Flask app wired to an in-memory SQLite engine."""
    from sqlalchemy.orm import sessionmaker as sm
    from services.data_service import models as models_mod

    TestSessionFactory = sm(bind=in_memory_engine, expire_on_commit=False)

    import services.data_service.db as db_mod
    monkeypatch.setattr(db_mod, "engine", in_memory_engine)
    monkeypatch.setattr(db_mod, "SessionFactory", TestSessionFactory)
    monkeypatch.setattr(db_mod, "init_db", lambda: None)

    from services.data_service.app import create_app
    app = create_app()
    app.config["TESTING"] = True
    return app, TestSessionFactory


@pytest.fixture()
def data_client(data_app):
    app, _ = data_app
    with app.test_client() as client:
        yield client


# ---------------------------------------------------------------------------
# Task 3: Property CRUD API tests
# ---------------------------------------------------------------------------


class TestPropertyAPI:
    def test_health_check(self, data_client):
        resp = data_client.get("/health")
        assert resp.status_code == 200
        assert resp.get_json()["service"] == "data_service"

    def test_create_property_success(self, data_client):
        payload = {
            "address_line1": "456 Oak Ave",
            "city": "Dallas",
            "state": "TX",
            "zip_code": "75201",
            "beds": 4,
            "baths": 3.0,
            "sqft": 2200,
            "year_built": 2010,
            "property_type": "SINGLE_FAMILY",
        }
        resp = data_client.post(
            "/api/properties",
            json=payload,
            content_type="application/json",
        )
        assert resp.status_code == 201
        data = resp.get_json()
        assert data["city"] == "Dallas"
        assert "id" in data
        assert data["beds"] == 4

    def test_create_property_missing_required_fields(self, data_client):
        resp = data_client.post(
            "/api/properties",
            json={"city": "Dallas"},
            content_type="application/json",
        )
        assert resp.status_code == 400
        body = resp.get_json()
        assert "Validation failed" in body["error"]

    def test_create_property_no_body(self, data_client):
        resp = data_client.post("/api/properties")
        assert resp.status_code == 400

    def test_create_property_invalid_year_built(self, data_client):
        payload = {
            "address_line1": "1 Fake St",
            "city": "Test",
            "state": "TX",
            "year_built": 1200,
        }
        resp = data_client.post(
            "/api/properties", json=payload, content_type="application/json"
        )
        assert resp.status_code == 400

    def test_list_properties(self, data_client):
        # Create one first
        data_client.post(
            "/api/properties",
            json={"address_line1": "1 List St", "city": "X", "state": "TX"},
            content_type="application/json",
        )
        resp = data_client.get("/api/properties")
        assert resp.status_code == 200
        assert isinstance(resp.get_json(), list)

    def test_get_property_by_id(self, data_client):
        create_resp = data_client.post(
            "/api/properties",
            json={"address_line1": "99 Get St", "city": "Austin", "state": "TX"},
            content_type="application/json",
        )
        assert create_resp.status_code == 201
        prop_id = create_resp.get_json()["id"]

        resp = data_client.get(f"/api/properties/{prop_id}")
        assert resp.status_code == 200
        assert resp.get_json()["id"] == prop_id

    def test_get_property_not_found(self, data_client):
        resp = data_client.get(f"/api/properties/{uuid.uuid4()}")
        assert resp.status_code == 404

    def test_get_property_invalid_uuid(self, data_client):
        resp = data_client.get("/api/properties/not-a-uuid")
        assert resp.status_code == 400

    def test_update_property(self, data_client):
        create_resp = data_client.post(
            "/api/properties",
            json={"address_line1": "77 Update Rd", "city": "Austin", "state": "TX"},
            content_type="application/json",
        )
        prop_id = create_resp.get_json()["id"]

        update_resp = data_client.put(
            f"/api/properties/{prop_id}",
            json={"city": "San Antonio", "beds": 5},
            content_type="application/json",
        )
        assert update_resp.status_code == 200
        assert update_resp.get_json()["city"] == "San Antonio"
        assert update_resp.get_json()["beds"] == 5

    def test_update_property_not_found(self, data_client):
        resp = data_client.put(
            f"/api/properties/{uuid.uuid4()}",
            json={"city": "Nowhere"},
            content_type="application/json",
        )
        assert resp.status_code == 404

    def test_delete_property(self, data_client):
        create_resp = data_client.post(
            "/api/properties",
            json={"address_line1": "55 Del Blvd", "city": "Austin", "state": "TX"},
            content_type="application/json",
        )
        prop_id = create_resp.get_json()["id"]

        del_resp = data_client.delete(f"/api/properties/{prop_id}")
        assert del_resp.status_code == 200
        assert "deleted" in del_resp.get_json()["message"].lower()

        # Verify gone
        get_resp = data_client.get(f"/api/properties/{prop_id}")
        assert get_resp.status_code == 404

    def test_delete_property_not_found(self, data_client):
        resp = data_client.delete(f"/api/properties/{uuid.uuid4()}")
        assert resp.status_code == 404


# ---------------------------------------------------------------------------
# Task 5: UnderwritingDecision CRUD API tests
# ---------------------------------------------------------------------------


class TestUnderwritingDecisionAPI:
    def _valid_payload(self):
        return {
            "loan_application_id": str(uuid.uuid4()),
            "decision": "APPROVED",
            "dti_ratio": 0.35,
            "ltv_ratio": 0.80,
            "risk_score": 72.5,
            "loan_amount": 350000.00,
            "loan_type": "CONVENTIONAL",
            "borrower_name": "Alice Example",
            "property_address": "123 Main St, Austin, TX",
        }

    def test_create_decision_returns_201(self, data_client):
        resp = data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        assert resp.status_code == 201
        body = resp.get_json()
        assert body["decision"] == "APPROVED"
        assert "id" in body

    def test_create_decision_missing_required_fields(self, data_client):
        resp = data_client.post(
            "/api/underwriting/decisions",
            json={"decision": "APPROVED"},  # missing loan_application_id
            content_type="application/json",
        )
        assert resp.status_code == 400
        body = resp.get_json()
        assert "Validation failed" in body["error"]

    def test_create_decision_invalid_decision_value(self, data_client):
        payload = self._valid_payload()
        payload["decision"] = "MAYBE"
        resp = data_client.post(
            "/api/underwriting/decisions",
            json=payload,
            content_type="application/json",
        )
        assert resp.status_code == 400

    def test_create_decision_no_body(self, data_client):
        resp = data_client.post("/api/underwriting/decisions")
        assert resp.status_code == 400

    def test_list_decisions_returns_200(self, data_client):
        data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        resp = data_client.get("/api/underwriting/decisions")
        assert resp.status_code == 200
        assert isinstance(resp.get_json(), list)

    def test_get_decision_by_id_returns_200(self, data_client):
        create_resp = data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        did = create_resp.get_json()["id"]
        resp = data_client.get(f"/api/underwriting/decisions/{did}")
        assert resp.status_code == 200
        assert resp.get_json()["id"] == did

    def test_get_decision_not_found(self, data_client):
        resp = data_client.get(f"/api/underwriting/decisions/{uuid.uuid4()}")
        assert resp.status_code == 404

    def test_get_decision_invalid_uuid(self, data_client):
        resp = data_client.get("/api/underwriting/decisions/not-a-uuid")
        assert resp.status_code == 400

    def test_update_decision_returns_200(self, data_client):
        create_resp = data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        did = create_resp.get_json()["id"]

        update_resp = data_client.put(
            f"/api/underwriting/decisions/{did}",
            json={"decision": "CONDITIONAL", "notes": "Needs more docs"},
            content_type="application/json",
        )
        assert update_resp.status_code == 200
        body = update_resp.get_json()
        assert body["decision"] == "CONDITIONAL"
        assert body["notes"] == "Needs more docs"

    def test_update_decision_invalid_decision_value(self, data_client):
        create_resp = data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        did = create_resp.get_json()["id"]

        resp = data_client.put(
            f"/api/underwriting/decisions/{did}",
            json={"decision": "MAYBE"},
            content_type="application/json",
        )
        assert resp.status_code == 400

    def test_update_decision_not_found(self, data_client):
        resp = data_client.put(
            f"/api/underwriting/decisions/{uuid.uuid4()}",
            json={"decision": "DENIED"},
            content_type="application/json",
        )
        assert resp.status_code == 404

    def test_delete_decision_returns_200(self, data_client):
        create_resp = data_client.post(
            "/api/underwriting/decisions",
            json=self._valid_payload(),
            content_type="application/json",
        )
        did = create_resp.get_json()["id"]

        del_resp = data_client.delete(f"/api/underwriting/decisions/{did}")
        assert del_resp.status_code == 200
        assert "deleted" in del_resp.get_json()["message"].lower()

        # Verify gone
        get_resp = data_client.get(f"/api/underwriting/decisions/{did}")
        assert get_resp.status_code == 404

    def test_delete_decision_not_found(self, data_client):
        resp = data_client.delete(f"/api/underwriting/decisions/{uuid.uuid4()}")
        assert resp.status_code == 404

    def test_all_valid_decision_values_accepted(self, data_client):
        for decision_value in ("APPROVED", "DENIED", "CONDITIONAL", "SUSPENDED"):
            payload = self._valid_payload()
            payload["decision"] = decision_value
            resp = data_client.post(
                "/api/underwriting/decisions",
                json=payload,
                content_type="application/json",
            )
            assert resp.status_code == 201, f"Expected 201 for decision={decision_value}"
