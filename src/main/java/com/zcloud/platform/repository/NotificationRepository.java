package com.zcloud.platform.repository;

import com.zcloud.platform.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Notification entity.
 * Anti-patterns: bulk update in repo, native query for mark-all-read,
 * business logic for notification management in data layer.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserId(UUID userId);

    List<Notification> findByIsReadFalse();

    List<Notification> findByType(String type);

    // Anti-pattern: business logic - unread count per user belongs in service
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.userId = :userId AND n.isRead = false")
    long countUnreadByUser(@Param("userId") UUID userId);

    // Anti-pattern: bulk update operation in repository - should be in service with transaction
    @Modifying
    @Query(value = "UPDATE notifications SET is_read = true WHERE user_id = :userId AND is_read = false",
            nativeQuery = true)
    int markAllAsReadForUser(@Param("userId") UUID userId);

    // Anti-pattern: JPQL mixed with native above, redundant with findByIsReadFalse + userId filter
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.isRead = false " +
            "ORDER BY n.createdAt DESC")
    List<Notification> getUnreadNotificationsForUser(@Param("userId") UUID userId);
}
