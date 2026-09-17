package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.command.Command;
import com.harupaper.server.command.CommandRepository;
import com.harupaper.server.render.RenderRepository;
import com.harupaper.server.render.RenderScanTrigger;
import com.harupaper.server.schedule.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * poll 응답의 명령(commands[]) 조회가 폴링한 기기의 소유자로 스코핑되는지 검증.
 *
 * 배경: DeviceSyncService.processPoll()이 commandRepository.findAllByStatusIn(...)을 그대로 써서
 * 모든 사용자의 pending/delivered 명령을 이 기기의 poll 응답에 실었다 — 사용자가 2명 이상이면
 * 남의 "지금 인쇄"가 내 프린터에서 나오는 버그. 같은 메서드의 buildSnapshot(device)는
 * findAllByOwnerUserId로 이미 스코핑돼 있었던 것과 대비된다.
 *
 * 만료 처리(commandRepository.findAllByStatusInAndCreatedAtBefore)는 의도적으로 전역 스캔을 유지한다 —
 * 상태만 "expired"로 바꿀 뿐 아무 데이터도 노출하지 않고, 기기별로 스코핑하면 한 번도 폴링하지 않는
 * 기기의 명령이 영영 만료되지 않기 때문이다(DeviceSyncService 주석 참고).
 *
 * device_id가 아니라 owner_user_id로 거르는 이유는 CommandRepository.findAllByOwnerUserIdAndStatusIn
 * 주석 참조 — V2의 uk_devices_owner가 1인 1기기를 보장하므로 보안상 동등하면서, 페어링 전에 만든
 * 명령(device_id=NULL)을 조용히 버리지 않는다.
 */
@DisplayName("DeviceSyncService - poll 명령 조회 소유자 스코핑")
class DeviceSyncServicePollScopingTest {

    private DeviceSyncService deviceSyncService;
    private DeviceRepository deviceRepository;
    private CommandRepository commandRepository;
    private ScheduleRepository scheduleRepository;
    private SnapshotHashCalculator snapshotHashCalculator;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        commandRepository = mock(CommandRepository.class);
        RenderRepository renderRepository = mock(RenderRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        ResultIngestService resultIngestService = mock(ResultIngestService.class);
        snapshotHashCalculator = mock(SnapshotHashCalculator.class);
        RenderScanTrigger renderScanTrigger = mock(RenderScanTrigger.class);
        ObjectMapper objectMapper = new ObjectMapper();

        deviceSyncService = new DeviceSyncService(
                deviceRepository, commandRepository, renderRepository, scheduleRepository,
                resultIngestService, objectMapper, snapshotHashCalculator, renderScanTrigger
        );

        when(scheduleRepository.findAllByOwnerUserId(anyString())).thenReturn(List.of());
        when(scheduleRepository.findAllByOwnerUserIdAndEnabledTrue(anyString())).thenReturn(List.of());
        when(snapshotHashCalculator.calculate(anyList(), anyList())).thenReturn("hash-x");
        when(commandRepository.findAllByStatusInAndCreatedAtBefore(anyList(), any())).thenReturn(List.of());
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("이 기기 소유자의 명령만 poll 응답 commands[]에 실린다 — 남의 명령은 안 섞인다")
    void poll_onlyReturnsCommandsForThisDevice() {
        Device deviceA = testDevice("device-A", "user-A");
        Command commandForA = testCommand("c1", "device-A");

        when(commandRepository.findAllByOwnerUserIdAndStatusIn(eq("user-A"), anyList()))
                .thenReturn(List.of(commandForA));
        // 예전 전역 조회가 다시 쓰이면(회귀) 다른 사용자(B)의 명령까지 섞이도록 스텁해 둔다.
        when(commandRepository.findAllByStatusIn(anyList()))
                .thenReturn(List.of(commandForA, testCommand("c2", "device-B")));

        DeviceDto.PollRequest request = new DeviceDto.PollRequest("1.0.0", null, null, null, null);
        DeviceDto.PollResponse response = deviceSyncService.processPoll(deviceA, request);

        assertEquals(1, response.commands().size());
        assertEquals("c1", response.commands().get(0).commandId());
        // 소유권 스코핑 없는 메서드는 더 이상 쓰이지 않아야 한다.
        verify(commandRepository, never()).findAllByStatusIn(anyList());
        verify(commandRepository).findAllByOwnerUserIdAndStatusIn(eq("user-A"), anyList());
    }

    @Test
    @DisplayName("다른 사용자 소유 명령은 poll 응답에 절대 섞이지 않는다")
    void poll_neverLeaksOtherDevicesCommands() {
        Device deviceB = testDevice("device-B", "user-B");
        when(commandRepository.findAllByOwnerUserIdAndStatusIn(eq("user-B"), anyList()))
                .thenReturn(List.of());

        DeviceDto.PollRequest request = new DeviceDto.PollRequest("1.0.0", null, null, null, null);
        DeviceDto.PollResponse response = deviceSyncService.processPoll(deviceB, request);

        assertTrue(response.commands().isEmpty());
    }

    @Test
    @DisplayName("페어링 전에 만든 명령(device_id=NULL)도 소유자 스코핑이라 전달된다")
    void poll_deliversCommandCreatedBeforePairing() {
        // PrintNowController는 사용자가 아직 기기를 페어링하지 않았으면 device_id를 NULL로 둔다.
        // device_id로 스코핑하면 이 명령은 어디에도 실리지 않고 10분 뒤 조용히 expired가 된다 —
        // 앱에는 202만 뜨고 아무 일도 일어나지 않는다. owner_user_id 스코핑은 그걸 막는다.
        Device deviceA = testDevice("device-A", "user-A");
        Command prePairingCommand = testCommand("c3", null);

        when(commandRepository.findAllByOwnerUserIdAndStatusIn(eq("user-A"), anyList()))
                .thenReturn(List.of(prePairingCommand));

        DeviceDto.PollRequest request = new DeviceDto.PollRequest("1.0.0", null, null, null, null);
        DeviceDto.PollResponse response = deviceSyncService.processPoll(deviceA, request);

        assertEquals(1, response.commands().size());
        assertEquals("c3", response.commands().get(0).commandId());
    }

    private Device testDevice(String id, String ownerUserId) {
        return Device.builder()
                .id(id)
                .ownerUserId(ownerUserId)
                .name("test-device")
                .tokenHash("hash")
                .tokenIssuedAt(Instant.now())
                .paperStateManual(false)
                .createdAt(Instant.now())
                .build();
    }

    private Command testCommand(String id, String deviceId) {
        return Command.builder()
                .id(id)
                .deviceId(deviceId)
                .type("print_now")
                .formatId("f1")
                .paperConfirmed(true)
                .status("pending")
                .createdAt(Instant.now())
                .build();
    }
}
