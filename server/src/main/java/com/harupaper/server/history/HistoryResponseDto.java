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
        String source,            // "schedule" | "command"
        // renderId가 있어도 RenderCleanupScheduler가 7일 지난 scheduled 렌더를 지운다(2026-09-19 추가) —
        // 앱이 renderId만 보고 <img>를 걸면 깨진 이미지가 뜬다. GET /{resultId}/render.png를 부를 가치가
        // 있는지 여기서 미리 알려준다.
        boolean renderAvailable
) {}
