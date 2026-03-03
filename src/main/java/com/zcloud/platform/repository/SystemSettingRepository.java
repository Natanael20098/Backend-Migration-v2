package com.zcloud.platform.repository;

import com.zcloud.platform.model.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for SystemSetting entity.
 * Anti-pattern: returns raw object for findBySettingKey (null if not found),
 * no caching consideration for settings that are read frequently.
 */
@Repository
public interface SystemSettingRepository extends JpaRepository<SystemSetting, UUID> {

    // Anti-pattern: returns raw object instead of Optional - null if key doesn't exist
    SystemSetting findBySettingKey(String settingKey);

    List<SystemSetting> findByCategory(String category);
}
