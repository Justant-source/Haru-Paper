package com.harupaper.server.schedule;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.device.DeviceRepository;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.RenderScanTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ScheduleService.createWithOwner/updateWithOwnerCheck의 포맷 소유권 검사 회귀 테스트
 * (.temp/06 1.4절 "추가로 발견한 것" — owner_user_id가 NULL인 포맷을 아무 사용자에게나 허용하던 구멍).
 *
 * ownershipStrict=false(기본)일 때는 지금 동작(NULL 포맷도 허용, 레거시 호환)을 그대로 지키고,
 * true일 때는 PrintNowController.java와 동일하게 NULL 포맷을 거부한다는 것을 고정한다.
 */
@DisplayName("ScheduleService - 포맷 소유권 검사(ownershipStrict)")
class ScheduleServiceOwnershipTest {

    private ScheduleRepository scheduleRepository;
    private FormatRepository formatRepository;
    private RenderScanTrigger renderScanTrigger;
    private DeviceRepository deviceRepository;

    private ScheduleService newService(boolean ownershipStrict) {
        scheduleRepository = mock(ScheduleRepository.class);
        formatRepository = mock(FormatRepository.class);
        renderScanTrigger = mock(RenderScanTrigger.class);
        deviceRepository = mock(DeviceRepository.class);
        when(deviceRepository.findByOwnerUserId(anyString())).thenReturn(Optional.empty());
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ScheduleService(scheduleRepository, formatRepository, renderScanTrigger, deviceRepository,
                ownershipStrict);
    }

    private CreateScheduleRequestDto recurringRequest(String formatId) {
        return new CreateScheduleRequestDto(
                formatId, "recurring", List.of("MON"), null, "07:00", true);
    }

    private Format formatOwnedBy(String ownerUserId) {
        return Format.builder()
                .id("f1")
                .name("test-format")
                .schemaVersion(2)
                .body("{}")
                .hasDynamicBlocks(false)
                .ownerUserId(ownerUserId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("createWithOwner: strict=false(기본)면 owner_user_id가 NULL인 포맷으로도 예약을 만들 수 있다 (지금 동작 유지)")
    void createWithOwner_nullFormatOwner_strictFalse_succeeds() {
        ScheduleService service = newService(false);
        when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy(null)));

        ScheduleResponseDto result = service.createWithOwner(recurringRequest("f1"), "user-A");

        assertEquals("f1", result.formatId());
        verify(scheduleRepository).save(any(Schedule.class));
    }

    @Test
    @DisplayName("createWithOwner: strict=true면 owner_user_id가 NULL인 포맷은 404 (PrintNowController와 동일)")
    void createWithOwner_nullFormatOwner_strictTrue_returns404() {
        ScheduleService service = newService(true);
        when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy(null)));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.createWithOwner(recurringRequest("f1"), "user-A"));
        assertEquals("Format not found: f1", ex.getMessage());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("createWithOwner: strict=true여도 소유자가 같으면 정상 생성된다")
    void createWithOwner_sameOwner_strictTrue_succeeds() {
        ScheduleService service = newService(true);
        when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy("user-A")));

        ScheduleResponseDto result = service.createWithOwner(recurringRequest("f1"), "user-A");

        assertEquals("f1", result.formatId());
    }

    @Test
    @DisplayName("createWithOwner: strict 무관하게 다른 사용자 소유 포맷은 항상 404")
    void createWithOwner_otherOwner_alwaysReturns404() {
        for (boolean strict : List.of(false, true)) {
            ScheduleService service = newService(strict);
            when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy("user-B")));

            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.createWithOwner(recurringRequest("f1"), "user-A"));
            assertEquals("Format not found: f1", ex.getMessage());
        }
    }

    @Test
    @DisplayName("updateWithOwnerCheck: strict=false(기본)면 owner_user_id가 NULL인 포맷으로도 수정할 수 있다")
    void updateWithOwnerCheck_nullFormatOwner_strictFalse_succeeds() {
        ScheduleService service = newService(false);
        Schedule existing = Schedule.builder()
                .id("s1")
                .ownerUserId("user-A")
                .formatId("f0")
                .type("recurring")
                .daysOfWeek("MON")
                .time(java.time.LocalTime.of(7, 0))
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(scheduleRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy(null)));

        ScheduleResponseDto result = service.updateWithOwnerCheck("s1", recurringRequest("f1"), "user-A");

        assertEquals("f1", result.formatId());
    }

    @Test
    @DisplayName("updateWithOwnerCheck: strict=true면 owner_user_id가 NULL인 포맷은 404")
    void updateWithOwnerCheck_nullFormatOwner_strictTrue_returns404() {
        ScheduleService service = newService(true);
        Schedule existing = Schedule.builder()
                .id("s1")
                .ownerUserId("user-A")
                .formatId("f0")
                .type("recurring")
                .daysOfWeek("MON")
                .time(java.time.LocalTime.of(7, 0))
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(scheduleRepository.findById("s1")).thenReturn(Optional.of(existing));
        when(formatRepository.findById("f1")).thenReturn(Optional.of(formatOwnedBy(null)));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> service.updateWithOwnerCheck("s1", recurringRequest("f1"), "user-A"));
        assertEquals("Format not found: f1", ex.getMessage());
    }
}
