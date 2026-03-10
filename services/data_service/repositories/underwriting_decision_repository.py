"""
UnderwritingDecisionRepository — Python/SQLAlchemy migration.

Implements all CRUD operations and custom queries from the original Java
Spring Data JPA repository for the UnderwritingDecision entity.

Migrated from: com.zcloud.platform.repository.UnderwritingDecisionRepository
"""

import logging
from typing import List, Optional
from uuid import UUID

from sqlalchemy import func, text
from sqlalchemy.orm import Session

from services.data_service.models import UnderwritingDecision

logger = logging.getLogger(__name__)


class UnderwritingDecisionRepository:
    """Data access layer for UnderwritingDecision entities.

    All mutations are wrapped in transactions — the caller provides the session
    so that transaction boundaries can be controlled at the service / route layer.
    """

    def __init__(self, session: Session) -> None:
        self._session = session

    # ------------------------------------------------------------------
    # CRUD — Create / Update
    # ------------------------------------------------------------------

    def save(self, decision: UnderwritingDecision) -> UnderwritingDecision:
        """Persist a new or updated UnderwritingDecision."""
        try:
            self._session.add(decision)
            self._session.flush()
            self._session.refresh(decision)
            return decision
        except Exception as exc:
            logger.error(
                "UnderwritingDecisionRepository.save failed: %s", exc, exc_info=True
            )
            self._session.rollback()
            raise

    # ------------------------------------------------------------------
    # CRUD — Read
    # ------------------------------------------------------------------

    def find_by_id(self, decision_id: UUID) -> Optional[UnderwritingDecision]:
        """Return the UnderwritingDecision with the given UUID, or None."""
        return self._session.get(UnderwritingDecision, decision_id)

    def find_all(self) -> List[UnderwritingDecision]:
        """Return all underwriting decisions."""
        return self._session.query(UnderwritingDecision).all()

    # ------------------------------------------------------------------
    # Custom queries — migrated from UnderwritingDecisionRepository.java
    # ------------------------------------------------------------------

    def find_by_loan_application_id(
        self, loan_application_id: UUID
    ) -> List[UnderwritingDecision]:
        """Return all decisions for a given loan application.

        Replicates: findByLoanApplicationId(UUID loanApplicationId)
        """
        return (
            self._session.query(UnderwritingDecision)
            .filter(
                UnderwritingDecision.loan_application_id == loan_application_id
            )
            .all()
        )

    def find_by_underwriter_id(
        self, underwriter_id: UUID
    ) -> List[UnderwritingDecision]:
        """Return all decisions made by a given underwriter (agent).

        Replicates: findByUnderwriterId(UUID underwriterId)
        Note: original Java used "underwriterId" which references the Agent table —
        naming is preserved as-is per the original schema.
        """
        return (
            self._session.query(UnderwritingDecision)
            .filter(UnderwritingDecision.underwriter_id == underwriter_id)
            .all()
        )

    def find_by_decision(self, decision: str) -> List[UnderwritingDecision]:
        """Return all decisions with the given decision value (e.g. 'APPROVED').

        Replicates: findByDecision(String decision)
        """
        return (
            self._session.query(UnderwritingDecision)
            .filter(UnderwritingDecision.decision == decision)
            .all()
        )

    def get_approval_rate_by_underwriter(self, underwriter_id: UUID) -> Optional[float]:
        """Calculate the approval rate (%) for a given underwriter.

        Replicates the native SQL business logic query from
        UnderwritingDecisionRepository.java:

            SELECT COUNT(CASE WHEN decision = 'APPROVED' THEN 1 END) * 100.0 / COUNT(*)
            FROM underwriting_decisions
            WHERE underwriter_id = :underwriterId
        """
        sql = text(
            "SELECT "
            "COUNT(CASE WHEN decision = 'APPROVED' THEN 1 END) * 100.0 / COUNT(*) "
            "AS approval_rate "
            "FROM underwriting_decisions "
            "WHERE underwriter_id = :underwriter_id"
        )
        result = self._session.execute(
            sql, {"underwriter_id": str(underwriter_id)}
        ).scalar()
        return float(result) if result is not None else None

    # ------------------------------------------------------------------
    # CRUD — Delete
    # ------------------------------------------------------------------

    def delete(self, decision: UnderwritingDecision) -> None:
        """Delete the given UnderwritingDecision entity."""
        try:
            self._session.delete(decision)
            self._session.flush()
        except Exception as exc:
            logger.error(
                "UnderwritingDecisionRepository.delete failed: %s",
                exc,
                exc_info=True,
            )
            self._session.rollback()
            raise

    def delete_by_id(self, decision_id: UUID) -> bool:
        """Delete an UnderwritingDecision by its UUID.

        Returns True if deleted, False if not found.
        """
        decision = self.find_by_id(decision_id)
        if decision is None:
            return False
        self.delete(decision)
        return True
