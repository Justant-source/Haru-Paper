package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PrinterProfileProviderImpl - "아무 기기나 하나" 폴백 제거 후 getCurrentProfile(String)만 남았는지,
 * 소유자별로 올바른 기기를 찾는지, 소유자를 모르거나(NULL) 기기가 없을 때 DEFAULT로 폴백하는지 검증한다.
 */
@DisplayName("PrinterProfileProviderImpl")
class PrinterProfileProviderImplTest {

    private DeviceRepository deviceRepository;
    private PrinterProfileProviderImpl provider;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        provider = new PrinterProfileProviderImpl(deviceRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("소유자의 기기 프로필이 있으면 그 프로필을 돌려준다")
    void returnsOwnersDeviceProfile() throws Exception {
        Device device = Device.builder()
                .id("d1")
                .ownerUserId("user-1")
                .printerProfile("{\"model\":\"m832\",\"dpi\":300,\"paperWidthMm\":110,\"printableWidthPx\":1300}")
                .build();
        when(deviceRepository.findByOwnerUserId("user-1")).thenReturn(Optional.of(device));

        PrinterProfile profile = provider.getCurrentProfile("user-1");

        assertEquals(new PrinterProfile("m832", 300, 110, 1300), profile);
    }

    @Test
    @DisplayName("사용자마다 다른 기기 프로필을 정확히 구분한다(멀티유저)")
    void distinguishesProfilesPerOwner() {
        Device device1 = Device.builder().id("d1").ownerUserId("user-1")
                .printerProfile("{\"model\":\"m832\",\"dpi\":300,\"paperWidthMm\":110,\"printableWidthPx\":1300}")
                .build();
        Device device2 = Device.builder().id("d2").ownerUserId("user-2")
                .printerProfile("{\"model\":\"m832\",\"dpi\":203,\"paperWidthMm\":80,\"printableWidthPx\":576}")
                .build();
        when(deviceRepository.findByOwnerUserId("user-1")).thenReturn(Optional.of(device1));
        when(deviceRepository.findByOwnerUserId("user-2")).thenReturn(Optional.of(device2));

        assertEquals("m832-300-110-1300", provider.getCurrentProfile("user-1").profileKey());
        assertEquals("m832-203-80-576", provider.getCurrentProfile("user-2").profileKey());
    }

    @Test
    @DisplayName("그 사용자의 기기가 없으면 DEFAULT")
    void noDeviceForOwner_returnsDefault() {
        when(deviceRepository.findByOwnerUserId("user-3")).thenReturn(Optional.empty());

        assertEquals(PrinterProfile.DEFAULT, provider.getCurrentProfile("user-3"));
    }

    @Test
    @DisplayName("ownerUserId가 NULL이면(레거시, claim-legacy 전) 리포지토리를 조회하지 않고 DEFAULT")
    void nullOwnerUserId_returnsDefaultWithoutQuery() {
        PrinterProfile profile = provider.getCurrentProfile(null);

        assertEquals(PrinterProfile.DEFAULT, profile);
        verify(deviceRepository, never()).findByOwnerUserId(any());
    }

    @Test
    @DisplayName("printer_profile JSON이 깨져 있으면 DEFAULT (예외를 던지지 않는다)")
    void malformedProfileJson_returnsDefault() {
        Device device = Device.builder().id("d1").ownerUserId("user-1")
                .printerProfile("not-json").build();
        when(deviceRepository.findByOwnerUserId("user-1")).thenReturn(Optional.of(device));

        assertDoesNotThrow(() -> {
            PrinterProfile profile = provider.getCurrentProfile("user-1");
            assertEquals(PrinterProfile.DEFAULT, profile);
        });
    }
}
