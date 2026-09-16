package com.harupaper.server.schedule;

import com.harupaper.server.auth.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * M6: Schedule(예약) 엔드포인트 (사용자 인증 필수)
 * 소유권 스코핑: 목록은 현재 사용자만, 단건 조회·수정·삭제는 소유자 아니면 404
 * - GET /api/schedules → 사용자의 예약 목록 (켜짐/꺼짐 모두, nextOccurrenceAt 포함)
 * - POST /api/schedules → 생성 (formatId 존재, time HH:mm, recurring은 daysOfWeek 1개 이상, once는 date 필수이고 과거면 422)
 * - PUT /api/schedules/{id} → 수정 (켜기/끄기 포함, 소유자만)
 * - DELETE /api/schedules/{id} → 삭제 (소유자만)
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
     * 사용자의 예약 목록 (켜짐/꺼짐 모두 포함), nextOccurrenceAt 계산
     */
    @GetMapping
    public ResponseEntity<List<ScheduleResponseDto>> getAll(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        List<ScheduleResponseDto> schedules = scheduleService.findAllByOwner(userId);
        return ResponseEntity.ok(schedules);
    }

    /**
     * POST /api/schedules
     * 예약 생성 (201)
     */
    @PostMapping
    public ResponseEntity<ScheduleResponseDto> create(
            @RequestBody CreateScheduleRequestDto request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        ScheduleResponseDto created = scheduleService.createWithOwner(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * PUT /api/schedules/{id}
     * 예약 수정 (전체 교체, 켜기/끄기도 포함, 소유자만 또는 404)
     */
    @PutMapping("/{id}")
    public ResponseEntity<ScheduleResponseDto> update(
            @PathVariable String id,
            @RequestBody CreateScheduleRequestDto request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        ScheduleResponseDto updated = scheduleService.updateWithOwnerCheck(id, request, userId);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /api/schedules/{id}
     * 예약 삭제 (204, 소유자만 또는 404)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        scheduleService.deleteWithOwnerCheck(id, userId);
        return ResponseEntity.noContent().build();
    }
}
