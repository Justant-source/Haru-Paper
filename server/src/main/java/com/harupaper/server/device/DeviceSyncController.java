package com.harupaper.server.device;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderOwnership;
import com.harupaper.server.render.RenderRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Device Sync API for Pi (Bearer 토큰 인증).
 * POST /api/device/poll, GET /api/device/snapshot, GET /api/device/renders/{renderId}.png,
 * GET /api/device/renders/{renderId}.pbm, POST /api/device/results
 * (docs/server/api.md 6절)
 */
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
@Slf4j
public class DeviceSyncController {

    private final DeviceSyncService deviceSyncService;
    private final RenderRepository renderRepository;

    @Value("${haru.files-dir:/data/haru-files}")
    private String filesDir;

    /**
     * owner_user_id가 NULL인 렌더·소유권이 갈라진 예약을 어떻게 다룰지. false(기본)=레거시로 보고
     * 허용, true=거부(404). V4 백필 + claim-legacy로 NULL이 0이 된 뒤에만 true로 켠다
     * (.temp/06 1.5절, docs/server/deploy.md "V4 적용 + claim-legacy" 절).
     * 기본값이 false인 이유: 이 값이 true인 채로 레거시 NULL이 남아 있으면 Pi가 렌더를 못 받고
     * 예약이 조용히 "failed"가 된다(.temp/06 1.2절). "렌더"로 이름을 한정하지 않은 이유는
     * ScheduleService의 NULL 포맷 소유권 검사(A-2)도 같은 플래그 아래 있기 때문이다.
     */
    @Value("${haru.ownership-strict:false}")
    private boolean ownershipStrict;

