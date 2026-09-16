package com.harupaper.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;

/**
 * M6: 사용자별 설정 (user_settings 테이블).
 * 복합 PK: (user_id, setting_key)
 *
 * 현재 설정:
 * - weather.location: {lat, lon, label} JSON
 *
 * docs/server/data-model.md, docs/server/weather.md 참고.
 */
@Entity
@Table(name = "user_settings")
@IdClass(Settings.SettingId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settings {

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    @Id
    @Column(name = "setting_key", length = 50)
    private String settingKey;

    @Column(nullable = false, columnDefinition = "JSON")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 복합 PK를 위한 도우미 클래스
     * JPA @IdClass의 요구사항: 필드명과 타입이 엔티티와 정확히 일치해야 함
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SettingId implements Serializable {
        public String userId;
        public String settingKey;
    }
}
