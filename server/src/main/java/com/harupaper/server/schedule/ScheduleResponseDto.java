package com.harupaper.server.schedule;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * GET /api/schedules, POST/PUT /api/schedules/{id} 응답 본문
 */
public record ScheduleResponseDto(
        String id,
        String formatId,
        String type,
        List<String> daysOfWeek,
        LocalDate date,
        @JsonFormat(pattern = "HH:mm")
        LocalTime time,
        Boolean enabled,
        String nextOccurrenceAt  // ISO-8601 with +09:00 offset, null if no next occurrence
) {}

