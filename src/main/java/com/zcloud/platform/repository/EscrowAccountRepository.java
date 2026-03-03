package com.zcloud.platform.repository;

import com.zcloud.platform.model.EscrowAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for EscrowAccount entity.
 * Anti-pattern: inconsistent return types - List for closingId (should be one-to-one),
 * Optional for accountNumber, no balance-related queries.
 */
@Repository
public interface EscrowAccountRepository extends JpaRepository<EscrowAccount, UUID> {

    // Anti-pattern: returns List but closing-to-escrow should be one-to-one
    List<EscrowAccount> findByClosingId(UUID closingId);

    Optional<EscrowAccount> findByAccountNumber(String accountNumber);
}
