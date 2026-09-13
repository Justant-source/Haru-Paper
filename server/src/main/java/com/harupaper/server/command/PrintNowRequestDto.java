package com.harupaper.server.command;

/**
 * POST /api/print-now 요청 본문
 */
public record PrintNowRequestDto(
        String formatId,
        Boolean paperConfirmed  // 앱의 "용지를 눈으로 확인함" 체크 (nullable, 기본값 false)
) {}
