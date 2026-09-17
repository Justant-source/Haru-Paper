package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeviceTokenAuthFilter - 보호 경로·인증 검증.
 *
 * 지금까지 이 필터에는 테스트가 없었다. 가장 중요한 목적은 GET /api/device/events가
 * PROTECTED_PATHS에서 빠지는 회귀를 잡는 것 — SSE 깨우기 채널도 Pi용 Bearer 토큰 인증이
 * 필요한데, 구현 담당이 이 줄을 빠뜨리면 인증 없이 다른 사용자의 소유자 채널을 열 수 있게 된다.
 *
 * 변이 확인: PROTECTED_PATHS에서 "/api/device/events"를 일시적으로 지우고 아래 테스트
 * 1번·2번이 실패하는지(= 인증 없이 필터를 통과하는지) 확인한 뒤 원상복구했다.
 */
@DisplayName("DeviceTokenAuthFilter - 보호 경로·인증")
class DeviceTokenAuthFilterPathsTest {

    private DeviceRepository deviceRepository;
    private DeviceTokenAuthFilter filter;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        filter = new DeviceTokenAuthFilter(deviceRepository, objectMapper);
    }

    @Test
    @DisplayName("Authorization 헤더 없이 /api/device/events를 요청하면 401을 응답하고 체인을 타지 않는다")
    void events_withoutToken_returns401AndBlocksChain() throws Exception {
        HttpServletRequest request = requestFor("GET", "/api/device/events", null);
        HttpServletResponse response = mockResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(401);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("유효한 토큰으로 /api/device/events를 요청하면 Device를 attribute에 담고 체인을 태운다")
    void events_withValidToken_setsDeviceAttributeAndContinues() throws Exception {
        Device device = deviceOwnedBy("device-A", "user-A");
        when(deviceRepository.findByTokenHash(anyString())).thenReturn(Optional.of(device));

        HttpServletRequest request = requestFor("GET", "/api/device/events", "Bearer valid-token");
        HttpServletResponse response = mockResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        verify(request).setAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE, device);
        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    @DisplayName("보호 대상이 아닌 /api/device는 토큰 없이도 바로 체인을 태운다")
    void unprotectedPath_passesThroughWithoutToken() throws Exception {
        HttpServletRequest request = requestFor("GET", "/api/device", null);
        HttpServletResponse response = mockResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(deviceRepository);
    }

    private HttpServletRequest requestFor(String method, String uri, String authHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getHeader("Authorization")).thenReturn(authHeader);
        return request;
    }

    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    private Device deviceOwnedBy(String deviceId, String ownerUserId) {
        return Device.builder()
                .id(deviceId)
                .ownerUserId(ownerUserId)
                .name("test-device")
                .tokenHash("hash")
                .tokenIssuedAt(Instant.now())
                .paperStateManual(false)
                .createdAt(Instant.now())
                .build();
    }
}
