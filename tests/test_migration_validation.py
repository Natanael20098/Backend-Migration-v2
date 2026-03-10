"""
Final Integration and Migration Validation Tests — Task 2/2

Covers all acceptance criteria:
  1. Successful integration of all microservices with data consistency validation
  2. No error logs related to integration issues across services
  3. Verify migration validation scripts run without exceptions
  4. Achieve zero critical issues in final integration test results
  5. Create a comprehensive migration validation report for stakeholders

All tests run without a live database.
"""

import json
import logging
import os
import sys
import uuid
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, inspect as sa_inspect
from sqlalchemy.orm import sessionmaker

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)


# ============================================================================
# AC 1 & 2 — Data consistency validation across both services
# ============================================================================


class TestAuthServiceDataConsistency:
    """
    Validates that the auth service ORM models maintain data consistency:
    data written equals data read; no silent truncation or type corruption.
    """

    @pytest.fixture(scope="class")
    def sqlite_session(self):
        from services.auth_service.models import Base, User, Session

        engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
        )
        Base.metadata.create_all(engine)
        SF = sessionmaker(bind=engine, expire_on_commit=False)
        db = SF()
        yield db, User, Session, engine
        db.close()

    def test_user_fields_round_trip(self, sqlite_session):
        """User fields written to DB are returned unchanged on read."""
        db, User, Session, _ = sqlite_session
        u = User()
        u.username = "roundtrip_user"
        u.email = "roundtrip@test.com"
        u.password_hash = "$2b$12$fakehash"
        u.roles = "ROLE_USER,ROLE_ADMIN"
        u.is_active = True
        db.add(u)
        db.commit()

        fetched = db.query(User).filter(User.username == "roundtrip_user").first()
        assert fetched is not None
        assert fetched.email == "roundtrip@test.com"
        assert fetched.password_hash == "$2b$12$fakehash"
        assert fetched.roles == "ROLE_USER,ROLE_ADMIN"
        assert fetched.is_active is True

    def test_user_get_roles_returns_correct_list(self, sqlite_session):
        """User.get_roles() parses comma-separated roles string correctly."""
        db, User, _, _ = sqlite_session
        u = User()
        u.username = "roles_user"
        u.email = "roles@test.com"
        u.password_hash = "$2b$12$fakehash"
        u.roles = "ROLE_USER,ROLE_ADMIN,ROLE_DATA"
        u.is_active = True
        db.add(u)
        db.commit()

        fetched = db.query(User).filter(User.username == "roles_user").first()
        roles = fetched.get_roles()
        assert isinstance(roles, list)
        assert "ROLE_USER" in roles
        assert "ROLE_ADMIN" in roles
        assert "ROLE_DATA" in roles

    def test_user_single_role_get_roles(self, sqlite_session):
        """Single-role user returns a one-element list from get_roles()."""
        db, User, _, _ = sqlite_session
        u = User()
        u.username = "single_role"
        u.email = "single@test.com"
        u.password_hash = "$2b$12$hash"
        u.roles = "ROLE_VIEWER"
        u.is_active = True
        db.add(u)
        db.commit()

        fetched = db.query(User).filter(User.username == "single_role").first()
        roles = fetched.get_roles()
        assert roles == ["ROLE_VIEWER"]

    def test_session_token_persists_unchanged(self, sqlite_session):
        """Session token stored and retrieved without modification."""
        import bcrypt as _bcrypt
        db, User, AuthSession, _ = sqlite_session

        u = User()
        u.username = "session_persist"
        u.email = "session@test.com"
        u.password_hash = "$2b$12$ph"
        u.roles = "ROLE_USER"
        u.is_active = True
        db.add(u)
        db.flush()

        raw_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.signature"
        s = AuthSession()
        s.user_id = u.id
        s.token = raw_token
        s.is_active = True
        s.expires_at = datetime.now(timezone.utc)
        db.add(s)
        db.commit()

        fetched = db.query(AuthSession).filter(AuthSession.token == raw_token).first()
        assert fetched is not None
        assert fetched.token == raw_token
        assert fetched.is_active is True
        assert fetched.user_id == u.id

    def test_session_deactivation_persists(self, sqlite_session):
        """Setting is_active=False on a session persists correctly."""
        db, User, AuthSession, _ = sqlite_session

        u = User()
        u.username = "deact_user"
        u.email = "deact@test.com"
        u.password_hash = "$2b$12$ph"
        u.roles = "ROLE_USER"
        u.is_active = True
        db.add(u)
        db.flush()

        s = AuthSession()
        s.user_id = u.id
        s.token = "deactivation.test.token"
        s.is_active = True
        s.expires_at = datetime.now(timezone.utc)
        db.add(s)
        db.commit()

        # Deactivate
        s.is_active = False
        db.commit()

        fetched = db.query(AuthSession).filter(
            AuthSession.token == "deactivation.test.token"
        ).first()
        assert fetched.is_active is False

    def test_no_sql_errors_on_repeated_schema_creation(self):
        """Base.metadata.create_all() is idempotent — calling twice raises no error."""
        from services.auth_service.models import Base

        engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
        )
        Base.metadata.create_all(engine)
        Base.metadata.create_all(engine)  # second call must not raise


