package com.harupaper.server.device;

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

/**
 * 앱이 발급하고 Pi의 {@code haru-agent pair <코드>}가 소비하는 1회용 코드
 * (.temp/03-플랫폼-작업지시서-v1.0.md Q14 경로 ②). Pi 쪽 구현은 노트북 세션 몫이며,
 * 이 서버는 발급(POST /api/devices/pairing-codes)과 소비(POST /api/device/pair)만 제공한다.
 */
@Entity
@Table(name = "pairing_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PairingCode {

    @Id
    @Column(length = 8)
    private String code;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
