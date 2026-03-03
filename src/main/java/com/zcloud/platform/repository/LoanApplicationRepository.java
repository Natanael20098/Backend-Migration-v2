package com.zcloud.platform.repository;

import com.zcloud.platform.model.LoanApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for LoanApplication entity.
 * Anti-patterns: massive native query for pipeline summary, business logic aggregation,
 * mixed return types (List<Object[]> for native projections), inconsistent naming.
 */
@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    List<LoanApplication> findByBorrowerId(UUID borrowerId);

    List<LoanApplication> findByPropertyId(UUID propertyId);

    List<LoanApplication> findByLoanOfficerId(UUID loanOfficerId);

    List<LoanApplication> findByStatus(String status);

    List<LoanApplication> findByApplicationDateBetween(LocalDate startDate, LocalDate endDate);

    // Anti-pattern: massive native query with business logic - pipeline analytics in repo layer,
    // returns raw Object[] which is fragile and not type-safe
    @Query(value = "SELECT la.status, COUNT(*) as count, " +
            "COALESCE(SUM(la.loan_amount), 0) as total_amount, " +
            "COALESCE(AVG(la.loan_amount), 0) as avg_amount " +
            "FROM loan_applications la " +
            "WHERE la.loan_officer_id = :loanOfficerId " +
            "AND la.application_date BETWEEN :startDate AND :endDate " +
            "GROUP BY la.status " +
            "ORDER BY count DESC",
            nativeQuery = true)
    List<Object[]> getPipelineSummary(@Param("loanOfficerId") UUID loanOfficerId,
                                      @Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    // Anti-pattern: JPQL mixed with native above, business logic for stale applications
    @Query("SELECT la FROM LoanApplication la WHERE la.status = 'PROCESSING' " +
            "AND la.updatedAt < :cutoffDate")
    List<LoanApplication> findStaleApplications(@Param("cutoffDate") java.sql.Timestamp cutoffDate);

    // Anti-pattern: inconsistent naming - "get" prefix instead of "find" used elsewhere
    @Query("SELECT la FROM LoanApplication la JOIN FETCH la.borrower b " +
            "WHERE la.loanOfficer.id = :officerId AND la.status IN ('SUBMITTED', 'PROCESSING', 'UNDERWRITING')")
    List<LoanApplication> getActivePipelineForOfficer(@Param("officerId") UUID officerId);
}
