package com.harupaper.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 키-값 설정 저장소. 현재 키는 "weather.location" 하나뿐(docs/server/data-model.md). */
@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Settings {

    @Id
    @Column(name = "setting_key", length = 50)
    private String settingKey;

    @Column(nullable = false, columnDefinition = "JSON")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
