package com.harupaper.server.device;

import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.common.time.TimeUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Device API for web app (인증 없음, tailnet이 인증).
 * GET /api/device, PUT /api/device/paper-state
 * (docs/server/api.md 5절 "기기")
 */
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {

    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    @Value("${haru.poll-interval-sec:30}")
    private Integer pollIntervalSec;

    /**
     * GET /api/device
     * 기기 상태 조회 (앱용)
     */
    @GetMapping
    public ResponseEntity<DeviceDto.GetDeviceResponse> getDevice() {
        Device device = deviceRepository.findById(1).orElseGet(() ->
                Device.builder().id(1).paperStateManual(false).build()
        );

        // online = lastPollAt이 90초(pollIntervalSec × 3) 이내
        boolean online = false;
        if (device.getLastPollAt() != null) {
            Instant threshold = Instant.now().minusSeconds((long) pollIntervalSec * 3);
            online = device.getLastPollAt().isAfter(threshold);
        }

        // printerProfile: JSON 문자열 → PrinterProfile record
        PrinterProfile printerProfile = null;
        if (device.getPrinterProfile() != null && !device.getPrinterProfile().isBlank()) {
            try {
                printerProfile = objectMapper.readValue(device.getPrinterProfile(), PrinterProfile.class);
            } catch (Exception e) {
                log.warn("Failed to parse printer profile", e);
            }
        }

        // printerStatus: JSON 문자열 → PrinterStatus record
        DeviceDto.PrinterStatus printerStatus = null;
        if (device.getPrinterStatus() != null && !device.getPrinterStatus().isBlank()) {
            try {
                printerStatus = objectMapper.readValue(device.getPrinterStatus(), DeviceDto.PrinterStatus.class);
            } catch (Exception e) {
                log.warn("Failed to parse printer status", e);
            }
        }

        // paperState
        DeviceDto.PaperState paperState = new DeviceDto.PaperState(
                device.getPaperStateManual(),
                device.getPaperStateUpdatedAt() != null ? TimeUtils.toIso8601(device.getPaperStateUpdatedAt()) : null,
                device.getPaperStateUpdatedBy()
        );

        DeviceDto.GetDeviceResponse response = new DeviceDto.GetDeviceResponse(
                "1",
                online,
                device.getLastPollAt() != null ? TimeUtils.toIso8601(device.getLastPollAt()) : null,
                device.getAgentVersion(),
                printerProfile,
                printerStatus,
                device.getPaperPolicy(),
                paperState
        );

        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/device/paper-state
     * 수동 용지 상태 갱신 (앱용)
     */
    @PutMapping("/paper-state")
    public ResponseEntity<DeviceDto.PutPaperStateResponse> putPaperState(
            @RequestBody DeviceDto.PutPaperStateRequest request) {
        if (request == null || request.loaded() == null) {
            throw new ValidationException("loaded is required", List.of(
                    new ValidationException.FieldError("loaded", "must not be null")
            ));
        }

        Device device = deviceRepository.findById(1).orElseGet(() ->
                Device.builder().id(1).paperStateManual(false).build()
        );

        Instant now = Instant.now();
        device.setPaperStateManual(request.loaded());
        device.setPaperStateUpdatedAt(now);
        device.setPaperStateUpdatedBy("app");

        device = deviceRepository.save(device);

        DeviceDto.PutPaperStateResponse response = new DeviceDto.PutPaperStateResponse(
                device.getPaperStateManual(),
                TimeUtils.toIso8601(device.getPaperStateUpdatedAt()),
                device.getPaperStateUpdatedBy()
        );

        return ResponseEntity.ok(response);
    }
}
