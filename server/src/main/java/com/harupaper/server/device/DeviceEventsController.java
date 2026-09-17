package com.harupaper.server.device;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * GET /api/device/events — Pi가 상시로 열어두는 SSE "깨우기" 채널.
 *
 * 이 연결은 "확인할 게 있다"는 신호(event: wake)만 보낸다. 명령 데이터·소유권 판정·중복
 * 제거·TTL은 전부 POST /api/device/poll(DeviceSyncController)에 그대로 남는다 — 이 컨트롤러는
 * poll을 앞당겨 부르게 할 뿐, poll을 대체하지 않는다.
 *
 * 이 연결은 device.lastPollAt/online을 절대 건드리지 않는다: SSE 연결은 소켓이 열려 있다만
 * 증명하고, lastPollAt은 Pi가 폴링을 완주해 프린터 프로필·상태가 실제로 갱신됐다는 뜻이다 —
 * 두 의미를 섞으면 앱이 죽은 Pi를 온라인으로 표시할 수 있다.
 */
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceEventsController {

    private final DeviceEventRegistry deviceEventRegistry;

    // emitter 자체 타임아웃. 무한 연결로 두면 프록시가 조용히 끊었을 때 서버쪽 emitter가 남는다.
    // 30분마다 스스로 끊어 Pi가 재연결하면 양쪽 상태가 주기적으로 정리된다.
    @Value("${haru.sse.timeout-sec:1800}")
    private long sseTimeoutSec;

    @GetMapping("/events")
    public SseEmitter events(HttpServletRequest request) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);

        // 비동기 dispatch로 전환되면 이 요청 스레드는 컨트롤러 메서드 종료와 함께 반환된다.
        // emitter의 onCompletion/onTimeout/onError 콜백은 나중에 다른 스레드에서 실행되므로,
        // 그 시점엔 이미 반환된 request나 device 객체를 다시 참조하면 안 된다 — 미리 String으로
        // 복사해 둔다.
        String ownerUserId = device.getOwnerUserId();
        String deviceId = device.getId();

        SseEmitter emitter = new SseEmitter(sseTimeoutSec * 1000);
        deviceEventRegistry.register(ownerUserId, emitter, deviceId);

        try {
            // Pi가 연결 성립을 확인하고 재연결 백오프를 초기화하는 신호.
            emitter.send(SseEmitter.event().name("ready"));
        } catch (IOException e) {
            // 전송 시점에 이미 끊겼을 수 있다 — DeviceEventRegistry가 아니라 여기서 처음 보내는
            // 것이므로 로그만 남기고 넘어간다(register가 이미 onError로 정리를 처리한다).
            log.warn("Failed to send initial SSE ready event: deviceId={}", deviceId, e);
        }

        return emitter;
    }
}
