package com.harupaper.server.render;

import com.harupaper.server.device.DeviceWakeNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RenderScanTrigger.requestScan(ownerUserId) - 스캔 이후에 소유자를 깨우는지 검증
 * (RenderScanTrigger.java 주석 참고 — 스캔 전에 깨우면 Pi가 렌더 없는 스냅샷을 받는다).
 *
 * plain JUnit 환경에는 트랜잭션 동기화가 없으므로 runScan()은 즉시 CompletableFuture.runAsync로
 * 실행된다 — 다른 스레드이므로 verify에 timeout을 줘야 한다.
 */
@DisplayName("RenderScanTrigger - 스캔 후 기기 깨우기")
class RenderScanTriggerWakeTest {

    private RenderScheduler renderScheduler;
    private DeviceWakeNotifier deviceWakeNotifier;
    private RenderScanTrigger trigger;

    @BeforeEach
    void setUp() {
        renderScheduler = mock(RenderScheduler.class);
        deviceWakeNotifier = mock(DeviceWakeNotifier.class);
        trigger = new RenderScanTrigger(renderScheduler, deviceWakeNotifier);
    }

    @Test
    @DisplayName("requestScan(ownerUserId) 호출 시 스캔과 깨우기가 둘 다 일어난다")
    void requestScan_runsScanAndWakesOwner() {
        trigger.requestScan("user-A");

        verify(renderScheduler, timeout(1000)).scanAndRender();
        verify(deviceWakeNotifier, timeout(1000)).wake(eq("user-A"), anyString());
    }

    @Test
    @DisplayName("scanAndRender가 예외를 던져도 깨우기는 여전히 일어난다")
    void requestScan_stillWakesWhenScanThrows() {
        doThrow(new RuntimeException("scan failed")).when(renderScheduler).scanAndRender();

        trigger.requestScan("user-A");

        verify(renderScheduler, timeout(1000)).scanAndRender();
        verify(deviceWakeNotifier, timeout(1000)).wake(eq("user-A"), anyString());
    }
}