class TestDataServiceDataConsistency:
    """
    Validates that the data service ORM models maintain data consistency.
    """

    @pytest.fixture(scope="class")
    def sqlite_session(self):
        from services.data_service.models import Base, Property, UnderwritingDecision

        engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
        )
        Base.metadata.create_all(engine)
        SF = sessionmaker(bind=engine, expire_on_commit=False)
        db = SF()
        yield db, Property, UnderwritingDecision, engine
        db.close()

    def test_property_fields_round_trip(self, sqlite_session):
        """Property fields written to DB match what is retrieved."""
        db, Property, _, _ = sqlite_session

        p = Property()
        p.address_line1 = "42 Consistency Blvd"
        p.city = "DataCity"
        p.state = "DC"
        p.zip_code = "20001"
        p.beds = 3
        p.baths = Decimal("2.5")
        p.sqft = 1600
        p.year_built = 2010
        p.property_type = "CONDO"
        db.add(p)
        db.commit()

        fetched = db.query(Property).filter(
            Property.address_line1 == "42 Consistency Blvd"
        ).first()
        assert fetched is not None
        assert fetched.city == "DataCity"
        assert fetched.state == "DC"
        assert fetched.beds == 3
        assert fetched.year_built == 2010
        assert fetched.property_type == "CONDO"

    def test_property_to_dict_contains_all_fields(self, sqlite_session):
        """Property.to_dict() includes all required fields."""
        db, Property, _, _ = sqlite_session

        p = Property()
        p.address_line1 = "Dict Test St"
        p.city = "DictCity"
        p.state = "DT"
        db.add(p)
        db.commit()

        d = p.to_dict()
        for key in ("id", "address_line1", "city", "state", "beds", "baths",
                     "sqft", "year_built", "created_at", "updated_at"):
            assert key in d, f"Missing key '{key}' in to_dict()"

    def test_property_numeric_fields_preserve_precision(self, sqlite_session):
        """Decimal numeric fields do not lose precision on round-trip."""
        db, Property, _, _ = sqlite_session

        p = Property()
        p.address_line1 = "Precision Ave"
        p.city = "Precise"
        p.state = "PR"
        p.baths = Decimal("2.5")
        p.lot_size = Decimal("1234.56")
        p.hoa_fee = Decimal("350.00")
        db.add(p)
        db.commit()

        fetched = db.query(Property).filter(
            Property.address_line1 == "Precision Ave"
        ).first()
        # Stored as Numeric — compare float representation
        assert float(fetched.baths) == pytest.approx(2.5)
        assert float(fetched.lot_size) == pytest.approx(1234.56, abs=0.01)
        assert float(fetched.hoa_fee) == pytest.approx(350.00)

    def test_underwriting_decision_fields_round_trip(self, sqlite_session):
        """UnderwritingDecision fields written to DB are retrieved correctly."""
        db, _, UnderwritingDecision, _ = sqlite_session

        loan_app_id = uuid.uuid4()
        d = UnderwritingDecision()
        d.loan_application_id = loan_app_id
        d.decision = "APPROVED"
        d.dti_ratio = Decimal("0.35")
        d.ltv_ratio = Decimal("0.80")
        d.risk_score = Decimal("75.00")
        d.loan_amount = Decimal("300000.00")
        d.loan_type = "CONVENTIONAL"
        d.borrower_name = "Test Borrower"
        d.notes = "Approved with conditions"
        db.add(d)
        db.commit()

        fetched = db.query(UnderwritingDecision).filter(
            UnderwritingDecision.loan_application_id == loan_app_id
        ).first()
        assert fetched is not None
        assert fetched.decision == "APPROVED"
        assert float(fetched.dti_ratio) == pytest.approx(0.35, abs=0.001)
        assert float(fetched.loan_amount) == pytest.approx(300000.00)
        assert fetched.borrower_name == "Test Borrower"
        assert fetched.notes == "Approved with conditions"

    def test_underwriting_decision_to_dict_contains_all_fields(self, sqlite_session):
        """UnderwritingDecision.to_dict() includes all required fields."""
        db, _, UnderwritingDecision, _ = sqlite_session

        d = UnderwritingDecision()
        d.loan_application_id = uuid.uuid4()
        d.decision = "DENIED"
        db.add(d)
        db.commit()

        result = d.to_dict()
        for key in ("id", "loan_application_id", "decision", "created_at", "updated_at"):
            assert key in result, f"Missing key '{key}' in to_dict()"

    def test_valid_decisions_set_contains_all_four_values(self):
        """UnderwritingDecision.VALID_DECISIONS has all four expected values."""
        from services.data_service.models import UnderwritingDecision

        expected = {"APPROVED", "DENIED", "CONDITIONAL", "SUSPENDED"}
        assert expected.issubset(UnderwritingDecision.VALID_DECISIONS)

    def test_no_sql_errors_on_repeated_schema_creation(self):
        """Base.metadata.create_all() is idempotent for data service models."""
        from services.data_service.models import Base

        engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
        )
        Base.metadata.create_all(engine)
        Base.metadata.create_all(engine)  # second call must not raise


