package com.zcloud.platform.repository;

import com.zcloud.platform.model.PaymentSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for PaymentSchedule entity.
 * Anti-pattern: single method, no amortization-specific queries,
 * extremely sparse for an entity that represents an entire amortization schedule.
 */
@Repository
public interface PaymentScheduleRepository extends JpaRepository<PaymentSchedule, UUID> {

    List<PaymentSchedule> findByLoanApplicationId(UUID loanApplicationId);
}
