package com.zcloud.platform.repository;

import com.zcloud.platform.model.PropertyTaxRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for PropertyTaxRecord entity.
 * Anti-pattern: inconsistent - no custom queries while sibling PropertyRepository has many,
 * returns List for taxYear which should probably be a single record per property+year.
 */
@Repository
public interface PropertyTaxRecordRepository extends JpaRepository<PropertyTaxRecord, UUID> {

    List<PropertyTaxRecord> findByPropertyId(UUID propertyId);

    // Anti-pattern: returns List when it should logically be one record per year per property
    List<PropertyTaxRecord> findByTaxYear(Integer taxYear);
}