# ============================================================================
# AC 3 — Migration validation scripts run without exceptions
# ============================================================================


class TestMigrationValidationScriptRunsClean:
    """
    Verifies scripts/validate_migration.py runs without exceptions
    and produces zero critical issues.
    """

    def test_run_validation_does_not_raise(self, tmp_path):
        """run_validation() completes without raising any exception."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports" / "migration_validation_report.json")
        try:
            report = run_validation(output_path=output_path)
        except Exception as exc:
            pytest.fail(f"run_validation() raised an unexpected exception: {exc}")

    def test_run_validation_returns_dict(self, tmp_path):
        """run_validation() returns a dict."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_dict" / "report.json")
        report = run_validation(output_path=output_path)
        assert isinstance(report, dict)

    def test_run_validation_has_required_keys(self, tmp_path):
        """The validation report dict contains all required top-level keys."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_keys" / "report.json")
        report = run_validation(output_path=output_path)
        for key in ("report_type", "generated_at", "overall_status", "exit_code",
                    "summary", "findings", "warnings"):
            assert key in report, f"Missing key '{key}' in validation report"

    def test_run_validation_report_type_is_migration_validation(self, tmp_path):
        """Report type is correctly set to 'migration_validation'."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_type" / "report.json")
        report = run_validation(output_path=output_path)
        assert report["report_type"] == "migration_validation"

    def test_run_validation_generated_at_is_iso_timestamp(self, tmp_path):
        """generated_at is a valid ISO datetime string."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_ts" / "report.json")
        report = run_validation(output_path=output_path)
        # Should parse without error
        datetime.fromisoformat(report["generated_at"].replace("Z", "+00:00"))

    def test_run_validation_findings_is_list(self, tmp_path):
        """findings is a list."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_fl" / "report.json")
        report = run_validation(output_path=output_path)
        assert isinstance(report["findings"], list)

    def test_run_validation_summary_has_required_fields(self, tmp_path):
        """Summary block contains all required fields."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_sum" / "report.json")
        report = run_validation(output_path=output_path)
        summary = report["summary"]
        for field in ("tables_validated", "columns_checked", "critical_issues",
                      "warnings", "passed_checks", "total_checks"):
            assert field in summary, f"Missing summary field: {field}"

    def test_run_validation_writes_json_file(self, tmp_path):
        """The report file written to disk is valid JSON."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "reports_file" / "report.json")
        run_validation(output_path=output_path)

        assert os.path.isfile(output_path)
        with open(output_path) as f:
            loaded = json.load(f)
        assert isinstance(loaded, dict)
        assert "overall_status" in loaded


