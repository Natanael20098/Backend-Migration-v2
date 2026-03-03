package com.zcloud.platform.repository;

import com.zcloud.platform.model.ClosingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repository for ClosingDetail entity.
 * Anti-patterns: business logic query for upcoming closings, native query mixed
 * with derived methods, inconsistent return types.
 */
@Repository
public interface ClosingDetailRepository extends JpaRepository<ClosingDetail, UUID> {

    // Anti-pattern: returns raw object - null if not found, should be Optional
    ClosingDetail findByLoanApplicationId(UUID loanApplicationId);

    // Anti-pattern: inconsistent - returns List here but raw object above
    List<ClosingDetail> findByListingId(UUID listingId);

    List<ClosingDetail> findByStatus(String status);

    List<ClosingDetail> findByClosingDateBetween(LocalDate startDate, LocalDate endDate);

    // Anti-pattern: business logic - upcoming closing alerts belong in service
    @Query("SELECT cd FROM ClosingDetail cd WHERE cd.status = 'SCHEDULED' " +
            "AND cd.closingDate BETWEEN :today AND :endDate " +
            "ORDER BY cd.closingDate ASC")
    List<ClosingDetail> findUpcomingClosings(@Param("today") LocalDate today,
                                              @Param("endDate") LocalDate endDate);

    // Anti-pattern: native query for total closing costs aggregation
    @Query(value = "SELECT COALESCE(SUM(total_closing_costs), 0) FROM closing_details " +
            "WHERE status = 'COMPLETED' " +
            "AND closing_date BETWEEN :startDate AND :endDate",
            nativeQuery = true)
    java.math.BigDecimal getTotalClosingCostsInPeriod(@Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);
}
