package com.harupaper.server.command;

/**
 * POST /api/print-now 응답 본문 (202)
 */
public record PrintNowResponseDto(
        String commandId,
        String formatId,
        String renderId,
        Boolean paperConfirmed,
        String status,         // "pending"
        String createdAt       // ISO-8601 with +09:00 offset
) {}
