package com.zcloud.platform.repository;

import com.zcloud.platform.model.ClosingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ClosingDocument entity.
 * Anti-patterns: business logic query for unsigned document tracking,
 * native query counting mixed with derived methods.
 */
@Repository
public interface ClosingDocumentRepository extends JpaRepository<ClosingDocument, UUID> {

    List<ClosingDocument> findByClosingId(UUID closingId);

    List<ClosingDocument> findByDocumentType(String documentType);

    List<ClosingDocument> findBySignedFalse();

    // Anti-pattern: business logic - document completion tracking belongs in service
    @Query(value = "SELECT COUNT(*) FROM closing_documents " +
            "WHERE closing_id = :closingId AND is_signed = false",
            nativeQuery = true)
    int countUnsignedDocumentsByClosing(@Param("closingId") UUID closingId);

    // Anti-pattern: JPQL mixed with native above, redundant with findByIsSignedFalse + closingId filter
    @Query("SELECT cd FROM ClosingDocument cd WHERE cd.closing.id = :closingId " +
            "AND cd.signed = false ORDER BY cd.createdAt ASC")
    List<ClosingDocument> findPendingSignaturesByClosing(@Param("closingId") UUID closingId);
}
