package com.zcloud.platform.repository;

import com.zcloud.platform.model.AppraisalOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for AppraisalOrder entity.
 * Anti-patterns: native query for overdue check (business logic in repo),
 * mixing query styles.
 */
@Repository
public interface AppraisalOrderRepository extends JpaRepository<AppraisalOrder, UUID> {

    List<AppraisalOrder> findByLoanApplicationId(UUID loanApplicationId);

    List<AppraisalOrder> findByPropertyId(UUID propertyId);

    List<AppraisalOrder> findByStatus(String status);

    // Anti-pattern: business logic - overdue detection belongs in service layer
    @Query(value = "SELECT * FROM appraisal_orders " +
            "WHERE status NOT IN ('COMPLETED', 'CANCELLED') " +
            "AND due_date < CURRENT_DATE",
            nativeQuery = true)
    List<AppraisalOrder> findOverdueOrders();
}
