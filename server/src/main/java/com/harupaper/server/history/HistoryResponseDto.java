package com.harupaper.server.history;

/**
 * GET /api/history 응답 항목
 * Result + formatName + source 필드 추가
 */
public record HistoryResponseDto(
        String resultId,
        String occurrenceKey,
        String commandId,
        String formatId,
        String formatName,        // 포맷이 삭제됐으면 null
        String renderId,
        String status,
        String detail,
        String scheduledAt,       // ISO-8601 with +09:00 offset (nullable)
        String executedAt,        // ISO-8601 with +09:00 offset
        String source             // "schedule" | "command"
) {}
