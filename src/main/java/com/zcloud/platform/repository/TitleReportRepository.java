package com.zcloud.platform.repository;

import com.zcloud.platform.model.TitleReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for TitleReport entity.
 * Anti-pattern: sparse with no custom queries, returns List for closingId
 * when there should be at most one title report per closing.
 */
@Repository
public interface TitleReportRepository extends JpaRepository<TitleReport, UUID> {

    // Anti-pattern: returns List but should be Optional (one report per closing)
    List<TitleReport> findByClosingId(UUID closingId);

    List<TitleReport> findByStatus(String status);
}
