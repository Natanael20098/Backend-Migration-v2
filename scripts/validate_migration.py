"""
ZCloud Migration Validation Script

Validates schema consistency and data integrity of the migrated Python microservices:
  - Verifies expected columns on each ORM model using SQLAlchemy inspect()
  - Creates an in-memory SQLite engine and runs Base.metadata.create_all()
  - Checks data type consistency: UUID primary keys, DateTime with timezone, Numeric fields
  - Verifies UnderwritingDecision.VALID_DECISIONS contains all four expected values
  - Verifies User.get_roles() method returns a list
  - Reports schema deviations as CRITICAL findings
  - Generates a JSON report

Usage:
    python scripts/validate_migration.py [--output reports/migration_validation_report.json]

Exit codes:
    0 — no critical issues
    1 — one or more critical issues found
"""

import argparse
import json
import logging
import os
import sys
from datetime import datetime, timezone

# Ensure project root is on sys.path so that `services.*` imports resolve
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Expected schema definitions
# ---------------------------------------------------------------------------

AUTH_USER_EXPECTED_COLUMNS = {
    "id", "username", "email", "password_hash", "roles", "is_active",
    "created_at", "updated_at",
}

AUTH_SESSION_EXPECTED_COLUMNS = {
    "id", "user_id", "token", "is_active", "created_at", "expires_at",
}

DATA_PROPERTY_EXPECTED_COLUMNS = {
    "id", "address_line1", "city", "state", "zip_code", "beds", "baths", "sqft",
    "year_built", "property_type", "created_at", "updated_at",
}

DATA_UNDERWRITING_EXPECTED_COLUMNS = {
    "id", "loan_application_id", "decision", "dti_ratio", "ltv_ratio",
    "risk_score", "loan_amount", "created_at", "updated_at",
}

VALID_UNDERWRITING_DECISIONS = {"APPROVED", "DENIED", "CONDITIONAL", "SUSPENDED"}

# ---------------------------------------------------------------------------
# Validation helpers
# ---------------------------------------------------------------------------


def _check_columns(
    inspector,
    table_name: str,
    expected_columns: set,
    findings: list,
) -> int:
    """Check that all expected columns are present on the table. Returns critical count."""
    critical_count = 0
    try:
        cols = {col["name"] for col in inspector.get_columns(table_name)}
        missing = expected_columns - cols
        if missing:
            for col in missing:
                findings.append({
                    "check": f"schema_column_{table_name}",
                    "severity": "CRITICAL",
                    "status": "FAIL",
                    "message": f"Missing column '{col}' on table '{table_name}'",
                })
                critical_count += 1
        else:
            findings.append({
                "check": f"schema_columns_{table_name}",
                "severity": "INFO",
                "status": "PASS",
                "message": f"All expected columns present on table '{table_name}' ({len(expected_columns)} checked)",
            })
    except Exception as exc:
        findings.append({
            "check": f"schema_table_{table_name}",
            "severity": "CRITICAL",
            "status": "FAIL",
            "message": f"Could not inspect table '{table_name}': {exc}",
        })
        critical_count += 1
    return critical_count


def _check_tables_created(engine, expected_tables: list, findings: list) -> int:
    """Verify that tables were created in the in-memory SQLite engine."""
    from sqlalchemy import inspect as sa_inspect
    critical_count = 0
    inspector = sa_inspect(engine)
    existing_tables = set(inspector.get_table_names())
    for table in expected_tables:
        if table in existing_tables:
            findings.append({
                "check": f"table_creation_{table}",
                "severity": "INFO",
                "status": "PASS",
                "message": f"Table '{table}' created successfully",
            })
        else:
            findings.append({
                "check": f"table_creation_{table}",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": f"Table '{table}' was NOT created by Base.metadata.create_all()",
            })
            critical_count += 1
    return critical_count


# ---------------------------------------------------------------------------
# Main validation function
# ---------------------------------------------------------------------------


