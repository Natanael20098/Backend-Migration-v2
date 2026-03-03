package com.zcloud.platform.repository;

import com.zcloud.platform.model.ComparableSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ComparableSale entity.
 * Anti-pattern: bare minimum, no spatial/distance queries despite entity
 * having distanceMiles field, no price comparison logic.
 */
@Repository
public interface ComparableSaleRepository extends JpaRepository<ComparableSale, UUID> {

    List<ComparableSale> findByAppraisalReportId(UUID appraisalReportId);
}
