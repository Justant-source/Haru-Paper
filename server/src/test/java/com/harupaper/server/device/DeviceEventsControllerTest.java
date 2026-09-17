package com.harupaper.server.device;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeviceEventsController - GET /api/device/events
 * DeviceSyncControllerOwnershipTest.java와 같은 패턴: 컨트롤러를 직접 new로 생성하고
 * HttpServletRequest를 mock해 DeviceTokenAuthFilter.DEVICE_ATTRIBUTE를 채운다.
 */
@DisplayName("DeviceEventsController - SSE 이벤트 엔드포인트")
class DeviceEventsControllerTest {

    private DeviceEventsController controller;
    private DeviceEventRegistry deviceEventRegistry;

    @BeforeEach
    void setUp() {
        deviceEventRegistry = mock(DeviceEventRegistry.class);
        controller = new DeviceEventsController(deviceEventRegistry);
        ReflectionTestUtils.setField(controller, "sseTimeoutSec", 1800L);
    }

    @Test
    @DisplayName("요청한 기기의 소유자로 emitter를 등록한다")
    void events_registersEmitterForDeviceOwner() {
        Device device = deviceOwnedBy("device-A", "user-A");
        HttpServletRequest request = requestWithDevice(device);

        SseEmitter emitter = controller.events(request);

        assertNotNull(emitter);
        ArgumentCaptor<SseEmitter> captor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(deviceEventRegistry).register(eq("user-A"), captor.capture(), eq("device-A"));
        assertSame(emitter, captor.getValue());
    }

    @Test
    @DisplayName("예외 없이 SseEmitter를 반환한다")
    void events_returnsEmitterWithoutException() {
        Device device = deviceOwnedBy("device-B", "user-B");
        HttpServletRequest request = requestWithDevice(device);

        assertDoesNotThrow(() -> {
            SseEmitter emitter = controller.events(request);
            assertNotNull(emitter);
        });
    }

    private HttpServletRequest requestWithDevice(Device device) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE)).thenReturn(device);
        return request;
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
