package com.harupaper.server.device;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DeviceEventRegistry - SSE 깨우기 채널의 연결 레지스트리 검증
 * (docs/architecture.md 4.3절 — 신호 전용 채널, 명령 데이터·소유권 판정은 poll에 남는다).
 *
 * 가장 중요한 테스트는 소유자 격리(wake_doesNotLeakAcrossOwners) — 다른 사용자를 깨웠을 때
 * 내 emitter가 건드려지면 안 된다는 것. 변이 확인: wake()에서 streamsByOwner.get(ownerUserId)
 * 대신 모든 소유자를 순회하도록 임시로 바꿔보면 이 테스트가 실패하는지 확인했다(원상복구함).
 */
@DisplayName("DeviceEventRegistry - SSE 깨우기 레지스트리")
class DeviceEventRegistryTest {

    private DeviceEventRegistry registry;

    @BeforeEach
    void setUp() {
        // 하트비트 주기를 충분히 길게 둬 테스트 도중 백그라운드 하트비트가 send() 호출 수를
        // 흔들지 않게 한다(생성자가 바로 heartbeatSec 뒤로 스케줄을 건다).
        registry = new DeviceEventRegistry(3600);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    @DisplayName("등록한 소유자를 깨우면 그 emitter에 wake 이벤트가 1회 전송된다")
    void wake_sendsToRegisteredOwner() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register("user-A", emitter, "device-A");

        registry.wake("user-A", "test");

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("다른 소유자를 깨워도 내 emitter는 건드리지 않는다 — 소유자 격리 (가장 중요)")
    void wake_doesNotLeakAcrossOwners() throws Exception {
        SseEmitter emitterA = mock(SseEmitter.class);
        registry.register("user-A", emitterA, "device-A");

        registry.wake("user-B", "test");

        verify(emitterA, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("소유자당 상한(2)을 넘으면 가장 먼저 등록한 연결이 끊긴다")
    void register_evictsOldestWhenOverLimit() {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        SseEmitter third = mock(SseEmitter.class);

        registry.register("user-A", first, "device-A");
        registry.register("user-A", second, "device-A");
        registry.register("user-A", third, "device-A");

        verify(first, times(1)).complete();
        verify(second, never()).complete();
        verify(third, never()).complete();
        assertEquals(2, registry.connectionCount());
    }

    @Test
    @DisplayName("ownerUserId가 null이면 등록하지 않고 emitter를 즉시 완료한다")
    void register_nullOwner_completesImmediately() {
        SseEmitter emitter = mock(SseEmitter.class);

        registry.register(null, emitter, "device-X");

        verify(emitter, times(1)).complete();
        assertEquals(0, registry.connectionCount());
    }

    @Test
    @DisplayName("ownerUserId가 null이면 wake는 예외 없이 조용히 리턴한다")
    void wake_nullOwner_doesNothing() {
        assertDoesNotThrow(() -> registry.wake(null, "test"));
    }

    @Test
    @DisplayName("같은 소유자에게 짧은 간격으로 wake를 두 번 부르면 합체돼 1회만 전송된다")
    void wake_coalescesRapidCalls() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register("user-A", emitter, "device-A");

        registry.wake("user-A", "reason1");
        registry.wake("user-A", "reason2");

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("sendHeartbeats를 직접 호출하면 등록된 emitter에 하트비트(comment)가 전송된다")
    void sendHeartbeats_sendsToRegisteredEmitters() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register("user-A", emitter, "device-A");

        registry.sendHeartbeats();

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
