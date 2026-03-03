package com.zcloud.platform.repository;

import com.zcloud.platform.model.ClientDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for ClientDocument entity.
 * Anti-pattern: zero custom queries, extremely sparse compared to other repos,
 * no verification workflow queries despite entity having isVerified field.
 */
@Repository
public interface ClientDocumentRepository extends JpaRepository<ClientDocument, UUID> {

    List<ClientDocument> findByClientId(UUID clientId);

    List<ClientDocument> findByDocumentType(String documentType);

    List<ClientDocument> findByVerifiedFalse();
}