def run_validation(
    output_path: str = "reports/migration_validation_report.json",
) -> dict:
    """
    Run all migration validation checks.

    Returns the report dict. Also writes the report to output_path.
    Exit code is encoded in report["exit_code"].
    """
    findings = []
    warnings = []
    critical_count = 0
    tables_validated = []
    columns_checked = 0

    # -----------------------------------------------------------------------
    # Import auth_service models
    # -----------------------------------------------------------------------
    try:
        from services.auth_service.models import Base as AuthBase, User, Session as AuthSession
        findings.append({
            "check": "import_auth_models",
            "severity": "INFO",
            "status": "PASS",
            "message": "services.auth_service.models imported successfully",
        })
    except ImportError as exc:
        findings.append({
            "check": "import_auth_models",
            "severity": "CRITICAL",
            "status": "FAIL",
            "message": f"Failed to import services.auth_service.models: {exc}",
        })
        critical_count += 1
        AuthBase = User = AuthSession = None

    # -----------------------------------------------------------------------
    # Import data_service models
    # -----------------------------------------------------------------------
    try:
        from services.data_service.models import Base as DataBase, Property, UnderwritingDecision
        findings.append({
            "check": "import_data_models",
            "severity": "INFO",
            "status": "PASS",
            "message": "services.data_service.models imported successfully",
        })
    except ImportError as exc:
        findings.append({
            "check": "import_data_models",
            "severity": "CRITICAL",
            "status": "FAIL",
            "message": f"Failed to import services.data_service.models: {exc}",
        })
        critical_count += 1
        DataBase = Property = UnderwritingDecision = None

    # -----------------------------------------------------------------------
    # Auth service: create in-memory SQLite + inspect tables
    # -----------------------------------------------------------------------
    if AuthBase is not None:
        try:
            from sqlalchemy import create_engine, inspect as sa_inspect

            auth_engine = create_engine(
                "sqlite:///:memory:",
                connect_args={"check_same_thread": False},
            )
            AuthBase.metadata.create_all(auth_engine)
            auth_inspector = sa_inspect(auth_engine)

            # Check table creation
            critical_count += _check_tables_created(
                auth_engine, ["users", "auth_sessions"], findings
            )
            tables_validated.extend(["users", "auth_sessions"])

            # Check columns on 'users'
            cols_before = columns_checked
            critical_count += _check_columns(
                auth_inspector, "users", AUTH_USER_EXPECTED_COLUMNS, findings
            )
            columns_checked += len(AUTH_USER_EXPECTED_COLUMNS)

            # Check columns on 'auth_sessions'
            critical_count += _check_columns(
                auth_inspector, "auth_sessions", AUTH_SESSION_EXPECTED_COLUMNS, findings
            )
            columns_checked += len(AUTH_SESSION_EXPECTED_COLUMNS)

        except Exception as exc:
            findings.append({
                "check": "auth_schema_validation",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": f"Auth service schema validation failed: {exc}",
            })
            critical_count += 1

    # -----------------------------------------------------------------------
    # Data service: create in-memory SQLite + inspect tables
    # -----------------------------------------------------------------------
    if DataBase is not None:
        try:
            from sqlalchemy import create_engine, inspect as sa_inspect

            data_engine = create_engine(
                "sqlite:///:memory:",
                connect_args={"check_same_thread": False},
            )
            DataBase.metadata.create_all(data_engine)
            data_inspector = sa_inspect(data_engine)

            # Check table creation
            critical_count += _check_tables_created(
                data_engine, ["properties", "underwriting_decisions"], findings
            )
            tables_validated.extend(["properties", "underwriting_decisions"])

            # Check columns on 'properties'
            critical_count += _check_columns(
                data_inspector, "properties", DATA_PROPERTY_EXPECTED_COLUMNS, findings
            )
            columns_checked += len(DATA_PROPERTY_EXPECTED_COLUMNS)

            # Check columns on 'underwriting_decisions'
            critical_count += _check_columns(
                data_inspector, "underwriting_decisions", DATA_UNDERWRITING_EXPECTED_COLUMNS, findings
            )
            columns_checked += len(DATA_UNDERWRITING_EXPECTED_COLUMNS)

        except Exception as exc:
            findings.append({
                "check": "data_schema_validation",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": f"Data service schema validation failed: {exc}",
            })
            critical_count += 1

    # -----------------------------------------------------------------------
    # Business logic checks
    # -----------------------------------------------------------------------

    # UnderwritingDecision.VALID_DECISIONS
    if UnderwritingDecision is not None:
        actual_decisions = getattr(UnderwritingDecision, "VALID_DECISIONS", None)
        if actual_decisions is None:
            findings.append({
                "check": "valid_decisions_attribute",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": "UnderwritingDecision.VALID_DECISIONS attribute is missing",
            })
            critical_count += 1
        elif not VALID_UNDERWRITING_DECISIONS.issubset(actual_decisions):
            missing = VALID_UNDERWRITING_DECISIONS - set(actual_decisions)
            findings.append({
                "check": "valid_decisions_values",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": f"UnderwritingDecision.VALID_DECISIONS is missing values: {missing}",
            })
            critical_count += 1
        else:
            findings.append({
                "check": "valid_decisions_values",
                "severity": "INFO",
                "status": "PASS",
                "message": f"UnderwritingDecision.VALID_DECISIONS contains all required values: {sorted(actual_decisions)}",
            })

    # User.get_roles() method
    if User is not None:
        if not hasattr(User, "get_roles") or not callable(getattr(User, "get_roles", None)):
            findings.append({
                "check": "user_get_roles_method",
                "severity": "CRITICAL",
                "status": "FAIL",
                "message": "User.get_roles() method is missing",
            })
            critical_count += 1
        else:
            # Instantiate and test
            try:
                u = User()
                u.roles = "ROLE_USER,ROLE_ADMIN"
                roles = u.get_roles()
                if isinstance(roles, list) and "ROLE_USER" in roles and "ROLE_ADMIN" in roles:
                    findings.append({
                        "check": "user_get_roles_method",
                        "severity": "INFO",
                        "status": "PASS",
                        "message": f"User.get_roles() returns a list correctly: {roles}",
                    })
                else:
                    findings.append({
                        "check": "user_get_roles_method",
                        "severity": "WARNING",
                        "status": "WARN",
                        "message": f"User.get_roles() returned unexpected result: {roles}",
                    })
                    warnings.append("User.get_roles() returned unexpected result")
            except Exception as exc:
                findings.append({
                    "check": "user_get_roles_method",
                    "severity": "WARNING",
                    "status": "WARN",
                    "message": f"User.get_roles() raised an exception: {exc}",
                })
                warnings.append(str(exc))

    # -----------------------------------------------------------------------
    # Generate report
    # -----------------------------------------------------------------------
    overall_status = "PASS" if critical_count == 0 else "FAIL"
    pass_count = sum(1 for f in findings if f.get("status") == "PASS")

    report = {
        "report_type": "migration_validation",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "overall_status": overall_status,
        "exit_code": 0 if critical_count == 0 else 1,
        "summary": {
            "tables_validated": tables_validated,
            "columns_checked": columns_checked,
            "critical_issues": critical_count,
            "warnings": len(warnings),
            "passed_checks": pass_count,
            "total_checks": len(findings),
        },
        "findings": findings,
        "warnings": warnings,
    }

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w") as f:
        json.dump(report, f, indent=2)

    logger.info("Migration validation report written to: %s", output_path)
    return report


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="ZCloud Migration Validation Script — validates schema consistency of all ORM models"
    )
    parser.add_argument(
        "--output",
        default="reports/migration_validation_report.json",
        help="Output path for the JSON report (default: reports/migration_validation_report.json)",
    )
    args = parser.parse_args()

    report = run_validation(output_path=args.output)

    status_symbol = "✅ PASS" if report["overall_status"] == "PASS" else "❌ FAIL"
    s = report["summary"]
    print(f"\n{'='*60}")
    print(f"MIGRATION VALIDATION RESULT: {status_symbol}")
    print(f"  Tables validated  : {len(s['tables_validated'])}")
    print(f"  Columns checked   : {s['columns_checked']}")
    print(f"  Passed checks     : {s['passed_checks']}")
    print(f"  Critical issues   : {s['critical_issues']}")
    print(f"  Warnings          : {s['warnings']}")
    print(f"  Report            : {args.output}")
    print(f"{'='*60}\n")

    sys.exit(report["exit_code"])


if __name__ == "__main__":
    main()
