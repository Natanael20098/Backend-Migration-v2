package com.zcloud.platform.repository;

import com.zcloud.platform.model.CreditReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for CreditReport entity.
 * Anti-pattern: no custom queries despite needing score-based lookups for underwriting,
 * returns List for bureau which could be Optional (one report per bureau per app).
 */
@Repository
public interface CreditReportRepository extends JpaRepository<CreditReport, UUID> {

    List<CreditReport> findByLoanApplicationId(UUID loanApplicationId);

    // Anti-pattern: returns List when it should logically be filtered per application
    List<CreditReport> findByBureau(String bureau);
}
