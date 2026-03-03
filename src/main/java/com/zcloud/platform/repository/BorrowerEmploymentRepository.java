package com.zcloud.platform.repository;

import com.zcloud.platform.model.BorrowerEmployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for BorrowerEmployment entity.
 * Anti-pattern: extremely sparse - no custom queries at all,
 * no income aggregation queries despite being key to loan qualification.
 */
@Repository
public interface BorrowerEmploymentRepository extends JpaRepository<BorrowerEmployment, UUID> {

    List<BorrowerEmployment> findByLoanApplicationId(UUID loanApplicationId);

    List<BorrowerEmployment> findByIsCurrentTrue();
}
