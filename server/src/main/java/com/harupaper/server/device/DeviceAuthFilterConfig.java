package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeviceTokenAuthFilter를 등록한다. M6부터 토큰은 DeviceRepository에서 조회한다
 * (더 이상 .env 단일 토큰이 아니다).
 */
@Configuration
@RequiredArgsConstructor
public class DeviceAuthFilterConfig {

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    @Bean
    public FilterRegistrationBean<DeviceTokenAuthFilter> deviceTokenAuthFilterRegistration() {
        DeviceTokenAuthFilter filter = new DeviceTokenAuthFilter(deviceRepository, objectMapper);
        FilterRegistrationBean<DeviceTokenAuthFilter> registration = new FilterRegistrationBean<>(filter);

        // URL 패턴: /api/device/* 로 넓게 설정하되,
        // 필터 내부에서 정확히 4개 경로인지 확인한다
        registration.addUrlPatterns("/api/device/*", "/api/device/renders/*");
        registration.setOrder(1);

        return registration;
    }
}
