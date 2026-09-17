package com.harupaper.server.device;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 깨우기 채널의 연결 레지스트리 ({@code GET /api/device/events}).
 *
 * 이벤트는 "확인할 게 있다"는 신호만 싣는다(docs/architecture.md 4.3절) — 명령 데이터·소유권
 * 판정·중복 제거·TTL은 전부 poll 경로(DeviceSyncService)에 남는다. 이 클래스는 그 신호를
 * 누구에게 보낼지만 관리한다.
 *
 * 키는 device_id가 아니라 owner_user_id다. 이유:
 * - poll의 명령 조회도 이미 소유자 스코핑이다(DeviceSyncService.processPoll,
 *   commandRepository.findAllByOwnerUserIdAndStatusIn) — 깨우기 스코프를 poll 스코프와
 *   동일하게 두면 "깨웠는데 poll에 아무것도 없다" 류의 불일치가 구조적으로 없다.
 * - PrintNowController는 페어링 전에도 명령을 만들 수 있어 deviceId가 NULL일 수 있다.
 * - V2의 uk_devices_owner(UNIQUE)가 1인 1기기를 보장하므로 라우팅 키로 동등하다.
 */
@Slf4j
@Component
public class DeviceEventRegistry {

    /** 소유자당 동시 연결 상한. Pi는 1개만 열지만 재연결 겹침 구간을 위해 여유를 둔다. */
    private static final int MAX_STREAMS_PER_OWNER = 2;

    /** 같은 소유자에게 이 시간 안에 또 깨우면 건너뛴다(서버측 합체) — 편집 연타로 폴링이 몰아치지 않게. */
    private static final long WAKE_COALESCE_MS = 1000;

    private final Map<String, CopyOnWriteArrayList<Stream>> streamsByOwner = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastWakeAtByOwner = new ConcurrentHashMap<>();

    /**
     * 하트비트 전용 단일 스레드. Spring 기본 @Scheduled 풀은 이미 RenderScheduler의 동기 렌더
     * (수 초~수십 초, Playwright)와 정리 스케줄러들이 공유하고 있어(server/render/RenderScheduler.java),
     * 거기 얹으면 렌더가 도는 동안 하트비트가 밀려 프록시 idle 타임아웃에 걸린다 — 그래서 전용 executor를 쓴다.
     */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "device-event-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final long heartbeatSec;

    public DeviceEventRegistry(@Value("${haru.sse.heartbeat-sec:15}") long heartbeatSec) {
        this.heartbeatSec = heartbeatSec;
        heartbeatExecutor.scheduleWithFixedDelay(
                this::sendHeartbeats, heartbeatSec, heartbeatSec, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /** 새 SSE 연결을 등록한다. 상한을 넘으면 가장 오래된 연결을 끊고 자리를 만든다. */
    public void register(String ownerUserId, SseEmitter emitter, String deviceId) {
        if (ownerUserId == null) {
            log.warn("Refusing to register SSE stream with null ownerUserId (deviceId={})", deviceId);
            emitter.complete();
            return;
        }
        Stream stream = new Stream(emitter, ownerUserId, deviceId);
        CopyOnWriteArrayList<Stream> streams =
                streamsByOwner.computeIfAbsent(ownerUserId, k -> new CopyOnWriteArrayList<>());
        streams.add(stream);

        while (streams.size() > MAX_STREAMS_PER_OWNER) {
            Stream oldest = streams.stream()
                    .min((a, b) -> a.connectedAt.compareTo(b.connectedAt))
                    .orElse(null);
            if (oldest == null || oldest == stream) {
                break;
            }
            streams.remove(oldest);
            oldest.emitter.complete();
        }

        emitter.onCompletion(() -> remove(ownerUserId, stream));
        emitter.onTimeout(() -> {
            remove(ownerUserId, stream);
            emitter.complete();
        });
        emitter.onError(e -> remove(ownerUserId, stream));
    }

    private void remove(String ownerUserId, Stream stream) {
        CopyOnWriteArrayList<Stream> streams = streamsByOwner.get(ownerUserId);
        if (streams != null) {
            streams.remove(stream);
        }
    }

    /**
     * 해당 소유자의 모든 연결에 깨우기 신호를 보낸다. 절대 예외를 던지지 않는다 — 호출자는
     * 대개 앱 요청을 처리 중인 스레드다(PrintNowController 등).
     */
    public void wake(String ownerUserId, String reason) {
        if (ownerUserId == null) {
            return;
        }
        AtomicLong lastWakeAt = lastWakeAtByOwner.computeIfAbsent(ownerUserId, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long prev = lastWakeAt.get();
        if (now - prev < WAKE_COALESCE_MS) {
            return;
        }
        if (!lastWakeAt.compareAndSet(prev, now)) {
            return;
        }

        CopyOnWriteArrayList<Stream> streams = streamsByOwner.get(ownerUserId);
        if (streams == null || streams.isEmpty()) {
            return;
        }
        String payload = "{\"reason\":\"" + reason + "\"}";
        for (Stream stream : streams) {
            sendSafely(ownerUserId, stream, emitter -> emitter.send(
                    SseEmitter.event().name("wake").data(payload)));
        }
    }

    void sendHeartbeats() {
        for (Map.Entry<String, CopyOnWriteArrayList<Stream>> entry : streamsByOwner.entrySet()) {
            for (Stream stream : entry.getValue()) {
                sendSafely(entry.getKey(), stream, emitter -> emitter.send(SseEmitter.event().comment("hb")));
            }
        }
    }

    private void sendSafely(String ownerUserId, Stream stream, EmitterAction action) {
        synchronized (stream.sendLock) {
            try {
                action.run(stream.emitter);
            } catch (Exception e) {
                remove(ownerUserId, stream);
                try {
                    stream.emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // 이미 닫혔을 수 있다 — 무시
                }
            }
        }
    }

    /** 테스트·운영 관측용: 현재 등록된 총 연결 수. */
    public int connectionCount() {
        int total = 0;
        for (List<Stream> streams : streamsByOwner.values()) {
            total += streams.size();
        }
        return total;
    }

    private interface EmitterAction {
        void run(SseEmitter emitter) throws Exception;
    }

    private static final class Stream {
        final SseEmitter emitter;
        final String ownerUserId;
        final String deviceId;
        final Instant connectedAt = Instant.now();
        final Object sendLock = new Object();

        Stream(SseEmitter emitter, String ownerUserId, String deviceId) {
            this.emitter = emitter;
            this.ownerUserId = ownerUserId;
            this.deviceId = deviceId;
        }
    }
}
