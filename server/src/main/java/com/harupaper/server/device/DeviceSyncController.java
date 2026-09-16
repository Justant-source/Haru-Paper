package com.harupaper.server.device;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.render.Render;
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
 * POST /api/device/poll, GET /api/device/snapshot, GET /api/device/renders/{renderId}.png, POST /api/device/results
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
    public ResponseEntity<Resource> getRenderImage(@PathVariable String renderId) {
        Render render = renderRepository.findById(renderId)
                .orElseThrow(() -> new NotFoundException("Render not found: " + renderId));

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
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000")
                    .body(resource);
        } catch (Exception e) {
            log.error("Failed to read render file: {}", renderId, e);
            throw new NotFoundException("Failed to read render file");
        }
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