    /**
     * POST /api/device/poll (30초마다)
     * (docs/server/api.md 6절 "poll 처리 순서")
     */
    @PostMapping("/poll")
    public ResponseEntity<DeviceDto.PollResponse> poll(
            HttpServletRequest request,
            @RequestBody DeviceDto.PollRequest pollRequest) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);

        log.debug("Poll received from Pi: agentVersion={}, snapshotHash={}",
                pollRequest.agentVersion(), pollRequest.snapshotHash());

        DeviceDto.PollResponse response = deviceSyncService.processPoll(device, pollRequest);

        log.debug("Poll response: snapshotChanged={}, commandCount={}",
                response.snapshotChanged(), response.commands().size());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/device/snapshot
     * snapshotChanged일 때만 Pi가 부른다.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<DeviceDto.SnapshotResponse> getSnapshot(HttpServletRequest request) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);

        log.debug("Snapshot requested by Pi");

        DeviceDto.SnapshotResponse response = deviceSyncService.buildSnapshot(device);

        log.debug("Snapshot response: scheduleCount={}, renderCount={}",
                response.schedules().size(), response.renders().size());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/device/renders/{renderId}.png
     * 렌더 PNG 파일 다운로드
     */
    @GetMapping("/renders/{renderId}.png")
    public ResponseEntity<Resource> getRenderImage(HttpServletRequest request, @PathVariable String renderId) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);
        Render render = renderRepository.findById(renderId)
                .orElseThrow(() -> new NotFoundException("Render not found: " + renderId));
        assertOwnership(device, render, renderId);

        // 파일 경로: {filesDir}/renders/{renderId}.png
        Path filePath = Paths.get(filesDir, render.getPath());
        File file = filePath.toFile();

        if (!file.exists()) {
            throw new NotFoundException("Render file not found: " + renderId);
        }

        try {
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.ETAG, "\"" + render.getSha256() + "\"")
                    // 개인 인쇄물이므로 공유 캐시(CDN·프록시)에는 두지 않는다 — public이면 다른 기기의
                    // 캐싱 경유지에 저장될 수 있다.
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000")
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to read render file: {}", renderId, e);
            throw new NotFoundException("Failed to read render file");
        }
    }

    /**
     * GET /api/device/renders/{renderId}.pbm
     * 같은 렌더의 1-bpp(PBM P4) 다운로드. 2단계 MCU 기기용, 2026-09-17 승인
     * (docs/architecture.md 3.4). PBM이 없는 렌더(V3 마이그레이션 이전 또는 생성 실패)면 404.
     */
    @GetMapping("/renders/{renderId}.pbm")
    public ResponseEntity<Resource> getRenderPbm(HttpServletRequest request, @PathVariable String renderId) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);
        Render render = renderRepository.findById(renderId)
                .orElseThrow(() -> new NotFoundException("Render not found: " + renderId));
        assertOwnership(device, render, renderId);

        if (render.getPbmPath() == null) {
            throw new NotFoundException("PBM not available for render: " + renderId);
        }

        // 파일 경로: {filesDir}/renders/{renderId}.pbm
        Path filePath = Paths.get(filesDir, render.getPbmPath());
        File file = filePath.toFile();

        if (!file.exists()) {
            throw new NotFoundException("Render PBM file not found: " + renderId);
        }

        try {
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("image/x-portable-bitmap"))
                    .header(HttpHeaders.ETAG, "\"" + render.getPbmSha256() + "\"")
                    // 개인 인쇄물이므로 공유 캐시에는 두지 않는다 (getRenderImage와 동일한 이유).
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000")
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to read render PBM file: {}", renderId, e);
            throw new NotFoundException("Failed to read render PBM file");
        }
    }

    /**
     * 폴링한 기기의 소유자와 렌더 소유자가 다르면 404로 응답한다(존재 여부를 노출하지 않는다,
     * docs/server/auth.md 7절). renderId는 UUIDv4라 추측이 사실상 불가능하지만, 유효한 기기 토큰
     * 하나로 남의 renderId를 넣으면 지금까지는 그대로 통과했다(IDOR).
     *
     * render.ownerUserId가 NULL인 경우는 레거시로 보고 허용하되 경고 로그를 남긴다. NULL이 되는 경로는
     * 둘뿐이다: (a) V4 백필 이전에 만들어진 렌더, (b) 소유자가 아직 NULL인 레거시 포맷(claim-legacy 전)에서
     * 파생된 렌더. **포맷이 지워진 "고아 렌더"는 NULL 사유가 아니다** — V1의 `fk_renders_format`이
     * `ON DELETE CASCADE`라 포맷이 지워지면 렌더 행도 함께 지워진다.
     * 허용하는 이유는 Pi가 상시 구동 중이기 때문이다. V4 적용 + claim-legacy를 마치면 NULL은 0이 되고
     * 더 생기지 않으므로, 그 시점 이후의 NULL은 버그 신호다 — {@code ownershipStrict} 플래그를 켜면
     * 거부로 바뀐다. 켜는 절차는 `docs/server/deploy.md`.
     */
    private void assertOwnership(Device device, Render render, String renderId) {
        if (device == null) {
            throw new NotFoundException("Render not found: " + renderId);
        }
        // 규칙 본문은 RenderOwnership으로 옮겼다(2026-09-19) — HistoryController(이력 렌더 이미지)도
        // 같은 규칙을 쓴다.
        RenderOwnership.assertAccessible(render, renderId, device.getOwnerUserId(), ownershipStrict,
                "device=" + device.getId());
    }

    /**
     * POST /api/device/results
     * Pi의 실행 결과 업로드 (멱등 처리)
     */
    @PostMapping("/results")
    public ResponseEntity<DeviceDto.ResultsResponse> postResults(
            HttpServletRequest request,
            @RequestBody DeviceDto.ResultsRequest resultsRequest) {
        Device device = (Device) request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE);

        if (resultsRequest == null || resultsRequest.results() == null) {
            throw new ValidationException("results is required", List.of(
                    new ValidationException.FieldError("results", "must not be null")
            ));
        }

        log.debug("Results received from Pi: {} results", resultsRequest.results().size());

        DeviceDto.ResultsResponse response = deviceSyncService.processResults(device, resultsRequest);

        log.debug("Results processed: {} accepted, {} duplicates",
                response.accepted().size(), response.duplicates().size());

        return ResponseEntity.ok(response);
    }
}
