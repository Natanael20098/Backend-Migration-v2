package com.zcloud.platform.repository;

import com.zcloud.platform.model.AppraisalReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for AppraisalReport entity.
 * Anti-pattern: single derived query, extremely sparse for an entity
 * that should have valuation comparison logic.
 */
@Repository
public interface AppraisalReportRepository extends JpaRepository<AppraisalReport, UUID> {

    // Anti-pattern: returns List but there should be at most one report per order
    List<AppraisalReport> findByAppraisalOrderId(UUID appraisalOrderId);
}
