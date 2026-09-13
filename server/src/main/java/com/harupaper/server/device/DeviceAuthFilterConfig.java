package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeviceTokenAuthFilter를 등록한다.
 */
@Configuration
public class DeviceAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<DeviceTokenAuthFilter> deviceTokenAuthFilterRegistration(
            @Value("${haru.device-token}") String deviceToken,
            ObjectMapper objectMapper) {

        DeviceTokenAuthFilter filter = new DeviceTokenAuthFilter(deviceToken, objectMapper);
        FilterRegistrationBean<DeviceTokenAuthFilter> registration = new FilterRegistrationBean<>(filter);

        // URL 패턴: /api/device/* 로 넓게 설정하되,
        // 필터 내부에서 정확히 4개 경로인지 확인한다
        registration.addUrlPatterns("/api/device/*", "/api/device/renders/*");
        registration.setOrder(1);

        return registration;
    }
}
