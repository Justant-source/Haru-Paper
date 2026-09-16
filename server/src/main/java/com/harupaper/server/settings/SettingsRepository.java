package com.harupaper.server.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Settings 리포지토리
 * 복합 PK: (user_id, setting_key)
 */
public interface SettingsRepository extends JpaRepository<Settings, Settings.SettingId> {

    /**
     * 특정 사용자의 특정 설정 조회
     */
    Optional<Settings> findByUserIdAndSettingKey(String userId, String settingKey);

    /**
     * 특정 사용자의 모든 설정 조회
     */
    List<Settings> findByUserId(String userId);

    /**
     * 특정 사용자와 설정 키로 설정 조회 (findByUserIdAndSettingKey와 동일)
     */
    @Query("SELECT s FROM Settings s WHERE s.userId = :userId AND s.settingKey = :settingKey")
    Optional<Settings> findSetting(@Param("userId") String userId, @Param("settingKey") String settingKey);
}
