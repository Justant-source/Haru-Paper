package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DeviceController.putPaperState - 수동 용지 상태 갱신 후 기기를 깨우는지 검증
 * (docs/architecture.md 4.3절 — 앱에서 "용지 채움"을 누르면 Pi가 다음 poll을 30초 기다리지
 * 않고 바로 반영하도록 SSE로 깨운다).
 */
@DisplayName("DeviceController - 용지 상태 갱신 후 기기 깨우기")
class DeviceControllerPaperStateWakeTest {

    private DeviceRepository deviceRepository;
    private PairingCodeRepository pairingCodeRepository;
    private DeviceWakeNotifier deviceWakeNotifier;
    private DeviceController controller;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        pairingCodeRepository = mock(PairingCodeRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        deviceWakeNotifier = mock(DeviceWakeNotifier.class);

        controller = new DeviceController(deviceRepository, pairingCodeRepository, objectMapper, deviceWakeNotifier);

        User user = User.builder()
                .id("user-A")
                .email("a@example.com")
                .passwordHash("irrelevant")
                .handle("usera")
                .displayName("User A")
                .role("user")
                .status("active")
                .mustChangePassword(false)
                .build();
        principal = new UserPrincipal(user);

        Device device = Device.builder()
                .id("device-A")
                .ownerUserId("user-A")
                .name("내 프린터")
                .tokenHash("hash")
                .tokenIssuedAt(Instant.now())
                .paperStateManual(false)
                .createdAt(Instant.now())
                .build();
        when(deviceRepository.findByOwnerUserId("user-A")).thenReturn(Optional.of(device));
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("정상적으로 용지 상태를 갱신하면 deviceWakeNotifier.wake가 1회 호출된다")
    void putPaperState_wakesDeviceOwner() {
        DeviceDto.PutPaperStateRequest request = new DeviceDto.PutPaperStateRequest(true);

        controller.putPaperState(principal, request);

        verify(deviceWakeNotifier, times(1)).wake(eq("user-A"), anyString());
    }
}
