package com.zcloud.platform.repository;

import com.zcloud.platform.model.EscrowDisbursement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for EscrowDisbursement entity.
 * Anti-pattern: only derived queries, no date-range queries despite having paidDate,
 * no aggregation for total disbursements per account.
 */
@Repository
public interface EscrowDisbursementRepository extends JpaRepository<EscrowDisbursement, UUID> {

    List<EscrowDisbursement> findByEscrowAccountId(UUID escrowAccountId);

    List<EscrowDisbursement> findByDisbursementType(String disbursementType);
}
