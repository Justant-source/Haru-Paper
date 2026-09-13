package com.harupaper.server.device;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Device domain DTOs for API responses and requests.
 * All timestamps are ISO-8601 with +09:00 offset in API responses, but stored as UTC.
 */
public class DeviceDto {

    /**
     * GET /api/device response (앱용 - 인증 없음).
     * Pi가 아직 poll하지 않은 필드는 값이 null일 뿐 키 자체는 항상 내려준다
     * (docs/server/api.md 5절 응답 형태 고정 — @JsonInclude(NON_NULL)로 필드를 숨기지 않는다).
     */
    public record GetDeviceResponse(
            String deviceId,
            Boolean online,
            String lastPollAt,
            String agentVersion,
            PrinterProfile printerProfile,
            PrinterStatus printerStatus,
            String paperPolicy,
            PaperState paperState
    ) {}

    public record PrinterStatus(
            String state,     // "ok" | "offline" | "error" | "unknown"
            String detail
    ) {}

    public record PaperState(
            Boolean loaded,
            String updatedAt,  // ISO-8601 or null
            String updatedBy   // "app" | "server" or null
    ) {}

    /**
     * PUT /api/device/paper-state request
     */
    public record PutPaperStateRequest(Boolean loaded) {}

    /**
     * PUT /api/device/paper-state response
     */
    public record PutPaperStateResponse(
            Boolean loaded,
            String updatedAt,
            String updatedBy
    ) {}

    /**
     * POST /api/device/poll request (Pi용 - Bearer 토큰 인증)
     */
    public record PollRequest(
            String agentVersion,
            PrinterProfile printerProfile,
            PrinterStatus printerStatus,
            String paperPolicy,
            String snapshotHash  // null일 수 있음 (처음 poll)
    ) {}

    /**
     * POST /api/device/poll response
     */
    public record PollResponse(
            String serverTime,        // ISO-8601
            String snapshotHash,
            Boolean snapshotChanged,
            List<CommandDto> commands,
            PaperState paperState,
            Integer pollIntervalSec
    ) {}

    public record CommandDto(
            String commandId,
            String type,             // "print_now"
            String formatId,
            String renderId,
            String sha256,           // 렌더 PNG의 SHA-256 해시
            Boolean paperConfirmed,
            String createdAt         // ISO-8601
    ) {}

    /**
     * GET /api/device/snapshot response
     */
    public record SnapshotResponse(
            String snapshotHash,
            String generatedAt,      // ISO-8601
            List<ScheduleDto> schedules,
            List<RenderDto> renders
    ) {}

    public record ScheduleDto(
            String id,
            String formatId,
            String type,             // "recurring" | "once"
            List<String> daysOfWeek,// null if type="once", values like ["MON", "TUE", ...]
            String time,             // "HH:mm" (KST)
            String date,             // "YYYY-MM-DD" (KST) - null if type="recurring"
            Boolean enabled
    ) {}

    public record RenderDto(
            String renderId,
            String formatId,
            String targetDate,       // "YYYY-MM-DD"
            String sha256,
            Integer widthPx,
            String renderedAt,       // ISO-8601
            String url               // "/api/device/renders/{renderId}.png"
    ) {}

    /**
     * POST /api/device/results request
     */
    public record ResultsRequest(
            List<ResultDto> results
    ) {}

    public record ResultDto(
            String resultId,
            String occurrenceKey,    // null if from command
            String commandId,        // null if from schedule
            String formatId,
            String renderId,
            String status,           // "printed", "dry_run", "missed", "failed", "skipped_no_paper", "skipped_clock_unsynced", "skipped_printer_offline"
            String detail,
            String scheduledAt,      // ISO-8601, null if command
            String executedAt       // ISO-8601
    ) {}

    /**
     * POST /api/device/results response
     */
    public record ResultsResponse(
            List<String> accepted,
            List<String> duplicates
    ) {}

    /**
     * 스냅샷 해시 계산에 필요한 데이터 구조 (내부용)
     */
    public record SnapshotInput(
            List<ScheduleHashInput> schedules,
            List<RenderHashInput> renders
    ) {}

    public record ScheduleHashInput(
            String id,
            String formatId,
            String type,
            List<String> daysOfWeek,
            String time,
            String date,
            Boolean enabled
    ) {}

    public record RenderHashInput(
            String renderId,
            String formatId,
            String targetDate,
            String sha256
    ) {}
}
