package com.zcloud.platform.repository;

import com.zcloud.platform.model.LoanPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for LoanPayment entity.
 * Anti-patterns: native query for payment summary with business logic aggregation,
 * mixed JPQL and native, business logic for delinquency detection in repo.
 */
@Repository
public interface LoanPaymentRepository extends JpaRepository<LoanPayment, UUID> {

    List<LoanPayment> findByLoanApplicationId(UUID loanApplicationId);

    List<LoanPayment> findByStatus(String status);

    List<LoanPayment> findByDueDateBetween(LocalDate startDate, LocalDate endDate);

    // Anti-pattern: massive native query with business logic aggregation - payment analytics in repo
    @Query(value = "SELECT " +
            "COUNT(*) as total_payments, " +
            "COUNT(CASE WHEN status = 'PAID' THEN 1 END) as paid_count, " +
            "COUNT(CASE WHEN status = 'LATE' THEN 1 END) as late_count, " +
            "COUNT(CASE WHEN status = 'MISSED' THEN 1 END) as missed_count, " +
            "COALESCE(SUM(CASE WHEN status = 'PAID' THEN total_amount END), 0) as total_paid, " +
            "COALESCE(SUM(CASE WHEN status IN ('PENDING', 'LATE') THEN total_amount END), 0) as total_outstanding, " +
            "COALESCE(SUM(late_fee), 0) as total_late_fees " +
            "FROM loan_payments " +
            "WHERE loan_application_id = :loanApplicationId",
            nativeQuery = true)
    List<Object[]> getPaymentSummary(@Param("loanApplicationId") UUID loanApplicationId);

    // Anti-pattern: business logic - delinquency detection belongs in service
    @Query("SELECT lp FROM LoanPayment lp WHERE lp.status = 'PENDING' " +
            "AND lp.dueDate < :today")
    List<LoanPayment> findOverduePayments(@Param("today") LocalDate today);

    // Anti-pattern: native query for what could be a derived query, returns raw object
    @Query(value = "SELECT * FROM loan_payments " +
            "WHERE loan_application_id = :loanApplicationId " +
            "ORDER BY payment_number DESC LIMIT 1",
            nativeQuery = true)
    LoanPayment findLatestPayment(@Param("loanApplicationId") UUID loanApplicationId);
}
