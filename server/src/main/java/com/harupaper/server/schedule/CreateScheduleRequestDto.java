package com.harupaper.server.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * POST /api/schedules, PUT /api/schedules/{id} 요청 본문
 */
public record CreateScheduleRequestDto(
        String formatId,
        String type,        // "recurring" | "once"
        List<String> daysOfWeek,  // recurring일 때 필수
        LocalDate date,           // once일 때 필수
        String time,              // HH:mm (KST)
        Boolean enabled           // 생략 시 true로 기본값
) {}
