package com.zcloud.platform.repository;

import com.zcloud.platform.model.PropertyImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for PropertyImage entity.
 * Anti-patterns: inconsistent return types, delete without transaction consideration.
 */
@Repository
public interface PropertyImageRepository extends JpaRepository<PropertyImage, UUID> {

    List<PropertyImage> findByPropertyId(UUID propertyId);

    // Anti-pattern: returns raw object instead of Optional - null if not found
    PropertyImage findByPropertyIdAndIsPrimaryTrue(UUID propertyId);

    @Modifying
    @Query("DELETE FROM PropertyImage pi WHERE pi.property.id = :propertyId")
    void deleteByPropertyId(@Param("propertyId") UUID propertyId);
}
