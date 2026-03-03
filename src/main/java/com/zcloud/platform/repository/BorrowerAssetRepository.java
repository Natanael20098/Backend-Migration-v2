package com.zcloud.platform.repository;

import com.zcloud.platform.model.BorrowerAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for BorrowerAsset entity.
 * Anti-pattern: single method only - the sparsest repository in the entire project,
 * no aggregation queries for total assets despite being critical for underwriting.
 */
@Repository
public interface BorrowerAssetRepository extends JpaRepository<BorrowerAsset, UUID> {

    List<BorrowerAsset> findByLoanApplicationId(UUID loanApplicationId);
}