# ============================================================================
# AC 4 — Zero critical issues in final integration test results
# ============================================================================


class TestZeroCriticalIssuesInMigration:
    """
    Runs the migration validation and asserts zero critical issues found.
    This is the definitive AC 4 check.
    """

    def test_migration_validation_zero_critical_issues(self, tmp_path):
        """
        The migration validation report must contain zero critical issues.
        This verifies both auth_service and data_service ORM schemas are
        correct and complete as per the migration spec.
        """
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "final_report" / "migration_report.json")
        report = run_validation(output_path=output_path)

        critical_count = report["summary"]["critical_issues"]
        assert critical_count == 0, (
            f"Expected 0 critical issues, found {critical_count}. "
            f"Failing findings: {[f for f in report['findings'] if f.get('severity') == 'CRITICAL']}"
        )

    def test_migration_validation_overall_status_is_pass(self, tmp_path):
        """Overall migration validation status must be PASS."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "final_report_status" / "report.json")
        report = run_validation(output_path=output_path)
        assert report["overall_status"] == "PASS", (
            f"Migration validation FAILED. "
            f"Critical findings: {[f for f in report['findings'] if f.get('severity') == 'CRITICAL']}"
        )

    def test_migration_validation_exit_code_is_zero(self, tmp_path):
        """Exit code from migration validation must be 0 (no critical issues)."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "exit_code_report" / "report.json")
        report = run_validation(output_path=output_path)
        assert report["exit_code"] == 0

    def test_all_expected_tables_validated(self, tmp_path):
        """All four expected tables are present in the tables_validated list."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "tables_report" / "report.json")
        report = run_validation(output_path=output_path)

        tables = set(report["summary"]["tables_validated"])
        expected_tables = {"users", "auth_sessions", "properties", "underwriting_decisions"}
        missing = expected_tables - tables
        assert not missing, (
            f"Missing tables from validation: {missing}. "
            f"Validated tables: {tables}"
        )

    def test_auth_user_table_columns_pass(self, tmp_path):
        """Auth 'users' table column check must pass."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "user_col_report" / "report.json")
        report = run_validation(output_path=output_path)

        user_col_findings = [
            f for f in report["findings"]
            if "users" in f.get("check", "") and f.get("severity") == "CRITICAL"
        ]
        assert not user_col_findings, (
            f"Critical issues found for 'users' table columns: {user_col_findings}"
        )

    def test_auth_session_table_columns_pass(self, tmp_path):
        """Auth 'auth_sessions' table column check must pass."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "sess_col_report" / "report.json")
        report = run_validation(output_path=output_path)

        sess_col_findings = [
            f for f in report["findings"]
            if "auth_sessions" in f.get("check", "") and f.get("severity") == "CRITICAL"
        ]
        assert not sess_col_findings, (
            f"Critical issues for 'auth_sessions' columns: {sess_col_findings}"
        )

    def test_property_table_columns_pass(self, tmp_path):
        """Data 'properties' table column check must pass."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "prop_col_report" / "report.json")
        report = run_validation(output_path=output_path)

        prop_col_findings = [
            f for f in report["findings"]
            if "properties" in f.get("check", "") and f.get("severity") == "CRITICAL"
        ]
        assert not prop_col_findings, (
            f"Critical issues for 'properties' columns: {prop_col_findings}"
        )

    def test_underwriting_table_columns_pass(self, tmp_path):
        """Data 'underwriting_decisions' table column check must pass."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "uw_col_report" / "report.json")
        report = run_validation(output_path=output_path)

        uw_col_findings = [
            f for f in report["findings"]
            if "underwriting_decisions" in f.get("check", "") and f.get("severity") == "CRITICAL"
        ]
        assert not uw_col_findings, (
            f"Critical issues for 'underwriting_decisions' columns: {uw_col_findings}"
        )

    def test_business_logic_checks_pass(self, tmp_path):
        """Business logic checks (VALID_DECISIONS, get_roles) pass without critical issues."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "biz_report" / "report.json")
        report = run_validation(output_path=output_path)

        biz_findings = [
            f for f in report["findings"]
            if f.get("check", "") in ("valid_decisions_attribute",
                                      "valid_decisions_values",
                                      "user_get_roles_method")
            and f.get("severity") == "CRITICAL"
        ]
        assert not biz_findings, (
            f"Critical business logic issues found: {biz_findings}"
        )


