package com.harupaper.server.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HARU_DEVICE_TOKEN이 비어 있으면 애플리케이션 시작을 실패시킨다.
 * 토큰 없이 Pi 인증 필터가 뜨는 사고를 막기 위해서다 (docs/server/api.md 3절).
 */
@Component
public class DeviceTokenStartupValidator {

    private final String deviceToken;

    public DeviceTokenStartupValidator(@Value("${haru.device-token:}") String deviceToken) {
        this.deviceToken = deviceToken;
    }

    @PostConstruct
    public void validate() {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new IllegalStateException(
                    "HARU_DEVICE_TOKEN이 비어 있다. server/.env에 값을 채워야 애플리케이션이 시작된다.");
        }
    }
}
