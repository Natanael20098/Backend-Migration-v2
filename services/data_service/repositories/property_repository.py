"""
PropertyRepository — Python/SQLAlchemy migration of PropertyRepository.java

Implements all CRUD operations and custom queries from the original Java
Spring Data JPA repository for the Property entity.

Migrated from: com.zcloud.platform.repository.PropertyRepository
"""

import logging
from decimal import Decimal
from typing import List, Optional
from uuid import UUID

from sqlalchemy import func, text
from sqlalchemy.orm import Session

from services.data_service.models import Property

logger = logging.getLogger(__name__)


class PropertyRepository:
    """Data access layer for Property entities.

    All mutations are wrapped in transactions — the caller provides the session
    so that transaction boundaries can be controlled at the service / route layer.
    """

    def __init__(self, session: Session) -> None:
        self._session = session

    # ------------------------------------------------------------------
    # CRUD — Create
    # ------------------------------------------------------------------

    def save(self, property_: Property) -> Property:
        """Persist a new or updated Property. Flushes and returns the entity."""
        try:
            self._session.add(property_)
            self._session.flush()
            self._session.refresh(property_)
            return property_
        except Exception as exc:
            logger.error("PropertyRepository.save failed: %s", exc, exc_info=True)
            self._session.rollback()
            raise

    # ------------------------------------------------------------------
    # CRUD — Read
    # ------------------------------------------------------------------

    def find_by_id(self, property_id: UUID) -> Optional[Property]:
        """Return the Property with the given UUID, or None."""
        return self._session.get(Property, property_id)

    def find_all(self) -> List[Property]:
        """Return all properties."""
        return self._session.query(Property).all()

    # ------------------------------------------------------------------
    # Custom queries — migrated from PropertyRepository.java
    # ------------------------------------------------------------------

    def find_by_city(self, city: str) -> List[Property]:
        """Return all properties in the given city."""
        return (
            self._session.query(Property)
            .filter(Property.city == city)
            .all()
        )

    def find_by_state(self, state: str) -> List[Property]:
        """Return all properties in the given state."""
        return (
            self._session.query(Property)
            .filter(Property.state == state)
            .all()
        )

    def find_by_property_type(self, property_type: str) -> Optional[Property]:
        """Return the first property matching the type, or None.

        Original Java returned a raw object (null if not found) — replicated here
        as Optional for safety.
        """
        return (
            self._session.query(Property)
            .filter(Property.property_type == property_type)
            .first()
        )

    def find_by_zip_code(self, zip_code: str) -> List[Property]:
        """Return all properties with the given zip code."""
        return (
            self._session.query(Property)
            .filter(Property.zip_code == zip_code)
            .all()
        )

    def find_by_beds_gte(self, beds: int) -> List[Property]:
        """Return all properties with at least the specified number of beds."""
        return (
            self._session.query(Property)
            .filter(Property.beds >= beds)
            .all()
        )

    def find_by_baths_gte(self, baths: Decimal) -> List[Property]:
        """Return all properties with at least the specified number of baths."""
        return (
            self._session.query(Property)
            .filter(Property.baths >= baths)
            .all()
        )

    def find_by_sqft_between(self, min_sqft: int, max_sqft: int) -> List[Property]:
        """Return all properties whose square footage is within [min_sqft, max_sqft]."""
        return (
            self._session.query(Property)
            .filter(Property.sqft >= min_sqft, Property.sqft <= max_sqft)
            .all()
        )

    def search_by_price_range(
        self, min_price: Decimal, max_price: Decimal
    ) -> List[Property]:
        """Return active-listed properties whose listing price is in [min_price, max_price].

        Replicates the native SQL JOIN query from PropertyRepository.java:
            SELECT p.* FROM properties p
            INNER JOIN listings l ON p.id = l.property_id
            WHERE l.list_price BETWEEN :minPrice AND :maxPrice
            AND l.status = 'ACTIVE'
            ORDER BY l.list_price ASC
        """
        sql = text(
            "SELECT p.* FROM properties p "
            "INNER JOIN listings l ON p.id = l.property_id "
            "WHERE l.list_price BETWEEN :min_price AND :max_price "
            "AND l.status = 'ACTIVE' "
            "ORDER BY l.list_price ASC"
        )
        rows = self._session.execute(
            sql, {"min_price": min_price, "max_price": max_price}
        ).mappings().all()
        return [Property(**dict(row)) for row in rows]

    def find_available_properties_by_criteria(
        self,
        city: str,
        min_beds: int,
        min_baths: float,
        max_price: Decimal,
    ) -> List[Property]:
        """Return active-listed properties matching city/beds/baths/price criteria.

        Replicates the native SQL query from PropertyRepository.java.
        """
        sql = text(
            "SELECT p.* FROM properties p "
            "INNER JOIN listings l ON p.id = l.property_id "
            "WHERE p.city = :city AND p.beds >= :min_beds "
            "AND p.baths >= :min_baths AND l.status = 'ACTIVE' "
            "AND l.list_price <= :max_price"
        )
        rows = self._session.execute(
            sql,
            {
                "city": city,
                "min_beds": min_beds,
                "min_baths": min_baths,
                "max_price": max_price,
            },
        ).mappings().all()
        return [Property(**dict(row)) for row in rows]

    def find_newer_large_properties(
        self, year: int, min_sqft: int
    ) -> List[Property]:
        """Return properties built in or after *year* with at least *min_sqft* sq ft.

        Replicates the JPQL query from PropertyRepository.java:
            SELECT p FROM Property p WHERE p.yearBuilt >= :year AND p.sqft >= :minSqft
        """
        return (
            self._session.query(Property)
            .filter(Property.year_built >= year, Property.sqft >= min_sqft)
            .all()
        )

    def count_properties_by_city(self, city: str) -> int:
        """Return the number of properties in the given city.

        Replicates:
            SELECT COUNT(p) FROM Property p WHERE p.city = :city
        """
        return (
            self._session.query(func.count(Property.id))
            .filter(Property.city == city)
            .scalar()
        )

    def find_distinct_cities_by_state(self, state: str) -> List[str]:
        """Return an alphabetically sorted list of distinct cities for a state.

        Replicates the native SQL query from PropertyRepository.java:
            SELECT DISTINCT p.city FROM properties p WHERE p.state = :state ORDER BY p.city
        """
        rows = (
            self._session.query(Property.city)
            .filter(Property.state == state)
            .distinct()
            .order_by(Property.city)
            .all()
        )
        return [row[0] for row in rows]

    # ------------------------------------------------------------------
    # CRUD — Delete
    # ------------------------------------------------------------------

    def delete(self, property_: Property) -> None:
        """Delete the given Property entity."""
        try:
            self._session.delete(property_)
            self._session.flush()
        except Exception as exc:
            logger.error("PropertyRepository.delete failed: %s", exc, exc_info=True)
            self._session.rollback()
            raise

    def delete_by_id(self, property_id: UUID) -> bool:
        """Delete a Property by its UUID. Returns True if deleted, False if not found."""
        property_ = self.find_by_id(property_id)
        if property_ is None:
            return False
        self.delete(property_)
        return True