# ============================================================================
# AC 5 — Comprehensive migration validation report for stakeholders
# ============================================================================


class TestMigrationValidationReport:
    """
    AC 5: Verify the report is comprehensive, human-readable, and stakeholder-ready.
    Tests cover report structure, content quality, and completeness.
    """

    @pytest.fixture(scope="class")
    def migration_report(self, tmp_path_factory):
        """Run the migration validation once and reuse the report across tests."""
        from scripts.validate_migration import run_validation

        output_path = str(
            tmp_path_factory.mktemp("migration") / "migration_validation_report.json"
        )
        report = run_validation(output_path=output_path)
        return report, output_path

    def test_report_is_written_to_disk(self, migration_report):
        """The report file must exist on disk after run_validation()."""
        _, output_path = migration_report
        assert os.path.isfile(output_path), f"Report file not found: {output_path}"

    def test_report_has_findings_with_status_field(self, migration_report):
        """Every finding in the report has a 'status' field."""
        report, _ = migration_report
        for finding in report["findings"]:
            assert "status" in finding, (
                f"Finding missing 'status' field: {finding}"
            )

    def test_report_has_findings_with_check_field(self, migration_report):
        """Every finding has a 'check' field identifying what was validated."""
        report, _ = migration_report
        for finding in report["findings"]:
            assert "check" in finding, (
                f"Finding missing 'check' field: {finding}"
            )

    def test_report_has_findings_with_message_field(self, migration_report):
        """Every finding has a 'message' field explaining the result."""
        report, _ = migration_report
        for finding in report["findings"]:
            assert "message" in finding, (
                f"Finding missing 'message' field: {finding}"
            )

    def test_report_has_findings_with_severity_field(self, migration_report):
        """Every finding has a 'severity' field (INFO, WARNING, or CRITICAL)."""
        report, _ = migration_report
        valid_severities = {"INFO", "WARNING", "CRITICAL"}
        for finding in report["findings"]:
            assert "severity" in finding
            assert finding["severity"] in valid_severities, (
                f"Invalid severity '{finding['severity']}' in finding: {finding}"
            )

    def test_report_has_at_least_ten_checks(self, migration_report):
        """A comprehensive report should have at least 10 individual checks."""
        report, _ = migration_report
        total_checks = report["summary"]["total_checks"]
        assert total_checks >= 10, (
            f"Expected at least 10 checks, got {total_checks}"
        )

    def test_report_passes_checks_count_greater_than_zero(self, migration_report):
        """There should be at least some passing checks."""
        report, _ = migration_report
        passed = report["summary"]["passed_checks"]
        assert passed > 0, "No passing checks found in the migration report"

    def test_report_columns_checked_covers_all_entities(self, migration_report):
        """The total columns checked covers all four entity schemas."""
        report, _ = migration_report
        # We have at minimum 8+6+12+9 = 35 columns across the four tables
        columns_checked = report["summary"]["columns_checked"]
        assert columns_checked >= 20, (
            f"Expected at least 20 columns checked, got {columns_checked}"
        )

    def test_report_import_checks_passed(self, migration_report):
        """Both auth_service.models and data_service.models imported successfully."""
        report, _ = migration_report
        import_findings = [
            f for f in report["findings"]
            if f.get("check", "").startswith("import_") and f.get("status") == "PASS"
        ]
        assert len(import_findings) >= 2, (
            f"Expected at least 2 import checks to pass, got: {import_findings}"
        )

    def test_report_table_creation_checks_passed(self, migration_report):
        """All four table creation checks passed."""
        report, _ = migration_report
        table_creation_pass = [
            f for f in report["findings"]
            if f.get("check", "").startswith("table_creation_")
            and f.get("status") == "PASS"
        ]
        assert len(table_creation_pass) == 4, (
            f"Expected 4 table creation checks to pass, got {len(table_creation_pass)}: "
            f"{table_creation_pass}"
        )

    def test_report_overall_status_pass_means_no_critical(self, migration_report):
        """PASS overall_status is consistent with zero critical issues."""
        report, _ = migration_report
        if report["overall_status"] == "PASS":
            assert report["summary"]["critical_issues"] == 0
        elif report["overall_status"] == "FAIL":
            assert report["summary"]["critical_issues"] > 0

    def test_report_file_is_valid_and_complete_json(self, migration_report):
        """The report file is valid JSON with all expected top-level keys."""
        _, output_path = migration_report
        with open(output_path) as f:
            loaded = json.load(f)

        for key in ("report_type", "generated_at", "overall_status", "exit_code",
                    "summary", "findings", "warnings"):
            assert key in loaded, f"Key '{key}' missing from serialized report"

    def test_report_summary_tables_validated_is_list(self, migration_report):
        """summary.tables_validated is a list, not a scalar."""
        report, _ = migration_report
        assert isinstance(report["summary"]["tables_validated"], list)

    def test_report_user_get_roles_check_present(self, migration_report):
        """The report contains the user_get_roles_method check."""
        report, _ = migration_report
        role_checks = [
            f for f in report["findings"]
            if "user_get_roles" in f.get("check", "")
        ]
        assert role_checks, "user_get_roles_method check not found in report"

    def test_report_valid_decisions_check_present(self, migration_report):
        """The report contains the valid_decisions check."""
        report, _ = migration_report
        decision_checks = [
            f for f in report["findings"]
            if "valid_decisions" in f.get("check", "")
        ]
        assert decision_checks, "valid_decisions check not found in report"

    def test_report_exit_code_consistent_with_critical_count(self, migration_report):
        """exit_code 0 means no critical issues; 1 means one or more critical issues."""
        report, _ = migration_report
        if report["summary"]["critical_issues"] == 0:
            assert report["exit_code"] == 0
        else:
            assert report["exit_code"] == 1


