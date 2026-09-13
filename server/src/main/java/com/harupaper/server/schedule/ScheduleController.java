package com.harupaper.server.schedule;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Schedule(예약) 엔드포인트 (인증 없음 — tailnet이 인증)
 * - GET /api/schedules → 전체 목록 (켜짐/꺼짐 모두, nextOccurrenceAt 포함)
 * - POST /api/schedules → 생성 (formatId 존재, time HH:mm, recurring은 daysOfWeek 1개 이상, once는 date 필수이고 과거면 422)
 * - PUT /api/schedules/{id} → 수정 (켜기/끄기 포함)
 * - DELETE /api/schedules/{id} → 삭제
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * GET /api/schedules
     * 전체 예약 목록 (켜짐/꺼짐 모두 포함), nextOccurrenceAt 계산
     */
    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getAll() {
        List<ScheduleResponseDto> schedules = scheduleService.findAll();
        return ResponseEntity.ok(schedules);
    }

    /**
     * POST /api/schedules
     * 예약 생성 (201)
     */
    @PostMapping
    public ResponseEntity<ScheduleResponseDto> create(@RequestBody CreateScheduleRequestDto request) {
        ScheduleResponseDto created = scheduleService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /api/schedules/{id}
     * 예약 수정 (전체 교체, 켜기/끄기도 포함)
     */
    @PutMapping("/{id}")
    public ResponseEntity<ScheduleResponseDto> update(
            @PathVariable String id,
            @RequestBody CreateScheduleRequestDto request
    ) {
        ScheduleResponseDto updated = scheduleService.update(id, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /api/schedules/{id}
     * 예약 삭제 (204)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