# ============================================================================
# AC 2 — No error logs related to integration issues across services
# ============================================================================


class TestNoIntegrationErrorLogs:
    """
    Verify that normal service operations do not produce ERROR-level log entries.
    We capture log records during successful operations and assert no ERROR messages
    are emitted.
    """

    def test_auth_service_login_success_no_error_logs(self, caplog, auth_engine,
                                                       monkeypatch):
        """Successful login must not produce any ERROR log entries."""
        from sqlalchemy.orm import sessionmaker as sm
        import services.auth_service.db as db_mod
        import services.auth_service.config as cfg_mod
        import services.auth_service.jwt_filter as jf_mod
        from tests.conftest import make_user, TEST_JWT_SECRET

        TestSF = sm(bind=auth_engine, expire_on_commit=False)
        monkeypatch.setattr(db_mod, "engine", auth_engine)
        monkeypatch.setattr(db_mod, "SessionFactory", TestSF)
        monkeypatch.setattr(db_mod, "init_db", lambda: None)
        monkeypatch.setattr(cfg_mod, "JWT_SECRET", TEST_JWT_SECRET)
        monkeypatch.setattr(jf_mod, "JWT_SECRET", TEST_JWT_SECRET)
        monkeypatch.setattr(jf_mod, "JWT_AUDIENCE", "zcloud-platform")
        monkeypatch.setenv("JWT_SECRET", TEST_JWT_SECRET)
        monkeypatch.setenv("JWT_EXPIRATION_SECONDS", "3600")
        monkeypatch.setenv("FERNET_KEY", "")

        from services.auth_service.app import create_app
        app = create_app()
        app.config["TESTING"] = True

        import bcrypt as _bcrypt
        user = make_user(username="log_test_user", password="LogTest1!")
        db = TestSF()
        db.add(user)
        db.commit()
        db.close()

        with caplog.at_level(logging.ERROR):
            with app.test_client() as client:
                resp = client.post(
                    "/api/auth/login",
                    json={"username": "log_test_user", "password": "LogTest1!"},
                    content_type="application/json",
                )
        assert resp.status_code == 200

        error_records = [r for r in caplog.records if r.levelno >= logging.ERROR]
        assert not error_records, (
            f"Unexpected ERROR logs during successful login: "
            f"{[r.getMessage() for r in error_records]}"
        )

    def test_data_service_create_property_no_error_logs(self, caplog, data_engine,
                                                         monkeypatch):
        """Successful property creation must not produce any ERROR log entries."""
        from sqlalchemy.orm import sessionmaker as sm
        import services.data_service.db as db_mod

        TestSF = sm(bind=data_engine, expire_on_commit=False)
        monkeypatch.setattr(db_mod, "engine", data_engine)
        monkeypatch.setattr(db_mod, "SessionFactory", TestSF)
        monkeypatch.setattr(db_mod, "init_db", lambda: None)

        from services.data_service.app import create_app
        app = create_app()
        app.config["TESTING"] = True

        with caplog.at_level(logging.ERROR):
            with app.test_client() as client:
                resp = client.post(
                    "/api/properties",
                    json={"address_line1": "1 Log Ave", "city": "LogCity", "state": "LC"},
                    content_type="application/json",
                )
        assert resp.status_code == 201

        error_records = [r for r in caplog.records if r.levelno >= logging.ERROR]
        assert not error_records, (
            f"Unexpected ERROR logs during property creation: "
            f"{[r.getMessage() for r in error_records]}"
        )

    def test_migration_validation_no_error_logs(self, caplog, tmp_path):
        """Migration validation script must not emit ERROR logs for a clean schema."""
        from scripts.validate_migration import run_validation

        output_path = str(tmp_path / "nolog_report" / "report.json")

        with caplog.at_level(logging.ERROR):
            report = run_validation(output_path=output_path)

        # Only check for unexpected errors (not expected CRITICAL in findings)
        error_records = [r for r in caplog.records if r.levelno >= logging.ERROR]
        assert not error_records, (
            f"Unexpected ERROR logs during migration validation: "
            f"{[r.getMessage() for r in error_records]}"
        )


# ============================================================================
# Fixtures used across this module
# ============================================================================


@pytest.fixture(scope="module")
def auth_engine():
    """Shared SQLite engine for auth service tests in this module."""
    from services.auth_service.models import Base as AuthBase

    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
    )
    AuthBase.metadata.create_all(engine)
    return engine


@pytest.fixture(scope="module")
def data_engine():
    """Shared SQLite engine for data service tests in this module."""
    from services.data_service.models import Base as DataBase

    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
    )
    DataBase.metadata.create_all(engine)
    return engine
